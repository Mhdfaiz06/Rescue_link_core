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

    // --- Core State Tracking ---
    private final MainActivity activity;
    private final ConnectionsClient connectionsClient;
    private PacketListener listener;

    private boolean isHost = false;
    private boolean isConnected = false;
    private boolean isDiscovering = false;
    private boolean isAdvertising = false;

    // --- Backoff Constants ---
    private static final long BASE_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 16000;
    private static final int MAX_RETRY_COUNT = 5;
    private int retryCount = 0;

    // --- Consolidated Thread-Safe Collections ---
    // This is the master list of physical links (Phone-to-Phone)
    private final Map<String, String> connectedDevices = new ConcurrentHashMap<>();

    // This is the software map for the mesh (MeshID-to-PhysicalID)
    private final Map<String, String> routingTable = new ConcurrentHashMap<>();

    // Prevents duplicate connection requests
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

        if (hostElectionRunnable != null) {
            handler.removeCallbacks(hostElectionRunnable);
        }

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

    // ==========================================
    //      ADVERTISING & DISCOVERY (RANGE FIX)
    // ==========================================

    public void startDiscovery() {
        if (isDiscovering) return;
        try {
            // P2P_STAR forces WiFi Direct (70m range)
            DiscoveryOptions options = new DiscoveryOptions.Builder()
                    .setStrategy(Strategy.P2P_STAR)
                    .build();

            connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
                    .addOnSuccessListener(unused -> {
                        isDiscovering = true;
                        Log.d(TAG, "Discovery started — P2P_STAR (WiFi Direct)");
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "P2P_STAR discovery failed, falling back: " + e.getMessage());
                        startDiscoveryFallback();
                    });
        } catch (Exception e) {
            startDiscoveryFallback();
        }
    }

    private void startDiscoveryFallback() {
        if (isDiscovering) return;
        try {
            DiscoveryOptions options = new DiscoveryOptions.Builder()
                    .setStrategy(Strategy.P2P_CLUSTER).build();
            connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
                    .addOnSuccessListener(unused -> isDiscovering = true)
                    .addOnFailureListener(e -> scheduleRetry());
        } catch (Exception e) {
            scheduleRetry();
        }
    }

    public void startAdvertising() {
        if (isAdvertising) return;
        try {
            AdvertisingOptions options = new AdvertisingOptions.Builder()
                    .setStrategy(Strategy.P2P_STAR)
                    .build();

            connectionsClient.startAdvertising(
                            activity.USER_NICKNAME, SERVICE_ID,
                            connectionLifecycleCallback, options)
                    .addOnSuccessListener(unused -> {
                        isHost = true;
                        isAdvertising = true;
                        activity.setStatusText("Status: Mesh Host (Wi-Fi)");
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "P2P_STAR advertising failed, falling back: " + e.getMessage());
                        isAdvertising = false;
                        startAdvertisingFallback();
                    });
        } catch (Exception e) {
            startAdvertisingFallback();
        }
    }

    private void startAdvertisingFallback() {
        if (isAdvertising) return;
        try {
            AdvertisingOptions options = new AdvertisingOptions.Builder()
                    .setStrategy(Strategy.P2P_CLUSTER).build();
            connectionsClient.startAdvertising(
                            activity.USER_NICKNAME, SERVICE_ID,
                            connectionLifecycleCallback, options)
                    .addOnSuccessListener(unused -> {
                        isHost = true;
                        isAdvertising = true;
                        activity.setStatusText("Status: Mesh Host (Bluetooth)");
                    })
                    .addOnFailureListener(e -> scheduleRetry());
        } catch (Exception e) {
            scheduleRetry();
        }
    }

    private void stopDiscovery() {
        if (!isDiscovering) return;
        try { connectionsClient.stopDiscovery(); } catch (Exception ignored) {}
        isDiscovering = false;
    }

    private void stopAdvertising() {
        if (!isAdvertising) return;
        try { connectionsClient.stopAdvertising(); } catch (Exception ignored) {}
        isAdvertising = false;
    }

    // ==========================================
    //      CONNECTION CALLBACKS (AUTO-HEAL FIX)
    // ==========================================

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            activity.log("Found Node: " + info.getEndpointName());
            if (pendingConnections.contains(endpointId) || connectedDevices.containsKey(endpointId)) return;

            if (hostElectionRunnable != null) handler.removeCallbacks(hostElectionRunnable);

            stopDiscovery();
            pendingConnections.add(endpointId);
            connectionsClient.requestConnection(activity.USER_NICKNAME, endpointId, connectionLifecycleCallback)
                    .addOnFailureListener(e -> {
                        pendingConnections.remove(endpointId);
                        scheduleRetry();
                    });
        }
        @Override public void onEndpointLost(@NonNull String endpointId) { pendingConnections.remove(endpointId); }
    };

    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            connectedDevices.put(endpointId, info.getEndpointName());
            try {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
                        .addOnFailureListener(e -> connectedDevices.remove(endpointId));
            } catch (Exception e) { connectedDevices.remove(endpointId); }
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            pendingConnections.remove(endpointId);
            if (result.getStatus().isSuccess()) {
                isConnected = true;
                retryCount = 0;
                activity.log("Linked (Nearby): " + connectedDevices.get(endpointId));

                // Keep the chain alive
                if (!isAdvertising) startAdvertising();
            } else {
                connectedDevices.remove(endpointId);
                if (!isConnected) scheduleRetry();
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            Log.w(TAG, "❌ CONNECTION LOST: " + endpointId);

            // 1. Unified Cleanup: Remove from both "buckets"
            connectedDevices.remove(endpointId);
            routingTable.values().remove(endpointId); // Remove any routes pointing to this ID

            if (activity != null) activity.log("Lost link to " + endpointId + ". Auto-healing...");

            // 2. Auto-Heal Trigger
            if (connectedDevices.isEmpty()) {
                isConnected = false;
                isHost = false;

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Log.d(TAG, "Initiating Auto-Heal Scan...");
                    connectionsClient.stopAllEndpoints();
                    isAdvertising = false;
                    isDiscovering = false;
                    startAutonomousNetwork();
                }, 1500);
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
        connectionsClient.sendPayload(new ArrayList<>(connectedDevices.keySet()), Payload.fromBytes(data));
    }

    public void sendToAllExcept(String excludedId, byte[] data) {
        if (data == null) return;
        List<String> targets = new ArrayList<>();
        for (String id : connectedDevices.keySet()) {
            if (!id.equals(excludedId)) targets.add(id);
        }
        if (!targets.isEmpty()) {
            connectionsClient.sendPayload(targets, Payload.fromBytes(data));
        }
    }

    public void sendTo(String endpointId, byte[] data) {
        if (data == null || !connectedDevices.containsKey(endpointId)) return;
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(data));
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

    private void scheduleRetry() {
        if (retryCount >= MAX_RETRY_COUNT) {
            retryCount = 0;
            handler.postDelayed(this::fullReset, 3000);
            return;
        }
        long delay = Math.min(BASE_BACKOFF_MS * (1L << retryCount), MAX_BACKOFF_MS);
        retryCount++;
        activity.setStatusText("Status: Reconnecting...");
        handler.postDelayed(this::startScanPhase, delay);
    }

    public void fullReset() {
        disconnectAll();
        handler.postDelayed(this::startScanPhase, 2000);
    }

    public void disconnectAll() {
        if (hostElectionRunnable != null) handler.removeCallbacks(hostElectionRunnable);
        try { connectionsClient.stopAllEndpoints(); } catch (Exception ignored) {}
        stopAdvertising();
        stopDiscovery();

        connectedDevices.clear();
        routingTable.clear();
        pendingConnections.clear();

        isConnected = false;
        isHost = false;
    }

    public boolean isConnected() { return isConnected; }
    public boolean isHost() { return isHost; }
    public int getPeerCount() { return connectedDevices.size(); }
}