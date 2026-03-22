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

    // 48000Hz matches Opus internal sample rate — decoder always outputs 48000Hz
    private static final int SAMPLE_RATE = 48000;
    private static final int BITRATE     = 12000; // controls packet size, not sample rate

    private final WeakReference<Context> contextRef;
    private final TransportBroker broker;
    private final String myMeshId;

    private final boolean canEncode;
    private volatile boolean isDecoderReady = false;

    private AudioRecord audioRecord;
    private volatile AudioTrack audioTrack;
    private MediaCodec encoder;
    private MediaCodec decoder;
    private boolean isRecording = false;

    private ExecutorService encoderExecutor;
    private ExecutorService decoderExecutor;

    private long encoderPresentationTimeUs = 0;

    public OpusAudioManager(TransportBroker broker, String myMeshId, Context context) {
        this.contextRef = new WeakReference<>(context.getApplicationContext());
        this.broker     = broker;
        this.myMeshId   = myMeshId;
        this.canEncode  = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        setupDecoder();
    }

    public boolean canTransmitAudio() {
        return canEncode;
    }

    // ─── Encoder Setup ───────────────────────────────────────────────────

    private void setupEncoder() throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        encoderExecutor = Executors.newFixedThreadPool(2);
        encoderPresentationTimeUs = 0;
    }

    // ─── Decoder Setup ───────────────────────────────────────────────────

    private void setupDecoder() {
        Log.e("AUDIO_TEST", "setupDecoder() called on API " + Build.VERSION.SDK_INT);
        try {
            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);

            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            decoder.configure(format, null, null, 0);
            decoder.start();

            // Two threads — one feeds input, one drains output continuously
            decoderExecutor = Executors.newFixedThreadPool(2);

            // AudioTrack is NOT created here
            // It is created in recreateAudioTrack() when INFO_OUTPUT_FORMAT_CHANGED fires
            // because the decoder tells us the real output sample rate at that point

            isDecoderReady = true;
            Log.e("AUDIO_TEST", "Decoder initialized successfully");

            startDecoderOutputLoop();

        } catch (IOException e) {
            isDecoderReady = false;
            Log.e("AUDIO_TEST", "Decoder setup FAILED: " + e.getMessage());
            Log.e("AUDIO_TEST", "Stack: " + Log.getStackTraceString(e));
        }
    }

    // ─── Decoder Output Loop ─────────────────────────────────────────────
    // Runs permanently — drains decoded PCM from decoder and writes to speaker
    // Separated from input feeding because MediaCodec buffers internally
    // and won't output until several frames have been fed

    private void startDecoderOutputLoop() {
        decoderExecutor.execute(() -> {
            Log.d("AUDIO_TRACE", "Decoder output loop started");
            while (isDecoderReady) {
                if (decoder == null) break;
                try {
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    int outputIndex = decoder.dequeueOutputBuffer(info, 100000);

                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Decoder is telling us its actual output format
                        // Read the real sample rate and recreate AudioTrack to match
                        MediaFormat actualFormat = decoder.getOutputFormat();
                        int actualSampleRate = actualFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                                ? actualFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                : SAMPLE_RATE;
                        int actualChannels = actualFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                                ? actualFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                : 1;
                        Log.e("AUDIO_TEST", "Decoder actual output: "
                                + actualSampleRate + "Hz " + actualChannels + "ch");
                        recreateAudioTrack(actualSampleRate, actualChannels);
                        continue;
                    }

                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        continue;
                    }

                    if (outputIndex >= 0) {
                        ByteBuffer buf = decoder.getOutputBuffer(outputIndex);
                        if (buf != null && info.size > 0) {
                            byte[] pcm = new byte[info.size];
                            buf.position(info.offset);
                            buf.limit(info.offset + info.size);
                            buf.get(pcm);
                            AudioTrack track = audioTrack; // local ref — thread safe
                            if (track != null) {
                                track.write(pcm, 0, pcm.length);
                                Log.d("AUDIO_TRACE", "PCM written: " + pcm.length + " bytes");
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false);
                    }

                } catch (IllegalStateException e) {
                    isDecoderReady = false;
                    Log.w("OpusAudio", "Decoder output loop stopped: " + e.getMessage());
                    break;
                } catch (Exception e) {
                    Log.e("OpusAudio", "Decoder output error: " + e.getMessage());
                }
            }
            Log.d("AUDIO_TRACE", "Decoder output loop ended");
        });
    }

    private void recreateAudioTrack(int sampleRate, int channels) {
        // Stop and release old AudioTrack before creating new one
        AudioTrack old = audioTrack;
        audioTrack = null;
        if (old != null) {
            old.stop();
            old.release();
        }

        int channelConfig = channels == 2
                ? AudioFormat.CHANNEL_OUT_STEREO
                : AudioFormat.CHANNEL_OUT_MONO;

        int bufSize = AudioTrack.getMinBufferSize(
                sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);

        AudioTrack track = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize * 2,
                AudioTrack.MODE_STREAM);
        track.play();
        audioTrack = track;

        Log.e("AUDIO_TEST", "AudioTrack recreated at " + sampleRate + "Hz");
    }

    // ─── Recording ───────────────────────────────────────────────────────

    public void startRecording() {
        Log.e("AUDIO_TEST", "startRecording() called");
        if (!canEncode) {
            Log.d("OpusAudio", "Encoding not supported on API " + Build.VERSION.SDK_INT);
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
                if (inputIndex < 0) continue;

                ByteBuffer buf = encoder.getInputBuffer(inputIndex);
                if (buf != null) {
                    buf.clear();
                    buf.put(inputBuf, 0, read);
                    encoder.queueInputBuffer(inputIndex, 0, read,
                            encoderPresentationTimeUs, 0);
                    encoderPresentationTimeUs += (read / 2) * 1_000_000L / SAMPLE_RATE;
                }
            }
            // Signal end of stream so encoder flushes remaining frames
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

                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d("OpusAudio", "Encoder format changed: " + encoder.getOutputFormat());
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }

                if (outputIndex >= 0) {
                    ByteBuffer buf = encoder.getOutputBuffer(outputIndex);
                    if (buf != null && info.size > 0) {
                        byte[] compressed = new byte[info.size];
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        buf.get(compressed);

                        boolean isConfig =
                                (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;

                        // Prefix every packet with 1 marker byte
                        // 0x01 = codec config frame — receiver feeds to decoder as CSD
                        // 0x00 = normal audio frame
                        byte[] packetData = new byte[1 + compressed.length];
                        packetData[0] = isConfig ? (byte) 0x01 : (byte) 0x00;
                        System.arraycopy(compressed, 0, packetData, 1, compressed.length);

                        MeshPacket packet = new MeshPacket(
                                'A', myMeshId, MeshPacket.BROADCAST_ID, packetData);
                        broker.send(packet);
                        Log.d("AUDIO_TRACE", (isConfig ? "Config" : "Audio")
                                + " frame sent: " + compressed.length + " bytes");
                    }
                    encoder.releaseOutputBuffer(outputIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        });
    }

    // ─── Playback ────────────────────────────────────────────────────────

    public void playIncomingAudio(byte[] rawData) {
        Log.e("AUDIO_TEST", "playIncomingAudio() called, size: "
                + (rawData == null ? "null" : rawData.length));

        if (!isDecoderReady || decoder == null
                || decoderExecutor == null || decoderExecutor.isShutdown()) {
            Log.w("AUDIO_TRACE", "Dropping frame — decoder not ready");
            return;
        }

        if (rawData == null || rawData.length < 2) {
            Log.w("AUDIO_TRACE", "Dropping frame — too small");
            return;
        }

        final byte marker = rawData[0];
        final byte[] opusData = new byte[rawData.length - 1];
        System.arraycopy(rawData, 1, opusData, 0, opusData.length);

        Log.d("AUDIO_TRACE", "Feeding " + (marker == 0x01 ? "CONFIG" : "AUDIO")
                + " frame: " + opusData.length + " bytes");

        // Only feeds input — output loop drains PCM continuously in background
        decoderExecutor.execute(() -> {
            if (!isDecoderReady || decoder == null) return;
            try {
                int inputIndex = decoder.dequeueInputBuffer(10000);
                if (inputIndex < 0) {
                    Log.w("AUDIO_TRACE", "No input buffer available, dropping frame");
                    return;
                }

                ByteBuffer buf = decoder.getInputBuffer(inputIndex);
                if (buf == null) return;

                buf.clear();
                if (opusData.length > buf.capacity()) {
                    Log.e("AUDIO_TRACE", "Frame too large: " + opusData.length);
                    decoder.queueInputBuffer(inputIndex, 0, 0, 0, 0);
                    return;
                }
                buf.put(opusData);

                if (marker == 0x01) {
                    decoder.queueInputBuffer(inputIndex, 0, opusData.length,
                            0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                    Log.d("AUDIO_TRACE", "Config frame queued");
                } else {
                    decoder.queueInputBuffer(inputIndex, 0, opusData.length,
                            System.nanoTime() / 1000, 0);
                }

            } catch (IllegalStateException e) {
                isDecoderReady = false;
                Log.w("OpusAudio", "Decoder input error: " + e.getMessage());
            } catch (Exception e) {
                Log.e("OpusAudio", "Decoder input error: " + e.getMessage());
            }
        });
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────

    public void stopRecording() {
        isRecording = false;
        if (encoderExecutor != null) {
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
        isDecoderReady = false; // set before releasing — blocks any queued frames
        if (decoderExecutor != null) {
            decoderExecutor.shutdown();
            decoderExecutor = null;
        }
        AudioTrack track = audioTrack;
        audioTrack = null;
        if (track != null) {
            track.stop();
            track.release();
        }
        if (decoder != null) {
            decoder.stop();
            decoder.release();
            decoder = null;
        }
    }
}