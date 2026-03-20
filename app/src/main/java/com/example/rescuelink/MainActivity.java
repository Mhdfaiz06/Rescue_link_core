package com.example.rescuelink;

import android.Manifest;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends AppCompatActivity {

    private static final String SERVICE_ID = "com.example.rescuelink.SERVICE";
    private final String USER_NICKNAME = Build.MANUFACTURER + " " + Build.MODEL;
    private String myMeshId;

    private ConnectionsClient connectionsClient;
    private final Map<String, String> connectedDevices = new ConcurrentHashMap<>();
    private final Map<String, Integer> deviceScores = new ConcurrentHashMap<>();
    private boolean isHost = false;
    private boolean isConnected = false;
    private boolean isAppInBackground = false;

    private final Map<String, String> routingTable = new ConcurrentHashMap<>();
    
    // Fixed self-cleaning thread-safe LRU cache for sequence IDs
    private final Set<String> seenSequenceIds = Collections.newSetFromMap(
        Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(512, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > 500;
            }
        })
    );
    
    private long sequenceCounter = 0;
    private static final int HEADER_SIZE = 18;
    private static final int MAX_TTL = 6;

    private static final int JAMMING_THRESHOLD_MS = 1500;
    private long lastPacketTime = 0;
    private int packetCount = 0;

    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private boolean isRecording = false;
    private int minBuffSize;

    private TextView statusText, myIdText, debugLog;
    private Button btnPtt, btnSend;
    private EditText inputMessage;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long SCAN_DURATION = 5000 + new Random().nextInt(3000);
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        myMeshId = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        initUI();
        setupCrashHandler();

        try {
            connectionsClient = Nearby.getConnectionsClient(this);
            if (hasPermissions()) { initAppLogic(); } else { requestPermissions(); }
        } catch (Exception e) { log("CRASH IN ONCREATE: " + e.getMessage()); }
    }

    private void initUI() {
        statusText = findViewById(R.id.statusText);
        myIdText = findViewById(R.id.myIdText);
        debugLog = findViewById(R.id.debugLog);
        btnPtt = findViewById(R.id.btnPtt);
        btnSend = findViewById(R.id.btnSend);
        inputMessage = findViewById(R.id.inputMessage);
        myIdText.setText(String.format("%s [%s]", USER_NICKNAME, myMeshId));
        debugLog.setMovementMethod(new ScrollingMovementMethod());
    }

    @Override
    protected void onPause() { super.onPause(); isAppInBackground = true; log("MODE: Repeater (Background)"); }

    @Override
    protected void onResume() { super.onResume(); isAppInBackground = false; log("MODE: Active Node (Foreground)"); }

    private void initAppLogic() {
        setupAudio();
        setupListeners();
        startAutonomousNetwork();
        startOptimizationLoop();
    }

    private byte[] createMeshPacket(char tag, String targetId, byte[] payload) {
        ByteBuffer bb = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        bb.put((byte) tag);
        bb.put(myMeshId.getBytes(StandardCharsets.US_ASCII));
        bb.put(targetId.substring(0, Math.min(targetId.length(), 4)).getBytes(StandardCharsets.US_ASCII));
        bb.putLong(++sequenceCounter);
        bb.put((byte) MAX_TTL);
        bb.put(payload);
        return bb.array();
    }

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() != Payload.Type.BYTES) return;
            byte[] data = payload.asBytes();
            if (data == null || data.length < HEADER_SIZE) return;

            monitorTraffic();

            // REPEATER MODE CHECK: If backgrounded, use optimized blind relay
            if (isAppInBackground) {
                blindRelay(endpointId, data);
                return;
            }

            ByteBuffer bb = ByteBuffer.wrap(data);
            char tag = (char) bb.get();
            byte[] originBytes = new byte[4]; bb.get(originBytes);
            byte[] targetBytes = new byte[4]; bb.get(targetBytes);
            long seq = bb.getLong();
            int ttl = bb.get();
            
            String originId = new String(originBytes, StandardCharsets.US_ASCII);
            String targetId = new String(targetBytes, StandardCharsets.US_ASCII);
            String packetKey = originId + ":" + seq;

            if (seenSequenceIds.contains(packetKey)) return;
            seenSequenceIds.add(packetKey);

            routingTable.put(originId, endpointId);

            boolean isForMe = targetId.equals("FFFF") || targetId.equals(myMeshId);

            if (isForMe) {
                byte[] body = new byte[data.length - HEADER_SIZE];
                bb.get(body);
                processIncomingPayload(tag, originId, body);
            }

            if (ttl > 0) {
                data[HEADER_SIZE - 1] = (byte) (ttl - 1);
                if (tag == 'A' || targetId.equals("FFFF")) {
                    relayToAllExcept(endpointId, data);
                } else if (!isForMe) {
                    relayToNextHop(targetId, data);
                }
            }
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {}
    };

    private void processIncomingPayload(char tag, String originId, byte[] body) {
        if (tag == 'A') {
            if (audioTrack != null) audioTrack.write(body, 0, body.length);
        } else if (tag == 'M') {
            log(String.format("%s: %s", originId, new String(body, StandardCharsets.UTF_8)));
        } else if (tag == 'S') {
            handleStatusUpdate(originId, body);
        }
    }

    private void relayToAllExcept(String senderId, byte[] data) {
        List<String> targets = new ArrayList<>();
        for (String id : connectedDevices.keySet()) {
            if (!id.equals(senderId)) targets.add(id);
        }
        if (!targets.isEmpty()) {
            connectionsClient.sendPayload(targets, Payload.fromBytes(data));
        }
    }

    private void relayToNextHop(String targetId, byte[] data) {
        String nextHop = routingTable.get(targetId);
        if (nextHop != null && connectedDevices.containsKey(nextHop)) {
            connectionsClient.sendPayload(nextHop, Payload.fromBytes(data));
        } else {
            relayToAllExcept("", data); 
        }
    }

    private void blindRelay(String senderId, byte[] data) {
        if (data.length < HEADER_SIZE) return;
        ByteBuffer bb = ByteBuffer.wrap(data);
        bb.get(); 
        byte[] origin = new byte[4]; bb.get(origin);
        bb.get(new byte[4]); 
        long seq = bb.getLong();
        String key = new String(origin, StandardCharsets.US_ASCII) + ":" + seq;

        if (seenSequenceIds.contains(key)) return;
        seenSequenceIds.add(key);

        int ttl = bb.get(HEADER_SIZE - 1);
        if (ttl > 0) {
            data[HEADER_SIZE - 1] = (byte) (ttl - 1);
            relayToAllExcept(senderId, data);
        }
    }

    private void monitorTraffic() {
        long now = System.currentTimeMillis();
        packetCount++;
        if (now - lastPacketTime > JAMMING_THRESHOLD_MS) {
            if (packetCount < 3 && isConnected) resolveJamming();
            packetCount = 0;
            lastPacketTime = now;
        }
    }

    private void resolveJamming() {
        log("⚠️ JAM DETECTED: Signal Re-strengthening...");
        byte[] boost = createMeshPacket('S', "FFFF", "BOOST_PRIORITY".getBytes(StandardCharsets.UTF_8));
        sendMeshPayload(boost);
    }

    private void setupAudio() {
        minBuffSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBuffSize <= 0) minBuffSize = 3840;
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT, minBuffSize, AudioTrack.MODE_STREAM);
        audioTrack.play();
    }

    private void startRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        if (connectedDevices.isEmpty()) return;

        isRecording = true;
        final int safeBufferSize = minBuffSize;

        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, safeBufferSize);
            audioRecord.startRecording();

            new Thread(() -> {
                byte[] buffer = new byte[safeBufferSize / 2];
                while (isRecording) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        byte[] pcm = new byte[read];
                        System.arraycopy(buffer, 0, pcm, 0, read);
                        byte[] packet = createMeshPacket('A', "FFFF", pcm);
                        sendMeshPayload(packet);
                    }
                }
            }).start();
        } catch (Exception e) { log("Mic Error: " + e.getMessage()); }
    }

    private void stopRecording() {
        isRecording = false;
        if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); audioRecord = null; }
    }

    private void startAutonomousNetwork() {
        if (isConnected) return;
        isHost = false;
        statusText.setText(R.string.status_scanning);
        startDiscovery();
        handler.postDelayed(becomeHostRunnable, SCAN_DURATION);
    }

    private final Runnable becomeHostRunnable = () -> {
        if (!isConnected) {
            log("AUTO: Region empty. Electing self as Host.");
            connectionsClient.stopDiscovery();
            startAdvertising();
        }
    };

    private void startAdvertising() {
        AdvertisingOptions options = new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
        connectionsClient.startAdvertising(USER_NICKNAME, SERVICE_ID, connectionLifecycleCallback, options)
                .addOnSuccessListener(unused -> { 
                    isHost = true; 
                    statusText.setText(R.string.status_host); 
                });
    }

    private void startDiscovery() {
        DiscoveryOptions options = new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options);
    }

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            log("Found Leader: " + info.getEndpointName());
            handler.removeCallbacks(becomeHostRunnable);
            connectionsClient.stopDiscovery();
            connectionsClient.requestConnection(USER_NICKNAME, endpointId, connectionLifecycleCallback);
        }
        @Override public void onEndpointLost(@NonNull String endpointId) {}
    };

    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            connectionsClient.acceptConnection(endpointId, payloadCallback);
            connectedDevices.put(endpointId, info.getEndpointName());
        }
        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                isConnected = true;
                handler.removeCallbacks(becomeHostRunnable);
                log(">>> Linked: " + connectedDevices.get(endpointId));
            } else { connectedDevices.remove(endpointId); }
        }
        @Override
        public void onDisconnected(@NonNull String endpointId) {
            connectedDevices.remove(endpointId);
            routingTable.values().remove(endpointId);
            if (connectedDevices.isEmpty()) { isConnected = false; startAutonomousNetwork(); }
        }
    };

    private void sendMeshPayload(byte[] data) {
        if (connectedDevices.isEmpty()) return;
        connectionsClient.sendPayload(new ArrayList<>(connectedDevices.keySet()), Payload.fromBytes(data));
    }

    private void startOptimizationLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isConnected && !isAppInBackground) {
                    byte[] status = createMeshPacket('S', "FFFF", String.valueOf(getBatteryLevel()).getBytes(StandardCharsets.UTF_8));
                    sendMeshPayload(status);
                }
                handler.postDelayed(this, 10000);
            }
        }, 5000);
    }

    private int getBatteryLevel() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = this.registerReceiver(null, ifilter);
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            return (int) ((level / (float) scale) * 100);
        }
        return 50;
    }

    private void setupListeners() {
        btnPtt.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) { 
                startRecording(); 
                btnPtt.setText(R.string.ptt_talking); 
            }
            else if (event.getAction() == MotionEvent.ACTION_UP) { 
                stopRecording(); 
                btnPtt.setText(R.string.ptt_idle); 
            }
            v.performClick();
            return true;
        });
        btnSend.setOnClickListener(v -> {
            String m = inputMessage.getText().toString();
            if (!m.isEmpty()) {
                byte[] packet = createMeshPacket('M', "FFFF", m.getBytes(StandardCharsets.UTF_8));
                sendMeshPayload(packet);
                log("Me: " + m);
                inputMessage.setText("");
            }
        });
    }

    private void handleStatusUpdate(String originId, byte[] body) {
        try {
            int score = Integer.parseInt(new String(body, StandardCharsets.UTF_8));
            deviceScores.put(originId, score);
            if (!isHost && score < 15) { log("Host Weak. Re-Clustering..."); disconnectAll(); startAutonomousNetwork(); }
        } catch (Exception e) {}
    }

    private void disconnectAll() {
        connectionsClient.stopAllEndpoints();
        connectionsClient.stopAdvertising();
        connectionsClient.stopDiscovery();
        connectedDevices.clear();
        routingTable.clear();
        isConnected = false;
    }

    private void log(String msg) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        runOnUiThread(() -> { if (debugLog != null) debugLog.append("\n[" + t + "] " + msg); });
    }

    private void setupCrashHandler() {
        prefs = getSharedPreferences("RescuePrefs", MODE_PRIVATE);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            prefs.edit().putString("last_crash", Log.getStackTraceString(e)).apply();
            System.exit(2);
        });
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, "android.permission.NEARBY_WIFI_DEVICES") == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO, "android.permission.NEARBY_WIFI_DEVICES", Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT}, 123);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO}, 123);
        }
    }

    @Override protected void onDestroy() { super.onDestroy(); disconnectAll(); }
}