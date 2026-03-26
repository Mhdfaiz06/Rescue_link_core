package com.example.rescuelink;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class NearbyMeshManager {

    public interface PacketListener {
        void onPacketReceived(byte[] data, String endpointId);
    }

    private static final String TAG = "NearbyMesh";
    private static final String SERVICE_ID = "com.example.rescuelink.SERVICE";

    // --- Backoff Constants for Connection Retry ---
    private static final long BASE_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 16000;
    private static final int MAX_RETRY_COUNT = 5;

    private final MainActivity activity;
    private final ConnectionsClient connectionsClient;

    private PacketListener listener;
    private boolean isHost = false;
    private boolean isConnected = false;

    // --- State Tracking Flags ---
    private boolean isDiscovering = false;
    private boolean isAdvertising = false;
    private int retryCount = 0;

    // --- Thread-Safe Collections ---
    private final Map<String, String> connectedDevices = new ConcurrentHashMap<>();
    private final Map<String, String> routingTable = new ConcurrentHashMap<>();
    // Tracks pending outbound requests to avoid duplicate requests (The Race Condition Fix)
    private final Set<String> pendingConnections = ConcurrentHashMap.newKeySet();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable hostElectionRunnable;

    public NearbyMeshManager(MainActivity activity) {
        this.activity = activity;
        this.connectionsClient = Nearby.getConnectionsClient(activity);
    }

    public void setPacketListener(PacketListener listener) {
        this.listener = listener;
    }

    // ==========================================
    //      NETWORK START & TIMERS
    // ==========================================

    public void startAutonomousNetwork() {
        if (isConnected) return;
        retryCount = 0;
        startScanPhase();
    }

    private void startScanPhase() {
        isHost = false;
        activity.setStatusText("Status: Scanning Mesh...");
        startDiscovery();

        // Cancel any existing election timer
        if (hostElectionRunnable != null) {
            handler.removeCallbacks(hostElectionRunnable);
        }

        // TIE-BREAKING: Randomized wait before becoming host
        // Higher mesh ID waits slightly longer, deterministic tie-breaking.
        long baseWait = 4000;
        long jitter = new Random().nextInt(3000);
        long meshBias = (activity.myMeshId != null) ? (activity.myMeshId.hashCode() & 0x7FFFFFFF) % 2000 : 0;
        long totalWait = baseWait + jitter + meshBias;

        hostElectionRunnable = () -> {
            if (!isConnected) {
                activity.log("AUTO: Region empty. Electing self as Host after " + totalWait + "ms");
                stopDiscovery();
                startAdvertising();
            }
        };
        handler.postDelayed(hostElectionRunnable, totalWait);
    }

    private void startDiscovery() {
        if (isDiscovering) return;
        try {
            DiscoveryOptions options = new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
            connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
                    .addOnSuccessListener(unused -> isDiscovering = true)
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Discovery failed to start: " + e.getMessage());
                        scheduleRetry();
                    });
        } catch (Exception e) {
            Log.e(TAG, "startDiscovery exception: " + e.getMessage());
        }
    }

    private void stopDiscovery() {
        if (!isDiscovering) return;
        try {
            connectionsClient.stopDiscovery();
        } catch (Exception e) {
            Log.w(TAG, "stopDiscovery exception: " + e.getMessage());
        }
        isDiscovering = false;
    }

    private void startAdvertising() {
        if (isAdvertising) return;
        try {
            AdvertisingOptions options = new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
            connectionsClient.startAdvertising(activity.USER_NICKNAME, SERVICE_ID, connectionLifecycleCallback, options)
                    .addOnSuccessListener(unused -> {
                        isHost = true;
                        isAdvertising = true;
                        activity.setStatusText("Status: Mesh Host");
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Advertising failed to start: " + e.getMessage());
                        isAdvertising = false;
                        scheduleRetry();
                    });
        } catch (Exception e) {
            Log.e(TAG, "startAdvertising exception: " + e.getMessage());
        }
    }

    // ==========================================
    //      RETRY LOGIC (EXPONENTIAL BACKOFF)
    // ==========================================

    private void scheduleRetry() {
        if (retryCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "Max retries reached — resetting completely");
            retryCount = 0;
            handler.postDelayed(this::fullReset, 3000);
            return;
        }
        long delay = Math.min(BASE_BACKOFF_MS * (1L << retryCount), MAX_BACKOFF_MS);
        retryCount++;
        activity.setStatusText("Status: Reconnecting...");
        handler.postDelayed(this::startScanPhase, delay);
    }

    private void fullReset() {
        disconnectAll();
        handler.postDelayed(this::startScanPhase, 2000);
    }

    // ==========================================
    //      CONNECTION CALLBACKS
    // ==========================================

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            activity.log("Found Node: " + info.getEndpointName());

            // PREVENT RACE CONDITION: Avoid duplicate connection requests to the same endpoint
            if (pendingConnections.contains(endpointId) || connectedDevices.containsKey(endpointId)) {
                return;
            }

            // Cancel host election since we found someone
            if (hostElectionRunnable != null) {
                handler.removeCallbacks(hostElectionRunnable);
            }

            stopDiscovery();
            pendingConnections.add(endpointId);

            connectionsClient.requestConnection(activity.USER_NICKNAME, endpointId, connectionLifecycleCallback)
                    .addOnFailureListener(e -> {
                        pendingConnections.remove(endpointId);
                        Log.w(TAG, "requestConnection failed: " + e.getMessage());
                        scheduleRetry(); // Back off instead of instant loop
                    });
        }
        @Override
        public void onEndpointLost(@NonNull String endpointId) {
            pendingConnections.remove(endpointId);
        }
    };

    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            // Both phones always accept. The Nearby API handles the internal de-duplication.
            connectedDevices.put(endpointId, info.getEndpointName());
            try {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
                        .addOnFailureListener(e -> connectedDevices.remove(endpointId));
            } catch (Exception e) {
                connectedDevices.remove(endpointId);
            }
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            pendingConnections.remove(endpointId);
            if (result.getStatus().isSuccess()) {
                isConnected = true;
                retryCount = 0; // Reset backoff on success
                activity.log(">>> Linked (Nearby): " + connectedDevices.get(endpointId));

                // CRUCIAL FOR MESH: Once connected, start advertising so others can chain onto you
                if (!isAdvertising) {
                    startAdvertising();
                }
            } else {
                connectedDevices.remove(endpointId);
                // Status 8002 = Already Connected, 8003 = Rejected. Normal in simultaneous scenarios.
                if (!isConnected) {
                    scheduleRetry();
                }
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            connectedDevices.remove(endpointId);
            routingTable.values().remove(endpointId);
            if (connectedDevices.isEmpty()) {
                isConnected = false;
                activity.log("Mesh link lost — re-scanning");
                // Small delay before rescanning to avoid thrashing
                handler.postDelayed(() -> startScanPhase(), 1500);
            }
        }
    };

    // ==========================================
    //      DATA TRANSMISSION
    // ==========================================

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() == Payload.Type.BYTES && listener != null) {
                byte[] data = payload.asBytes();
                if (data != null && data.length > 0) {
                    listener.onPacketReceived(data, endpointId);
                }
            }
        }
        @Override public void onPayloadTransferUpdate(@NonNull String id, @NonNull PayloadTransferUpdate update) {}
    };

    public void sendToAll(byte[] data) {
        if (data == null || connectedDevices.isEmpty()) return;
        try {
            connectionsClient.sendPayload(new ArrayList<>(connectedDevices.keySet()), Payload.fromBytes(data));
        } catch (Exception e) {
            Log.w(TAG, "sendToAll failed: " + e.getMessage());
        }
    }

    public void sendToAllExcept(String excludedId, byte[] data) {
        if (data == null) return;
        List<String> targets = new ArrayList<>();
        for (String id : connectedDevices.keySet()) {
            if (!id.equals(excludedId)) targets.add(id);
        }
        if (!targets.isEmpty()) {
            try {
                connectionsClient.sendPayload(targets, Payload.fromBytes(data));
            } catch (Exception e) {
                Log.w(TAG, "sendToAllExcept failed: " + e.getMessage());
            }
        }
    }

    public void sendTo(String endpointId, byte[] data) {
        if (data == null || !connectedDevices.containsKey(endpointId)) return;
        try {
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(data));
        } catch (Exception e) {
            Log.w(TAG, "sendTo failed: " + e.getMessage());
        }
    }

    // ==========================================
    //      ROUTING & STATE
    // ==========================================

    public void updateRoutingTable(String originMeshId, String viaEndpointId) {
        if (originMeshId != null && viaEndpointId != null) {
            routingTable.put(originMeshId, viaEndpointId);
        }
    }

    public String getNextHop(String targetMeshId) {
        return routingTable.get(targetMeshId);
    }

    public void disconnectAll() {
        if (hostElectionRunnable != null) {
            handler.removeCallbacks(hostElectionRunnable);
        }
        try { connectionsClient.stopAllEndpoints(); } catch (Exception ignored) {}
        try { connectionsClient.stopAdvertising(); } catch (Exception ignored) {}
        try { connectionsClient.stopDiscovery(); } catch (Exception ignored) {}

        connectedDevices.clear();
        routingTable.clear();
        pendingConnections.clear();

        isConnected = false;
        isHost = false;
        isDiscovering = false;
        isAdvertising = false;
    }

    public boolean isConnected() { return isConnected; }
    public boolean isHost() { return isHost; }
    public int getPeerCount() { return connectedDevices.size(); }
}