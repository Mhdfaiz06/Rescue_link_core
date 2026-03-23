package com.example.rescuelink.location;

import com.example.rescuelink.mesh.MeshBroker;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class LocationBroadcaster {
    private static final String TAG = "LocBroadcast";
    private static final long BROADCAST_INTERVAL = 10000; // Send GPS every 10 seconds

    private final MeshBroker broker;
    private final String myMeshId;
    private final RescueLinkLocationManager locationManager;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;

    public LocationBroadcaster(MeshBroker broker, String myMeshId, RescueLinkLocationManager locationManager) {
        this.broker = broker;
        this.myMeshId = myMeshId;
        this.locationManager = locationManager;
    }

    private final Runnable broadcastRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;

            if (locationManager.hasValidLocation()) {
                // Construct the 'L' (Location) packet:
                // Format: L|MeshID|Lat|Lon|Accuracy|Battery|isSOS
                String payload = String.format("L|%s|%.6f|%.6f|%.1f|%d|%b",
                        myMeshId,
                        locationManager.getLatitude(),
                        locationManager.getLongitude(),
                        locationManager.getAccuracy(),
                        85, // Placeholder for battery level
                        false // Placeholder for SOS status
                );

                Log.d(TAG, "Broadcasting Location: " + payload);
                broker.broadcast(payload);
            }

            handler.postDelayed(this, BROADCAST_INTERVAL);
        }
    };

    public void start() {
        if (isRunning) return;
        isRunning = true;
        handler.post(broadcastRunnable);
        Log.d(TAG, "Location Broadcasting Started");
    }

    // --- THIS IS THE METHOD MAINACTIVITY WAS LOOKING FOR ---
    public void stop() {
        isRunning = false;
        handler.removeCallbacks(broadcastRunnable);
        Log.d(TAG, "Location Broadcasting Stopped");
    }
}