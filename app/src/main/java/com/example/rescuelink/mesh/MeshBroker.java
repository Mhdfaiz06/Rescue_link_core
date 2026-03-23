package com.example.rescuelink.mesh;

import android.util.Log;
import com.example.rescuelink.NearbyMeshManager; // Updated class name
import java.nio.charset.StandardCharsets;

public class MeshBroker {
    private static final String TAG = "MeshBroker";

    // Updated to match your actual class name
    private final NearbyMeshManager nearby;

    public MeshBroker(NearbyMeshManager nearby) {
        this.nearby = nearby;
    }

    /**
     * This method is called by LocationBroadcaster.
     * It converts the GPS string into bytes and sends it through your mesh.
     */
    public void broadcast(String data) {
        if (data == null || data.isEmpty()) return;

        Log.d(TAG, "MeshBroker: Sending to all peers -> " + data);

        if (nearby != null && nearby.isConnected()) {
            // 1. Convert String to byte array (Your NearbyMeshManager needs bytes)
            byte[] payload = data.getBytes(StandardCharsets.UTF_8);

            // 2. Call your actual method: sendToAll
            nearby.sendToAll(payload);
        }
    }

    public void shutdown() {
        Log.d(TAG, "MeshBroker: Shutting down.");
    }
}