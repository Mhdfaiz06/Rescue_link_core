package com.example.rescuelink;

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
import java.util.concurrent.ConcurrentHashMap;

public class NearbyMeshManager {

    public interface PacketListener {
        void onPacketReceived(byte[] data, String endpointId);
    }

    private static final String SERVICE_ID = "com.example.rescuelink.SERVICE";
    private final MainActivity activity;
    private final ConnectionsClient connectionsClient;

    private PacketListener listener;
    private boolean isHost = false;
    private boolean isConnected = false;

    private final Map<String, String> connectedDevices = new ConcurrentHashMap<>();
    private final Map<String, String> routingTable = new ConcurrentHashMap<>();

    public NearbyMeshManager(MainActivity activity) {
        this.activity = activity;
        this.connectionsClient = Nearby.getConnectionsClient(activity);
    }

    public void setPacketListener(PacketListener listener) {
        this.listener = listener;
    }

    public void startAutonomousNetwork() {
        if (isConnected) return;
        isHost = false;
        activity.setStatusText("Status: Scanning Mesh...");

        DiscoveryOptions options = new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options);

        // Auto-elect as host if nobody found
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!isConnected) {
                activity.log("AUTO: Region empty. Electing self as Host.");
                connectionsClient.stopDiscovery();
                startAdvertising();
            }
        }, 6000);
    }

    private void startAdvertising() {
        AdvertisingOptions options = new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build();
        connectionsClient.startAdvertising(activity.USER_NICKNAME, SERVICE_ID, connectionLifecycleCallback, options)
                .addOnSuccessListener(unused -> {
                    isHost = true;
                    activity.setStatusText("Status: Mesh Host");
                });
    }

    // --- Connections Callbacks ---

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            activity.log("Found Leader: " + info.getEndpointName());
            connectionsClient.stopDiscovery();
            connectionsClient.requestConnection(activity.USER_NICKNAME, endpointId, connectionLifecycleCallback);
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
                activity.log(">>> Linked (Nearby): " + connectedDevices.get(endpointId));
            } else {
                connectedDevices.remove(endpointId);
            }
        }
        @Override
        public void onDisconnected(@NonNull String endpointId) {
            connectedDevices.remove(endpointId);
            routingTable.values().remove(endpointId);
            if (connectedDevices.isEmpty()) {
                isConnected = false;
                startAutonomousNetwork();
            }
        }
    };

    // --- Data Transmission ---

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() == Payload.Type.BYTES && listener != null) {
                listener.onPacketReceived(payload.asBytes(), endpointId);
            }
        }
        @Override public void onPayloadTransferUpdate(@NonNull String id, @NonNull PayloadTransferUpdate update) {}
    };

    public void sendToAll(byte[] data) {
        if (!connectedDevices.isEmpty()) {
            connectionsClient.sendPayload(new ArrayList<>(connectedDevices.keySet()), Payload.fromBytes(data));
        }
    }

    public void sendToAllExcept(String excludedId, byte[] data) {
        List<String> targets = new ArrayList<>();
        for (String id : connectedDevices.keySet()) {
            if (!id.equals(excludedId)) targets.add(id);
        }
        if (!targets.isEmpty()) {
            connectionsClient.sendPayload(targets, Payload.fromBytes(data));
        }
    }

    public void sendTo(String endpointId, byte[] data) {
        if (connectedDevices.containsKey(endpointId)) {
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(data));
        }
    }

    // --- Routing & State ---

    public void updateRoutingTable(String originMeshId, String viaEndpointId) {
        routingTable.put(originMeshId, viaEndpointId);
    }

    public String getNextHop(String targetMeshId) {
        return routingTable.get(targetMeshId);
    }

    public void disconnectAll() {
        connectionsClient.stopAllEndpoints();
        connectionsClient.stopAdvertising();
        connectionsClient.stopDiscovery();
        connectedDevices.clear();
        routingTable.clear();
        isConnected = false;
        isHost = false;
    }

    public boolean isConnected() { return isConnected; }
    public boolean isHost() { return isHost; }
    public int getPeerCount() { return connectedDevices.size(); }
}