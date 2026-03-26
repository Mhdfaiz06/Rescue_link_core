package com.example.rescuelink.location;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

// --- CHANGED: Import the unified mesh tools ---
import com.example.rescuelink.MeshPacket;
import com.example.rescuelink.TransportBroker;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class LocationBroadcaster {
    private static final String TAG = "LocBroadcast";
    private static final long BROADCAST_INTERVAL = 5000; // 5 seconds for better tracking

    public interface StatusProvider {
        int getBatteryLevel();
        boolean isSosActive();
    }

    // --- CHANGED: Use TransportBroker instead of MeshBroker ---
    private final TransportBroker broker;
    private final String myMeshId;
    private final RescueLinkLocationManager locationManager;
    private StatusProvider statusProvider;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;

    // --- CHANGED: Constructor accepts TransportBroker ---
    public LocationBroadcaster(TransportBroker broker, String myMeshId, RescueLinkLocationManager locationManager) {
        this.broker = broker;
        this.myMeshId = myMeshId;
        this.locationManager = locationManager;
    }

    public void setStatusProvider(StatusProvider provider) {
        this.statusProvider = provider;
    }

    private final Runnable broadcastRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;

            if (locationManager.hasValidLocation()) {
                int battery = (statusProvider != null) ? statusProvider.getBatteryLevel() : 100;
                boolean sos = (statusProvider != null) && statusProvider.isSosActive();

                // 1. Construct the payload string
                // Format: L|MeshID|Lat|Lon|Accuracy|Battery|isSOS
                // (Using Locale.US to ensure decimals are dots, not commas!)
                String payloadStr = String.format(Locale.US, "L|%s|%.6f|%.6f|%.1f|%d|%b",
                        myMeshId,
                        locationManager.getLatitude(),
                        locationManager.getLongitude(),
                        locationManager.getAccuracy(),
                        battery,
                        sos
                );

                Log.d(TAG, "Broadcasting Location: " + payloadStr + " (Acc: " + locationManager.getAccuracy() + "m)");

                // 2. CHANGED: Wrap the string securely in a MeshPacket!
                MeshPacket locPacket = new MeshPacket(
                        'L',
                        myMeshId,
                        MeshPacket.BROADCAST_ID,
                        payloadStr.getBytes(StandardCharsets.UTF_8)
                );

                // 3. CHANGED: Send via the main unified radio pipe
                if (broker != null) {
                    broker.send(locPacket);
                }
            } else {
                Log.d(TAG, "Location not ready yet, skipping broadcast...");
            }

            // Loop again in 5 seconds
            handler.postDelayed(this, BROADCAST_INTERVAL);
        }
    };

    public void start() {
        if (isRunning) return;
        isRunning = true;
        handler.post(broadcastRunnable);
        Log.d(TAG, "Location Broadcasting Started");
    }

    public void stop() {
        isRunning = false;
        handler.removeCallbacks(broadcastRunnable);
        Log.d(TAG, "Location Broadcasting Stopped");
    }
}