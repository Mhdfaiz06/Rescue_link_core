package com.example.rescuelink;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
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
import android.text.TextUtils;
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

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends AppCompatActivity {

    private static final String SERVICE_ID = "com.example.rescuelink.SERVICE";
    private final String USER_NICKNAME = Build.MANUFACTURER + " " + Build.MODEL;

    // --- NETWORKING & MESH STATE ---
    private ConnectionsClient connectionsClient;
    private final Map<String, String> connectedDevices = new ConcurrentHashMap<>();
    private final Map<String, Integer> deviceScores = new ConcurrentHashMap<>();
    private boolean isHost = false;
    private boolean isConnected = false;
    private boolean isAppInBackground = false;

    // --- JAMMING & SCALABILITY CONSTANTS ---
    private static final int MAX_ACTIVE_NODES = 8;
    private static final int JAMMING_THRESHOLD_MS = 1500;
    private long lastPacketTime = 0;
    private int packetCount = 0;

    // --- AUDIO CONFIGURATION (LOW LATENCY) ---
    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private boolean isRecording = false;
    private int minBuffSize;

    // --- UI & UTILS ---
    private TextView statusText, myIdText, debugLog;
    private Button btnPtt, btnSend, btnDisconnect;
    private EditText inputMessage;
    private Handler handler = new Handler(Looper.getMainLooper());
    private final long SCAN_DURATION = 5000 + new Random().nextInt(3000);
    private static final int PERMISSION_REQUEST_CODE = 123;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initUI();
        setupCrashHandler();

        String lastCrash = prefs.getString("last_crash", null);
        if (lastCrash != null) {
            statusText.setText("🛑 SYSTEM HALTED DUE TO CRASH");
            debugLog.setText("⚠️ PASTE THIS TO AI ⚠️\n\n" + lastCrash);
            prefs.edit().remove("last_crash").apply();
            return;
        }

        try {
            connectionsClient = Nearby.getConnectionsClient(this);
            if (hasPermissions()) {
                initAppLogic();
            } else {
                requestPermissions();
            }
        } catch (Exception e) {
            log("CRASH IN ONCREATE: " + e.getMessage());
        }
    }

    private void initUI() {
        statusText = findViewById(R.id.statusText);
        myIdText = findViewById(R.id.myIdText);
        debugLog = findViewById(R.id.debugLog);
        btnPtt = findViewById(R.id.btnPtt);
        btnSend = findViewById(R.id.btnSend);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        inputMessage = findViewById(R.id.inputMessage);
        myIdText.setText(USER_NICKNAME);
        debugLog.setMovementMethod(new ScrollingMovementMethod());
    }

    // --- APP LIFECYCLE FOR REPEATER MODE ---
    @Override
    protected void onPause() {
        super.onPause();
        isAppInBackground = true;
        log("MODE: Repeater (Background Relay Active)");
    }

    @Override
    protected void onResume() {
        super.onResume();
        isAppInBackground = false;
        log("MODE: Active Node (Foreground)");
    }

    private void initAppLogic() {
        try {
            // --- GPS HARDWARE CHECK ---
            LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

            if (!isGpsEnabled && !isNetworkEnabled) {
                statusText.setText("🛑 REQUIRED: Turn ON Phone Location/GPS");
                debugLog.setText("Android requires the physical Location toggle to be ON to scan for nearby mesh networks. Please turn it on in your phone settings and restart the app.");
                return;
            }

            setupAudio();
            setupListeners();
            log("AUTO: Initializing Self-Healing Network...");
            startAutonomousNetwork();
            startOptimizationLoop();
        } catch (Exception e) {
            log("CRASH CAUGHT IN INIT: " + e.getMessage());
        }
    }

    // ==========================================
    //      ROUTING MANAGER (THE BRAIN)
    // ==========================================

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() != Payload.Type.BYTES) return;
            byte[] data = payload.asBytes();
            if (data == null) return;

            monitorTraffic(); // Jamming Detection

            // BLIND FORWARDING: If backgrounded, relay without decoding to save CPU
            if (isAppInBackground) {
                blindRelay(endpointId, data);
                return;
            }

            // ACTIVE PROCESSING: Decapsulate based on 1-byte Header
            char tag = (char) data[0];
            if (tag == 'A') {
                if (audioTrack != null) audioTrack.write(data, 1, data.length - 1);
                if (isHost) relayPayload(endpointId, data);
            } else if (tag == 'M') {
                log(new String(data, StandardCharsets.UTF_8).substring(2));
                if (isHost) relayPayload(endpointId, data);
            } else if (tag == 'S') {
                handleStatusUpdate(endpointId, data);
            }
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {}
    };

    private void blindRelay(String senderId, byte[] data) {
        List<String> relayTargets = new ArrayList<>();
        for (String id : connectedDevices.keySet()) {
            if (!id.equals(senderId)) relayTargets.add(id);
        }
        if (!relayTargets.isEmpty()) {
            connectionsClient.sendPayload(relayTargets, Payload.fromBytes(data));
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
        sendPayloadToAll("S:BOOST_PRIORITY".getBytes(StandardCharsets.UTF_8));
    }

    // ==========================================
    //      AUDIO CORE (LOW LATENCY)
    // ==========================================

    private void setupAudio() {
        minBuffSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBuffSize <= 0) minBuffSize = 3840;
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT, minBuffSize, AudioTrack.MODE_STREAM);
        if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
            audioTrack.play();
        } else {
            log("WARNING: Speaker hardware is locked.");
        }
    }

    private void startRecording() {
        // Linter Fix & Safety Checks
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("ERROR: Mic permission missing.");
            return;
        }
        if (connectedDevices.isEmpty()) return;

        isRecording = true;
        final int safeBufferSize = minBuffSize;

        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, safeBufferSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                log("ERROR: AudioRecord failed to initialize.");
                return;
            }
            audioRecord.startRecording();

            new Thread(() -> {
                byte[] buffer = new byte[safeBufferSize / 2];
                while (isRecording) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        byte[] out = new byte[read + 1];
                        out[0] = 'A'; // Audio Header
                        System.arraycopy(buffer, 0, out, 1, read);
                        sendPayloadToAll(out);
                    }
                }
            }).start();
        } catch (Exception e) {
            log("CRASH IN AUDIO: " + e.getMessage());
        }
    }

    private void stopRecording() {
        isRecording = false;
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
    }

    // ==========================================
    //      NETWORK HEALTH & OPTIMIZATION
    // ==========================================

    private void startOptimizationLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isConnected && !isAppInBackground) {
                    sendPayloadToAll(("S:" + getBatteryLevel()).getBytes(StandardCharsets.UTF_8));
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

    private void checkNetworkHealth(String senderId, int theirScore) {
        if (!isHost && connectedDevices.containsKey(senderId) && theirScore < 15) {
            log("⚠️ WARNING: Host Battery Critical. Searching for new Host...");
            disconnectAll();
            startAutonomousNetwork();
        }
    }

    private void handleStatusUpdate(String id, byte[] data) {
        try {
            String statusMsg = new String(data, StandardCharsets.UTF_8).substring(2);
            if (statusMsg.equals("BOOST_PRIORITY")) {
                // Future multi-hop logic handles power amplification here
                return;
            }
            int score = Integer.parseInt(statusMsg);
            deviceScores.put(id, score);
            checkNetworkHealth(id, score);
        } catch (Exception e) {}
    }

    // ==========================================
    //      NETWORK LIFECYCLE & UTILS
    // ==========================================

    private void startAutonomousNetwork() {
        if (isConnected) return;
        isHost = false;
        statusText.setText("Status: Scanning Regions...");
        startDiscovery();
        handler.postDelayed(becomeHostRunnable, SCAN_DURATION);
    }

    private Runnable becomeHostRunnable = () -> {
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
                    statusText.setText("HOST (Leader) | Waiting for nodes...");
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

                String role = isHost ? "HOST (Leader)" : "NODE (Client)";
                statusText.setText(role + " | Connected peers: " + connectedDevices.size());
            } else {
                connectedDevices.remove(endpointId);
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            log("<<< Disconnected: " + connectedDevices.remove(endpointId));

            if (connectedDevices.isEmpty()) {
                isConnected = false;
                if (!isHost) {
                    log("⚠️ ALERT: Host Lost! Reorganizing...");
                    startAutonomousNetwork();
                } else {
                    statusText.setText("HOST (Leader) | Waiting for nodes...");
                }
            } else {
                String role = isHost ? "HOST (Leader)" : "NODE (Client)";
                statusText.setText(role + " | Connected peers: " + connectedDevices.size());
            }
        }
    };

    private void sendPayloadToAll(byte[] bytes) {
        if (connectedDevices.isEmpty()) return;
        connectionsClient.sendPayload(new ArrayList<>(connectedDevices.keySet()), Payload.fromBytes(bytes));
    }

    private void relayPayload(String senderId, byte[] bytes) {
        List<String> targets = new ArrayList<>();
        for (String id : connectedDevices.keySet()) { if (!id.equals(senderId)) targets.add(id); }
        if (!targets.isEmpty()) connectionsClient.sendPayload(targets, Payload.fromBytes(bytes));
    }

    private void setupListeners() {
        btnDisconnect.setOnClickListener(v -> {
            disconnectAll();
            startAutonomousNetwork();
        });

        btnPtt.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                startRecording();
                btnPtt.setText("TALKING...");
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                stopRecording();
                btnPtt.setText("HOLD TO TALK");
            }
            return true;
        });

        btnSend.setOnClickListener(v -> {
            String m = inputMessage.getText().toString();
            if (!m.isEmpty()) {
                sendPayloadToAll(("M:" + USER_NICKNAME + ": " + m).getBytes(StandardCharsets.UTF_8));
                log("Me: " + m);
                inputMessage.setText("");
            }
        });
    }

    private void disconnectAll() {
        handler.removeCallbacks(becomeHostRunnable);
        connectionsClient.stopAllEndpoints();
        connectionsClient.stopAdvertising();
        connectionsClient.stopDiscovery();
        connectedDevices.clear();
        statusText.setText("Disconnected");
        isConnected = false;
        isHost = false;
    }

    private void log(String msg) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        runOnUiThread(() -> {
            debugLog.append("\n[" + t + "] " + msg);
            if (debugLog.getLayout() != null) {
                final int scrollAmount = debugLog.getLayout().getLineTop(debugLog.getLineCount()) - debugLog.getHeight();
                if (scrollAmount > 0) debugLog.scrollTo(0, scrollAmount);
            }
        });
    }

    private void setupCrashHandler() {
        prefs = getSharedPreferences("RescuePrefs", MODE_PRIVATE);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            prefs.edit().putString("last_crash", Log.getStackTraceString(e)).commit();
            System.exit(2);
        });
    }

    // ==========================================
    //      CRASH-PROOF PERMISSION HANDLING
    // ==========================================
    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, "android.permission.NEARBY_WIFI_DEVICES") == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= 31) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= 33) {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    "android.permission.NEARBY_WIFI_DEVICES",
                    Manifest.permission.RECORD_AUDIO
            };
        } else if (Build.VERSION.SDK_INT >= 31) {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.RECORD_AUDIO
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECORD_AUDIO
            };
        }
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) allGranted = false;
            }
            if (allGranted && grantResults.length > 0) {
                log("Permissions Granted! Starting System...");
                initAppLogic();
            } else {
                statusText.setText("🛑 PERMISSIONS DENIED BY OS");
                debugLog.setText("Android refused to grant a permission. Please allow in phone settings.");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            disconnectAll();
            handler.removeCallbacksAndMessages(null);
            if (audioTrack != null) {
                if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) audioTrack.stop();
                audioTrack.release();
                audioTrack = null;
            }
            stopRecording();
        } catch (Exception e) {}
    }
}