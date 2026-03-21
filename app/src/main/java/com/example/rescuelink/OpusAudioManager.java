package com.example.rescuelink;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OpusAudioManager {

    private static final int SAMPLE_RATE = 16000;
    private static final int BITRATE     = 12000;

    // WeakReference prevents memory leak if Activity is destroyed
    private final WeakReference<Context> contextRef;
    private final TransportBroker broker;
    private final String myMeshId;

    // True only on API 29+ — older devices work as repeaters but cannot transmit voice
    private final boolean canEncode;

    private AudioRecord audioRecord;
    private AudioTrack  audioTrack;
    private MediaCodec  encoder;
    private MediaCodec  decoder;
    private boolean     isRecording = false;

    // ExecutorService instead of raw threads — cleaner lifecycle management
    private ExecutorService encoderExecutor;
    private ExecutorService decoderExecutor;

    // Accurate timestamp tracking for contiguous presentation timestamps
    private long encoderPresentationTimeUs = 0;

    public OpusAudioManager(TransportBroker broker, String myMeshId, Context context) {
        this.contextRef = new WeakReference<>(context.getApplicationContext());
        this.broker     = broker;
        this.myMeshId   = myMeshId;
        this.canEncode  = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q; // Q = API 29
        setupDecoder();
    }

    // Returns true if this device can transmit voice (API 29+)
    // Returns false if device is repeater-only (API 24–28)
    // MainActivity uses this to show/hide the PTT button
    public boolean canTransmitAudio() {
        return canEncode;
    }

    // ─── Encoder Setup ───────────────────────────────────────────────────

    private void setupEncoder() throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        encoderExecutor = Executors.newFixedThreadPool(2); // input + output threads
        encoderPresentationTimeUs = 0; // reset timestamp on each recording session
    }

    // ─── Decoder Setup ───────────────────────────────────────────────────
    // Opus decoding is supported from API 21 — works on all devices this app targets

    private void setupDecoder() {
        try {
            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);

            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            decoder.configure(format, null, null, 0);
            decoder.start();

            decoderExecutor = Executors.newSingleThreadExecutor();

            int bufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    bufSize * 2, AudioTrack.MODE_STREAM);
            audioTrack.play();

        } catch (IOException e) {
            Log.e("OpusAudio", "Decoder setup failed: " + e.getMessage());
        }
    }

    // ─── Recording ───────────────────────────────────────────────────────

    public void startRecording() {
        // Guard: devices on API 24–28 cannot encode Opus — they act as repeaters only
        // MainActivity hides the PTT button for these devices via canTransmitAudio()
        // but this guard ensures no crash even if called accidentally
        if (!canEncode) {
            Log.d("OpusAudio", "Opus encoding not supported on API " + Build.VERSION.SDK_INT);
            return;
        }

        Context ctx = contextRef.get();
        if (ctx == null) return;
        if (ActivityCompat.checkSelfPermission(ctx,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;

        try {
            setupEncoder();
        } catch (IOException e) {
            Log.e("OpusAudio", "Encoder setup failed: " + e.getMessage());
            return;
        }

        int bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize);
        audioRecord.startRecording();
        isRecording = true;

        // Input thread: mic PCM → encoder input buffer
        encoderExecutor.execute(() -> {
            byte[] inputBuf = new byte[bufSize];
            while (isRecording) {
                int read = audioRecord.read(inputBuf, 0, inputBuf.length);
                if (read <= 0) continue;

                int inputIndex = encoder.dequeueInputBuffer(10000);
                if (inputIndex < 0) continue; // timeout, try again next loop

                ByteBuffer buf = encoder.getInputBuffer(inputIndex);
                if (buf != null) {
                    buf.clear();
                    buf.put(inputBuf, 0, read);
                    // Accurate contiguous timestamp — avoids decoder jitter
                    // Calculated from actual sample count, not wall clock
                    encoder.queueInputBuffer(inputIndex, 0, read,
                            encoderPresentationTimeUs, 0);
                    // read is in bytes, PCM 16-bit = 2 bytes per sample
                    encoderPresentationTimeUs += (read / 2) * 1_000_000L / SAMPLE_RATE;
                }
            }
            // Signal end of stream cleanly so encoder flushes remaining frames
            int inputIndex = encoder.dequeueInputBuffer(10000);
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(inputIndex, 0, 0, 0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        });

        // Output thread: encoder output → mesh packet
        encoderExecutor.execute(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (isRecording) {
                int outputIndex = encoder.dequeueOutputBuffer(info, 10000);

                // Format negotiation at codec startup — must handle before reading data
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d("OpusAudio", "Encoder format changed: " + encoder.getOutputFormat());
                    continue;
                }

                // Nothing ready yet — loop and try again
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }

                if (outputIndex >= 0) {
                    // Skip codec config frames — these are headers, not audio data
                    boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!isConfig && info.size > 0) {
                        ByteBuffer buf = encoder.getOutputBuffer(outputIndex);
                        if (buf != null) {
                            byte[] compressed = new byte[info.size];
                            buf.position(info.offset);
                            buf.limit(info.offset + info.size);
                            buf.get(compressed);

                            MeshPacket packet = new MeshPacket(
                                    'A', myMeshId, MeshPacket.BROADCAST_ID, compressed);
                            broker.send(packet);
                        }
                    }
                    encoder.releaseOutputBuffer(outputIndex, false);

                    // End of stream reached — exit output loop
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        });
    }

    // ─── Playback ────────────────────────────────────────────────────────

    // Called from mesh receive thread — offloaded to decoder executor to avoid blocking mesh
    public void playIncomingAudio(byte[] opusData) {
        if (decoder == null || audioTrack == null
                || decoderExecutor == null || decoderExecutor.isShutdown()) return;

        decoderExecutor.execute(() -> {
            // Feed compressed Opus frame into decoder input
            int inputIndex = decoder.dequeueInputBuffer(10000);
            if (inputIndex >= 0) {
                ByteBuffer buf = decoder.getInputBuffer(inputIndex);
                if (buf != null) {
                    buf.clear();
                    buf.put(opusData);
                    decoder.queueInputBuffer(inputIndex, 0, opusData.length,
                            System.nanoTime() / 1000, 0);
                }
            }

            // Pull decoded PCM and write directly to speaker
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outputIndex = decoder.dequeueOutputBuffer(info, 10000);

            // Format negotiation at decoder startup — nothing to read yet
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d("OpusAudio", "Decoder format changed: " + decoder.getOutputFormat());
                return;
            }

            if (outputIndex >= 0) {
                ByteBuffer buf = decoder.getOutputBuffer(outputIndex);
                if (buf != null && info.size > 0) {
                    byte[] pcm = new byte[info.size];
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    buf.get(pcm);
                    audioTrack.write(pcm, 0, pcm.length);
                }
                decoder.releaseOutputBuffer(outputIndex, false);
            }
        });
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────

    public void stopRecording() {
        isRecording = false;
        if (encoderExecutor != null) {
            // shutdown() — lets the current encoding task finish cleanly before stopping
            // shutdownNow() would interrupt mid-flight and leave MediaCodec in dirty state
            encoderExecutor.shutdown();
            encoderExecutor = null;
        }
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
        if (encoder != null) {
            encoder.stop();
            encoder.release();
            encoder = null;
        }
    }

    public void release() {
        stopRecording();
        if (decoderExecutor != null) {
            decoderExecutor.shutdown();
            decoderExecutor = null;
        }
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }
        if (decoder != null) {
            decoder.stop();
            decoder.release();
            decoder = null;
        }
    }
}