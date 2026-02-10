package com.example.rescuelink;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.MotionEvent;
import android.view.View;
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

public class MainActivity extends AppCompatActivity {

    private static final String SERVICE_ID = "com.example.rescuelink.SERVICE";
    private final String USER_NICKNAME = Build.MANUFACTURER + " " + Build.MODEL;

    private ConnectionsClient connectionsClient;

    // UI Elements
    private TextView statusText, myIdText, debugLog;
    private Button btnBroadcast, btnScan, btnSend, btnDisconnect, btnPtt;
    private EditText inputMessage;

    // Data Structures
    private final Map<String, String> connectedDevices = new HashMap<>();

    // --- AUDIO CONFIGURATION ---
    // 16kHz Sample Rate is standard for VoIP (Voice over IP) - Good quality, lower data usage
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private boolean isRecording = false;
    private Thread recordingThread;
    private int minBuffSize;

    // Heartbeat & State
    private Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private Runnable heartbeatRunnable;
    private boolean isHost = false;
    private boolean isDiscovering = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind UI Elements
        statusText = findViewById(R.id.statusText);
        myIdText = findViewById(R.id.myIdText);
        debugLog = findViewById(R.id.debugLog);
        btnBroadcast = findViewById(R.id.btnBroadcast);
        btnScan = findViewById(R.id.btnScan);
        btnSend = findViewById(R.id.btnSend);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnPtt = findViewById(R.id.btnPtt); // The new "Hold to Talk" button
        inputMessage = findViewById(R.id.inputMessage);

        myIdText.setText(USER_NICKNAME);
        debugLog.setMovementMethod(new ScrollingMovementMethod());

        connectionsClient = Nearby.getConnectionsClient(this);
        checkPermissions();
        setupAudio(); // Initialize the "Always On" Speaker

        // --- BUTTON LISTENERS ---
        btnBroadcast.setOnClickListener(v -> startAdvertising());
        btnScan.setOnClickListener(v -> startDiscovery());

        btnDisconnect.setOnClickListener(v -> {
            disconnectAll();
        });

        // TEXT SEND LOGIC
        btnSend.setOnClickListener(v -> {
            String text = inputMessage.getText().toString();
            if (!TextUtils.isEmpty(text)) {
                // TAG "M" for Message: "M:Samsung: Hello World"
                String fullMessage = "M:" + USER_NICKNAME + ": " + text;
                sendPayloadToAll(fullMessage.getBytes(StandardCharsets.UTF_8));
                inputMessage.setText("");
                log("Me: " + text);
            }
        });

