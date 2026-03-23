package com.example.rescuelink.location;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.rescuelink.MeshPacket;
import com.example.rescuelink.TransportBroker;

import java.nio.ByteBuffer;

public class LocationBroadcaster {

    private static final String TAG = "LocationBcast";
    private static final long BROADCAST_INTERVAL_MS = 15000;

    private final TransportBroker broker;
    private final String myMeshId;
    private final RescueLinkLocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;

    public LocationBroadcaster(TransportBroker broker, String myMeshId, RescueLinkLocationManager locationManager) {
        this.broker          = broker;
        this.myMeshId        = myMeshId;
        this.locationManager = locationManager;
    }

    public void start() {
        isRunning = true;
        handler.postDelayed(broadcastRunnable, BROADCAST_INTERVAL_MS);
    }

    public void stop() {
        isRunning = false;
        handler.removeCallbacks(broadcastRunnable);
    }

    private final Runnable broadcastRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            broadcastLocation();
            handler.postDelayed(this, BROADCAST_INTERVAL_MS);
        }
    };

    public void broadcastLocation() {
        if (!locationManager.hasValidLocation()) return;

        byte[] payload = buildLocationPayload(
                locationManager.getLatitude(),
                locationManager.getLongitude(),
                locationManager.getAccuracy(),
                locationManager.isFresh()
        );

        MeshPacket packet = new MeshPacket('L', myMeshId, MeshPacket.BROADCAST_ID, payload);
        broker.send(packet);
    }

    private byte[] buildLocationPayload(double lat, double lon, float accuracy, boolean hasDirectGps) {
        ByteBuffer bb = ByteBuffer.allocate(17);
        bb.putDouble(lat);
        bb.putDouble(lon);
        bb.putShort((short)(accuracy * 10));
        bb.put(hasDirectGps ? (byte)0x01 : (byte)0x00);
        return bb.array();
    }

    public static LocationPayload parse(byte[] payload) {
        if (payload == null || payload.length < 17) return null;
        ByteBuffer bb = ByteBuffer.wrap(payload);
        LocationPayload lp = new LocationPayload();
        lp.latitude     = bb.getDouble();
        lp.longitude    = bb.getDouble();
        lp.accuracy     = (bb.getShort() & 0xFFFF) / 10.0f;
        lp.hasDirectGps = (bb.get() & 0x01) == 1;
        return lp;
    }

    public static class LocationPayload {
        public double latitude;
        public double longitude;
        public float  accuracy;
        public boolean hasDirectGps;
    }
}