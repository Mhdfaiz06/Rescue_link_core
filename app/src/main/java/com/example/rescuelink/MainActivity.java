package com.example.rescuelink;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
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
    private Button btnBroadcast, btnScan, btnSend, btnDisconnect;
    private EditText inputMessage;

    // Data Structures
    private final Map<String, String> connectedDevices = new HashMap<>();

    // State Flags
    private boolean isHost = false;
    private boolean isDiscovering = false;

    // Heartbeat
    private Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private Runnable heartbeatRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        myIdText = findViewById(R.id.myIdText);
        debugLog = findViewById(R.id.debugLog);
        btnBroadcast = findViewById(R.id.btnBroadcast);
        btnScan = findViewById(R.id.btnScan);
        btnSend = findViewById(R.id.btnSend);
        btnDisconnect = findViewById(R.id.btnDisconnect); // NEW BUTTON
        inputMessage = findViewById(R.id.inputMessage);

        myIdText.setText(USER_NICKNAME);
        debugLog.setMovementMethod(new ScrollingMovementMethod());

        connectionsClient = Nearby.getConnectionsClient(this);
        checkPermissions();

        btnBroadcast.setOnClickListener(v -> startAdvertising());
        btnScan.setOnClickListener(v -> startDiscovery());

        // NEW: DISCONNECT LOGIC
        btnDisconnect.setOnClickListener(v -> {
            connectionsClient.stopAllEndpoints();
            connectionsClient.stopAdvertising();
            connectionsClient.stopDiscovery();
            connectedDevices.clear();
            statusText.setText("Status: Disconnected");
            isHost = false;
            isDiscovering = false;
            log("--- DISCONNECTED ---");
        });

        btnSend.setOnClickListener(v -> {
            String text = inputMessage.getText().toString();
            if (!TextUtils.isEmpty(text)) {
                // We verify if we are Host or Client inside the payload logic
                // For now, just send to everyone we are connected to
                String fullMessage = "MSG:" + USER_NICKNAME + ": " + text;
                sendPayloadToAll(fullMessage);
                inputMessage.setText("");
                log("Me: " + text);
            }
        });

        // Heartbeat Loop (Runs every 3 seconds)
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (!connectedDevices.isEmpty()) {
                    // Send Heartbeat
                    sendPayloadToAll("HEARTBEAT");
                }
                heartbeatHandler.postDelayed(this, 3000);
            }
        };
        heartbeatHandler.post(heartbeatRunnable);
    }

    // --- SENDING HELPER ---
    private void sendPayloadToAll(String message) {
        if (connectedDevices.isEmpty()) return;
        Payload payload = Payload.fromBytes(message.getBytes());
        List<String> deviceIds = new ArrayList<>(connectedDevices.keySet());
        connectionsClient.sendPayload(deviceIds, payload);
    }

    // --- REPEATER HELPER (The Magic Part) ---
    // If I am Host, I forward messages to everyone EXCEPT the person who sent it
    private void relayMessage(String senderId, String message) {
        if (!isHost) return; // Only Host can relay

        List<String> otherDevices = new ArrayList<>();
        for (String id : connectedDevices.keySet()) {
            if (!id.equals(senderId)) { // Don't echo back to sender
                otherDevices.add(id);
            }
        }
        if (!otherDevices.isEmpty()) {
            Payload payload = Payload.fromBytes(message.getBytes());
            connectionsClient.sendPayload(otherDevices, payload);
            // log("System: Relayed message to " + otherDevices.size() + " others.");
        }
    }

    private void log(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        runOnUiThread(() -> {
            debugLog.append("\n[" + timestamp + "] " + message);
            final int scrollAmount = debugLog.getLayout().getLineTop(debugLog.getLineCount()) - debugLog.getHeight();
            if (scrollAmount > 0) debugLog.scrollTo(0, scrollAmount);
        });
    }

    private void startAdvertising() {
        AdvertisingOptions options = new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
        connectionsClient.startAdvertising(USER_NICKNAME, SERVICE_ID, connectionLifecycleCallback, options)
                .addOnSuccessListener(unused -> {
                    isHost = true; // MARK MYSELF AS HOST
                    statusText.setText("Status: Host (Broadcasting)");
                    log("SYSTEM: Started Hosting...");
                })
                .addOnFailureListener(e -> log("Error: " + e.getMessage()));
    }

    private void startDiscovery() {
        DiscoveryOptions options = new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
                .addOnSuccessListener(unused -> {
                    isDiscovering = true;
                    statusText.setText("Status: Scanning...");
                    log("SYSTEM: Started Scanning...");
                })
                .addOnFailureListener(e -> log("Error: " + e.getMessage()));
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
                String name = connectedDevices.get(endpointId);
                log(">>> Connected to: " + name);
                statusText.setText("Connected: " + connectedDevices.size() + " devices");
            } else {
                connectedDevices.remove(endpointId);
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            String name = connectedDevices.remove(endpointId);
            log("<<< Disconnected: " + name);
            statusText.setText("Connected: " + connectedDevices.size() + " devices");
        }
    };

    // --- PAYLOAD HANDLER (Updated for Relay) ---
    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() == Payload.Type.BYTES) {
                String rawMessage = new String(payload.asBytes());
                String senderName = connectedDevices.get(endpointId);

                if (rawMessage.equals("HEARTBEAT")) {
                    log("ALIVE " + senderName);
                    // Optional: If you want everyone to know A is alive, uncomment line below
                    // relayMessage(endpointId, "MSG:SYSTEM: " + senderName + " is Alive");
                }
                else if (rawMessage.startsWith("MSG:")) {
                    // Extract the clean message
                    // Format is "MSG:Samsung: Hello"
                    String cleanMsg = rawMessage.substring(4);
                    log(cleanMsg); // Show on my screen

                    // IMPORTANT: If I am Host, I must pass this to everyone else
                    if (isHost) {
                        relayMessage(endpointId, rawMessage);
                    }
                }
            }
        }
        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {}
    };

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