        // --- PUSH TO TALK LOGIC (HOLD DOWN) ---
        btnPtt.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    // USER PRESSED BUTTON -> Start Mic
                    btnPtt.setBackgroundColor(0xFF00FF00); // Turn Green
                    btnPtt.setText("TRANSMITTING...");
                    startRecording();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // USER RELEASED BUTTON -> Stop Mic
                    btnPtt.setBackgroundColor(0xFFFF0000); // Turn Red
                    btnPtt.setText("HOLD TO TALK");
                    stopRecording();
                    return true;
            }
            return false;
        });

        startHeartbeat();
    }

    // --- AUDIO SETUP (RUNS ON STARTUP) ---
    private void setupAudio() {
        minBuffSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

        // Setup Output (Speaker)
        audioTrack = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT,
                minBuffSize,
                AudioTrack.MODE_STREAM
        );
        // CRITICAL: We play immediately so the speaker is "Open" and ready for data
        audioTrack.play();
    }

    // --- MIC RECORDING LOGIC ---
    private void startRecording() {
        if (connectedDevices.isEmpty()) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("Error: No Mic Permission");
            return;
        }

        isRecording = true;
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, minBuffSize);
        audioRecord.startRecording();

        // Start Background Thread to Stream Data
        recordingThread = new Thread(() -> {
            byte[] buffer = new byte[minBuffSize];
            while (isRecording) {
                int bytesRead = audioRecord.read(buffer, 0, minBuffSize);
                if (bytesRead > 0) {
                    // Create a tagged packet: [ 'A', ...audio_bytes... ]
                    byte[] dataToSend = new byte[bytesRead + 1];
                    dataToSend[0] = 'A'; // 'A' tag for Audio
                    System.arraycopy(buffer, 0, dataToSend, 1, bytesRead);

                    // Send immediately
                    sendPayloadToAll(dataToSend);
                }
            }
        });
        recordingThread.start();
    }

    private void stopRecording() {
        isRecording = false;
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
    }

    // --- SEND HELPER ---
    private void sendPayloadToAll(byte[] bytes) {
        if (connectedDevices.isEmpty()) return;
        Payload payload = Payload.fromBytes(bytes);
        List<String> deviceIds = new ArrayList<>(connectedDevices.keySet());
        connectionsClient.sendPayload(deviceIds, payload);
    }

    // --- RELAY HELPER (Star Topology Forwarding) ---
    private void relayPayload(String senderId, byte[] bytes) {
        if (!isHost) return; // Only Host can relay
        List<String> otherDevices = new ArrayList<>();
        for (String id : connectedDevices.keySet()) {
            if (!id.equals(senderId)) otherDevices.add(id);
        }
        if (!otherDevices.isEmpty()) {
            connectionsClient.sendPayload(otherDevices, Payload.fromBytes(bytes));
        }
    }

    // --- PAYLOAD HANDLER (THE BRAIN) ---
    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() == Payload.Type.BYTES) {
                byte[] receivedBytes = payload.asBytes();
                if (receivedBytes == null || receivedBytes.length == 0) return;

                // CHECK TAG (First Byte)
                char tag = (char) receivedBytes[0];

                if (tag == 'A') {
                    // --- IT IS AUDIO ---
                    // 1. Play it immediately (Auto-Play)
                    if (audioTrack != null) {
                        // Skip the first byte (Tag) and play the rest
                        audioTrack.write(receivedBytes, 1, receivedBytes.length - 1);
                    }
                    // 2. Relay to others (if I am Host)
                    if (isHost) relayPayload(endpointId, receivedBytes);

                } else if (tag == 'M') {
                    // --- IT IS A TEXT MESSAGE ---
                    String message = new String(receivedBytes, StandardCharsets.UTF_8).substring(2); // Remove "M:"
                    log(message);
                    if (isHost) relayPayload(endpointId, receivedBytes);

                } else if (tag == 'H') {
                    // --- IT IS A HEARTBEAT ---
                    // Optional: log("❤️ " + connectedDevices.get(endpointId));
                }
            }
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {}
    };

    // --- STANDARD CONNECTION CODE ---
    private void startAdvertising() {
        AdvertisingOptions options = new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
        connectionsClient.startAdvertising(USER_NICKNAME, SERVICE_ID, connectionLifecycleCallback, options)
                .addOnSuccessListener(unused -> {
                    isHost = true;
                    statusText.setText("Status: Host (Broadcasting)");
                    log("SYSTEM: Started Hosting...");
                })
                .addOnFailureListener(e -> log("Advertise Error: " + e.getMessage()));
    }

    private void startDiscovery() {
        DiscoveryOptions options = new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
                .addOnSuccessListener(unused -> {
                    isDiscovering = true;
                    statusText.setText("Status: Scanning...");
                    log("SYSTEM: Started Scanning...");
                })
                .addOnFailureListener(e -> log("Scan Error: " + e.getMessage()));
    }

    private void disconnectAll() {
        connectionsClient.stopAllEndpoints();
        connectionsClient.stopAdvertising();
        connectionsClient.stopDiscovery();
        connectedDevices.clear();
        statusText.setText("Status: Disconnected");
        isHost = false;
        isDiscovering = false;
        log("--- DISCONNECTED ---");
    }

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            log("Found: " + info.getEndpointName());
            if (isDiscovering) {
                connectionsClient.stopDiscovery();
                isDiscovering = false;
            }
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
            } else {
                connectedDevices.remove(endpointId);
            }
        }
        @Override
        public void onDisconnected(@NonNull String endpointId) {
            log("<<< Disconnected: " + connectedDevices.remove(endpointId));
        }
    };

    private void startHeartbeat() {
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (!connectedDevices.isEmpty()) {
                    String hb = "H:Heartbeat";
                    sendPayloadToAll(hb.getBytes(StandardCharsets.UTF_8));
                }
                heartbeatHandler.postDelayed(this, 3000);
            }
        };
        heartbeatHandler.post(heartbeatRunnable);
    }

    private void log(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        runOnUiThread(() -> {
            debugLog.append("\n[" + timestamp + "] " + message);
            final int scrollAmount = debugLog.getLayout().getLineTop(debugLog.getLineCount()) - debugLog.getHeight();
            if (scrollAmount > 0) debugLog.scrollTo(0, scrollAmount);
        });
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.RECORD_AUDIO
            }, 1);
        }
    }
}