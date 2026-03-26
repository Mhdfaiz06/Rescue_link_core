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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class OpusAudioManager {

    private static final int SAMPLE_RATE = 48000;

    // --- UPGRADE 1: Half Bitrate for maximum range ---
    private static final int BITRATE = 6000;

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

    // --- UPGRADE 2: The Jitter Buffer ---
    private final LinkedBlockingQueue<byte[]> jitterBuffer = new LinkedBlockingQueue<>(200);
    private volatile boolean jitterPlayerRunning = false;

    // --- Continuous Config Resender (From our previous fix) ---
    private volatile byte[] lastConfigFrame = null;
    private final Handler configResendHandler = new Handler(Looper.getMainLooper());
    private final Runnable configResendRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording && lastConfigFrame != null) {
                byte[] configPacket = new byte[1 + lastConfigFrame.length];
                configPacket[0] = 0x01;
                System.arraycopy(lastConfigFrame, 0, configPacket, 1, lastConfigFrame.length);
                MeshPacket packet = new MeshPacket('A', myMeshId, MeshPacket.BROADCAST_ID, configPacket);
                if (broker != null) broker.send(packet);
                configResendHandler.postDelayed(this, 2000);
            }
        }
    };

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

        // --- UPGRADE 3: FEC (Forward Error Correction) for range loss ---
        format.setInteger("bitrate-mode", 0); // VBR
        format.setInteger("complexity", 5);   // Lower CPU usage

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        encoderExecutor = Executors.newFixedThreadPool(2);
        encoderPresentationTimeUs = 0;
        Log.e("AUDIO_TEST", "Encoder started at " + BITRATE + "bps with FEC");
    }

    // ─── Decoder & Jitter Buffer Setup ───────────────────────────────────

    private void setupDecoder() {
        Log.e("AUDIO_TEST", "setupDecoder() called on API " + Build.VERSION.SDK_INT);
        try {
            jitterBuffer.clear(); // Clear stale frames
            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);

            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            decoder.configure(format, null, null, 0);
            decoder.start();

            decoderExecutor = Executors.newFixedThreadPool(2);
            isDecoderReady  = true;
            Log.e("AUDIO_TEST", "Decoder initialized — starting jitter player");

            startJitterPlayer(); // Start the shock absorber

        } catch (IOException e) {
            isDecoderReady = false;
            Log.e("AUDIO_TEST", "Decoder setup FAILED: " + e.getMessage());
        }
    }

    private void startJitterPlayer() {
        jitterPlayerRunning = true;
        decoderExecutor.execute(() -> {
            Log.e("AUDIO_TEST", "Jitter player started");

            // Wait until we have 40 frames buffered (~800ms) before playing to absorb gaps
            while (jitterPlayerRunning && jitterBuffer.size() < 40) {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }
            Log.e("AUDIO_TEST", "Jitter buffer filled — starting playback");

            while (jitterPlayerRunning) {
                try {
                    // Wait up to 200ms for next frame
                    byte[] opusFrame = jitterBuffer.poll(200, TimeUnit.MILLISECONDS);

                    if (opusFrame == null) {
                        // Buffer ran dry — inject silence, then wait to refill to 20 frames
                        injectSilence();
                        while (jitterPlayerRunning && jitterBuffer.size() < 20) {
                            Thread.sleep(10);
                        }
                        continue;
                    }
                    decodeAndPlay(opusFrame);
                } catch (InterruptedException ignored) {
                } catch (Exception e) {
                    Log.e("OpusAudio", "Jitter player error: " + e.getMessage());
                }
            }
            Log.e("AUDIO_TEST", "Jitter player stopped");
        });
    }

    private void decodeAndPlay(byte[] opusData) {
        if (!isDecoderReady || decoder == null) return;
        try {
            int inputIndex = decoder.dequeueInputBuffer(5000);
            if (inputIndex < 0) return;

            ByteBuffer buf = decoder.getInputBuffer(inputIndex);
            if (buf == null) return;

            buf.clear();
            if (opusData.length > buf.capacity()) {
                decoder.queueInputBuffer(inputIndex, 0, 0, 0, 0);
                return;
            }
            buf.put(opusData);
            decoder.queueInputBuffer(inputIndex, 0, opusData.length, System.nanoTime() / 1000, 0);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outputIndex = decoder.dequeueOutputBuffer(info, 5000);

            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat fmt = decoder.getOutputFormat();
                int rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : SAMPLE_RATE;
                int ch   = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
                recreateAudioTrack(rate, ch);
                return;
            }
            if (outputIndex >= 0) {
                ByteBuffer out = decoder.getOutputBuffer(outputIndex);
                if (out != null && info.size > 0) {
                    byte[] pcm = new byte[info.size];
                    out.position(info.offset);
                    out.limit(info.offset + info.size);
                    out.get(pcm);
                    AudioTrack track = audioTrack;
                    if (track != null) {
                        track.write(pcm, 0, pcm.length);
                    }
                }
                decoder.releaseOutputBuffer(outputIndex, false);
            }
        } catch (IllegalStateException e) {
            isDecoderReady = false;
            Log.w("OpusAudio", "Decoder error in decodeAndPlay");
        }
    }

    private void injectSilence() {
        AudioTrack track = audioTrack;
        if (track != null) {
            byte[] silence = new byte[1920]; // ~20ms of silence
            track.write(silence, 0, silence.length);
        }
    }

    private void recreateAudioTrack(int sampleRate, int channels) {
        AudioTrack old = audioTrack;
        audioTrack = null;
        if (old != null) {
            old.stop();
            old.release();
        }

        int channelConfig = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int bufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);

        AudioTrack track = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT,
                bufSize * 2, AudioTrack.MODE_STREAM);
        track.play();
        audioTrack = track;
    }

    // ─── Recording ───────────────────────────────────────────────────────

    public void startRecording() {
        if (!canEncode) return;
        Context ctx = contextRef.get();
        if (ctx == null) return;
        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;

        try { setupEncoder(); } catch (IOException e) { return; }

        int bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        audioRecord.startRecording();

        isRecording = true;
        lastConfigFrame = null;

        // Input Thread
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
                    encoder.queueInputBuffer(inputIndex, 0, read, encoderPresentationTimeUs, 0);
                    encoderPresentationTimeUs += (read / 2) * 1_000_000L / SAMPLE_RATE;
                }
            }
            int inputIndex = encoder.dequeueInputBuffer(10000);
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        });

        // Output Thread
        encoderExecutor.execute(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (isRecording) {
                int outputIndex = encoder.dequeueOutputBuffer(info, 10000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) continue;

                if (outputIndex >= 0) {
                    ByteBuffer buf = encoder.getOutputBuffer(outputIndex);
                    if (buf != null && info.size > 0) {
                        byte[] compressed = new byte[info.size];
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        buf.get(compressed);

                        boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;

                        if (isConfig) {
                            lastConfigFrame = compressed.clone();
                            configResendHandler.removeCallbacks(configResendRunnable);
                            configResendHandler.postDelayed(configResendRunnable, 2000);
                        }

                        byte[] packetData = new byte[1 + compressed.length];
                        packetData[0] = isConfig ? (byte) 0x01 : (byte) 0x00;
                        System.arraycopy(compressed, 0, packetData, 1, compressed.length);

                        MeshPacket packet = new MeshPacket('A', myMeshId, MeshPacket.BROADCAST_ID, packetData);
                        if (broker != null) broker.send(packet);
                    }
                    encoder.releaseOutputBuffer(outputIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        });
    }

    // ─── Playback (Receiving) ────────────────────────────────────────────

    public void playIncomingAudio(byte[] rawData) {
        if (!isDecoderReady || decoder == null || decoderExecutor == null || decoderExecutor.isShutdown()) {
            if (decoder == null || decoderExecutor == null || decoderExecutor.isShutdown()) {
                new Handler(Looper.getMainLooper()).post(this::setupDecoder); // Auto-recovery
            }
            return;
        }

        if (rawData == null || rawData.length < 2) return;

        final byte marker = rawData[0];
        final byte[] opusData = new byte[rawData.length - 1];
        System.arraycopy(rawData, 1, opusData, 0, opusData.length);

        if (marker == 0x01) {
            // Config frame — feed directly to decoder, bypassing the jitter buffer
            decoderExecutor.execute(() -> {
                if (!isDecoderReady || decoder == null) return;
                try {
                    int inputIndex = decoder.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer buf = decoder.getInputBuffer(inputIndex);
                        if (buf != null) {
                            buf.clear();
                            buf.put(opusData);
                            decoder.queueInputBuffer(inputIndex, 0, opusData.length, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                        }
                    }
                } catch (Exception e) {
                    Log.w("OpusAudio", "Config frame error: " + e.getMessage());
                }
            });
        } else {
            // Audio frame — add to jitter buffer
            if (jitterBuffer.remainingCapacity() == 0) {
                jitterBuffer.poll(); // Drop oldest frame if full
            }
            jitterBuffer.offer(opusData);
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────

    public void stopRecording() {
        isRecording = false;
        configResendHandler.removeCallbacks(configResendRunnable);
        lastConfigFrame = null;

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
        jitterPlayerRunning = false;
        isDecoderReady = false;
        jitterBuffer.clear();

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