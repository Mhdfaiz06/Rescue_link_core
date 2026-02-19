package com.example.rescuelink;

import android.Manifest;
import android.content.Context;
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
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private static final String SERVICE_ID = "com.example.rescuelink.SERVICE";
    private final String USER_NICKNAME = Build.MANUFACTURER + " " + Build.MODEL;

    private ConnectionsClient connectionsClient;
    private TextView statusText, myIdText, debugLog;
    private Button btnPtt, btnSend, btnDisconnect;
    private EditText inputMessage;

    private final Map<String, String> connectedDevices = new HashMap<>();
    private final Map<String, Integer> deviceScores = new HashMap<>();

    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private boolean isRecording = false;
    private Thread recordingThread;
    private int minBuffSize;

    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isHost = false;
    private boolean isConnected = false;
    private final long SCAN_DURATION = 5000 + new Random().nextInt(3000);

    private static final int PERMISSION_REQUEST_CODE = 123;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // --- THE FLIGHT RECORDER (Catches invisible crashes) ---
        prefs = getSharedPreferences("RescuePrefs", MODE_PRIVATE);
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            String error = Log.getStackTraceString(throwable);
            prefs.edit().putString("last_crash", error).commit();
            System.exit(2);
        });

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        myIdText = findViewById(R.id.myIdText);
        debugLog = findViewById(R.id.debugLog);
        btnPtt = findViewById(R.id.btnPtt);
        btnSend = findViewById(R.id.btnSend);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        inputMessage = findViewById(R.id.inputMessage);

        myIdText.setText(USER_NICKNAME);
        debugLog.setMovementMethod(new ScrollingMovementMethod());

        // --- CHECK FOR PREVIOUS CRASH ---
        String lastCrash = prefs.getString("last_crash", null);
        if (lastCrash != null) {
            statusText.setText("🛑 SYSTEM HALTED DUE TO CRASH");
            debugLog.setText("⚠️ PASTE THIS TO AI ⚠️\n\n" + lastCrash);
            prefs.edit().remove("last_crash").apply();
            return; // Stops here so you can read the log
        }

        try {
            connectionsClient = Nearby.getConnectionsClient(this);
            if (hasPermissions()) {
                initAppLogic();
            } else {
                requestPermissions();
            }
        } catch (Exception e) {
            debugLog.setText("CRASH IN ONCREATE: " + e.getMessage());
        }
    }

    // ==========================================
    //      CRASH-PROOF PERMISSION HANDLING
    // ==========================================
    private boolean hasPermissions() {
        // FIXED: Using raw API number 33 instead of 'TIRAMISU' so older Android Studios don't panic
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, "android.permission.NEARBY_WIFI_DEVICES") == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= 31) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= 33) {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
                    "android.permission.NEARBY_WIFI_DEVICES", Manifest.permission.RECORD_AUDIO
            };
        } else if (Build.VERSION.SDK_INT >= 31) {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.RECORD_AUDIO
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
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

    private void initAppLogic() {
        try {
            setupAudio();
            setupListeners();
            log("AUTO: Initializing Self-Healing Network...");
            startAutonomousNetwork();
            startOptimizationLoop();
        } catch (Exception e) {
            log("CRASH CAUGHT IN INIT: " + e.getMessage());
        }
    }

    private void setupListeners() {
        if (btnDisconnect != null) {
            btnDisconnect.setOnClickListener(v -> {
                disconnectAll();
                startAutonomousNetwork();
            });
        }
        if (btnSend != null) {
            btnSend.setOnClickListener(v -> {
                String text = inputMessage.getText().toString();
                if (!TextUtils.isEmpty(text)) {
                    sendPayloadToAll(("M:" + USER_NICKNAME + ": " + text).getBytes(StandardCharsets.UTF_8));
                    inputMessage.setText("");
                    log("Me: " + text);
                }
            });
        }
        if (btnPtt != null) {
            btnPtt.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        btnPtt.setBackgroundColor(0xFF00FF00); btnPtt.setText("TRANSMITTING..."); startRecording(); return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        btnPtt.setBackgroundColor(0xFFFF0000); btnPtt.setText("HOLD TO TALK"); stopRecording(); return true;
                }
                return false;
            });
        }
    }

    private void setupAudio() {
        try {
            minBuffSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (minBuffSize <= 0 || minBuffSize == AudioRecord.ERROR || minBuffSize == AudioRecord.ERROR_BAD_VALUE) minBuffSize = 3840;

            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT, minBuffSize, AudioTrack.MODE_STREAM);
            if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                audioTrack.play();
            } else {
                log("WARNING: Speaker hardware is locked by a ghost process.");
            }
        } catch (Exception e) {
            log("CRASH CAUGHT (Audio): " + e.getMessage());
        }
    }

    private void startRecording() {
        if (connectedDevices.isEmpty() || ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        isRecording = true;

        int calcSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (calcSize == AudioRecord.ERROR || calcSize == AudioRecord.ERROR_BAD_VALUE) calcSize = 3840;

        // THE FIX: This variable MUST be 'final' so the background thread can use it
        final int safeBufferSize = calcSize;

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, safeBufferSize);
        audioRecord.startRecording();

        recordingThread = new Thread(() -> {
            int fastChunkSize = safeBufferSize / 2; // Uses the final variable
            byte[] buffer = new byte[fastChunkSize];
            while (isRecording) {
                int bytesRead = audioRecord.read(buffer, 0, fastChunkSize);
                if (bytesRead > 0) {
                    byte[] dataToSend = new byte[bytesRead + 1];
                    dataToSend[0] = 'A';
                    System.arraycopy(buffer, 0, dataToSend, 1, bytesRead);
                    sendPayloadToAll(dataToSend);
                }
            }
        });
        recordingThread.start();
    }
    private void stopRecording() {
        isRecording = false;
        if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); audioRecord = null; }
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

    private void startOptimizationLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isConnected) {
                    sendPayloadToAll(("S:" + getBatteryLevel()).getBytes(StandardCharsets.UTF_8));
                }
                handler.postDelayed(this, 10000);
            }
        }, 5000);
    }

    private void checkNetworkHealth(String senderId, int theirScore) {
        if (!isHost && connectedDevices.containsKey(senderId) && theirScore < 15) {
            log("⚠️ WARNING: Host Battery Critical. Searching for new Host...");
            disconnectAll();
            startAutonomousNetwork();
        }
    }

    private void startAutonomousNetwork() {
        if (isConnected) return;
        isHost = false;
        statusText.setText("Status: Auto-Scanning...");
        startDiscovery();
        handler.postDelayed(becomeHostRunnable, SCAN_DURATION);
    }

    private Runnable becomeHostRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isConnected) {
                log("AUTO: No network found. Taking Lead.");
                connectionsClient.stopDiscovery();
                startAdvertising();
            }
        }
    };

    private void startAdvertising() {
        try {
            AdvertisingOptions options = new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
            connectionsClient.startAdvertising(USER_NICKNAME, SERVICE_ID, connectionLifecycleCallback, options)
                    .addOnSuccessListener(unused -> { isHost = true; statusText.setText("Status: HOST (Leader)"); log("SYSTEM: Network Created."); })
                    .addOnFailureListener(e -> log("Adv Error: " + e.getMessage()));
        } catch (Exception e) {
            log("CRASH CAUGHT (Adv): " + e.getMessage());
        }
    }

    private void startDiscovery() {
        try {
            DiscoveryOptions options = new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
            connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options).addOnFailureListener(e -> log("Disc Error: " + e.getMessage()));
        } catch (Exception e) {
            log("CRASH CAUGHT (Disc): " + e.getMessage());
        }
    }

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            log("AUTO: Found Leader (" + info.getEndpointName() + "). Connecting...");
            handler.removeCallbacks(becomeHostRunnable);
            connectionsClient.stopDiscovery();
            connectionsClient.requestConnection(USER_NICKNAME, endpointId, connectionLifecycleCallback);
        }
        @Override
        public void onEndpointLost(@NonNull String endpointId) {}
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
                log(">>> Connected: " + connectedDevices.get(endpointId));
                statusText.setText("Connected: " + connectedDevices.size() + " devices");
                isConnected = true;
                handler.removeCallbacks(becomeHostRunnable);
            } else {
                connectedDevices.remove(endpointId);
                if (!isHost && connectedDevices.isEmpty()) startAutonomousNetwork();
            }
        }
        @Override
        public void onDisconnected(@NonNull String endpointId) {
            log("<<< Disconnected: " + connectedDevices.remove(endpointId));
            statusText.setText("Connected: " + connectedDevices.size() + " devices");
            if (!isHost && connectedDevices.isEmpty()) {
                isConnected = false;
                log("⚠️ ALERT: Host Lost! Reorganizing...");
                startAutonomousNetwork();
            }
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() == Payload.Type.BYTES) {
                byte[] receivedBytes = payload.asBytes();
                if (receivedBytes == null) return;
                char tag = (char) receivedBytes[0];

                if (tag == 'A') {
                    if (audioTrack != null) audioTrack.write(receivedBytes, 1, receivedBytes.length - 1);
                    if (isHost) relayPayload(endpointId, receivedBytes);
                } else if (tag == 'M') {
                    String message = new String(receivedBytes, StandardCharsets.UTF_8).substring(2);
                    log(message);
                    if (isHost) relayPayload(endpointId, receivedBytes);
                } else if (tag == 'S') {
                    try {
                        int score = Integer.parseInt(new String(receivedBytes, StandardCharsets.UTF_8).substring(2));
                        deviceScores.put(endpointId, score);
                        checkNetworkHealth(endpointId, score);
                    } catch (NumberFormatException e) {}
                }
            }
        }
        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {}
    };

    private void sendPayloadToAll(byte[] bytes) {
        if (connectedDevices.isEmpty()) return;
        Payload payload = Payload.fromBytes(bytes);
        List<String> deviceIds = new ArrayList<>(connectedDevices.keySet());
        connectionsClient.sendPayload(deviceIds, payload);
    }

    private void relayPayload(String senderId, byte[] bytes) {
        if (!isHost) return;
        List<String> otherDevices = new ArrayList<>();
        for (String id : connectedDevices.keySet()) { if (!id.equals(senderId)) otherDevices.add(id); }
        if (!otherDevices.isEmpty()) connectionsClient.sendPayload(otherDevices, Payload.fromBytes(bytes));
    }

    private void disconnectAll() {
        // THE FIX: Kill any rogue "Become Host" timers hiding in the background
        handler.removeCallbacks(becomeHostRunnable);

        connectionsClient.stopAllEndpoints();
        connectionsClient.stopAdvertising();
        connectionsClient.stopDiscovery();
        connectedDevices.clear();
        statusText.setText("Disconnected");
        isConnected = false;
        isHost = false;
    }

    private void log(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        runOnUiThread(() -> {
            debugLog.append("\n[" + timestamp + "] " + message);

            // THE FIX: Check if the screen has finished drawing before scrolling!
            if (debugLog.getLayout() != null) {
                final int scrollAmount = debugLog.getLayout().getLineTop(debugLog.getLineCount()) - debugLog.getHeight();
                if (scrollAmount > 0) debugLog.scrollTo(0, scrollAmount);
            }
        });
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