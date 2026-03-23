package com.example.rescuelink.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

public class RescueLinkLocationManager {

    public interface LocationListener {
        void onLocationUpdated(double latitude, double longitude, float accuracy);
        void onLocationUnavailable();
    }

    private static final String TAG = "RLLocation";
    private static final long UPDATE_INTERVAL_MS     = 5000;
    private static final long FASTEST_INTERVAL_MS    = 2000;
    private static final float MIN_DISPLACEMENT_M    = 5.0f;

    private final Context context;
    private final FusedLocationProviderClient fusedClient;
    private LocationCallback locationCallback;
    private LocationListener listener;

    private double lastLatitude  = 0.0;
    private double lastLongitude = 0.0;
    private float  lastAccuracy  = 0.0f;
    private long   lastUpdateTime = 0;
    private boolean hasValidLocation = false;

    public RescueLinkLocationManager(Context context) {
        this.context     = context.getApplicationContext();
        this.fusedClient = LocationServices.getFusedLocationProviderClient(context);
    }

    public void setLocationListener(LocationListener listener) {
        this.listener = listener;
    }

    public void startUpdates() {
        if (!hasPermission()) {
            Log.w(TAG, "Location permission not granted");
            if (listener != null) listener.onLocationUnavailable();
            return;
        }

        LocationRequest request = new LocationRequest.Builder(UPDATE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
                .setMinUpdateDistanceMeters(MIN_DISPLACEMENT_M)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location location = result.getLastLocation();
                if (location == null) return;

                lastLatitude    = location.getLatitude();
                lastLongitude   = location.getLongitude();
                lastAccuracy    = location.getAccuracy();
                lastUpdateTime  = System.currentTimeMillis();
                hasValidLocation = true;

                if (listener != null) {
                    listener.onLocationUpdated(lastLatitude, lastLongitude, lastAccuracy);
                }
            }
        };

        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());

        fusedClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null && !hasValidLocation) {
                lastLatitude    = location.getLatitude();
                lastLongitude   = location.getLongitude();
                lastAccuracy    = location.getAccuracy();
                hasValidLocation = true;
                if (listener != null) {
                    listener.onLocationUpdated(lastLatitude, lastLongitude, lastAccuracy);
                }
            }
        });
    }

    public void stopUpdates() {
        if (locationCallback != null) {
            fusedClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
    }

    // ─── THE MISSING GETTERS YOU NEED ────────────────────────────────────
    public double getLatitude()       { return lastLatitude; }
    public double getLongitude()      { return lastLongitude; }
    public float  getAccuracy()       { return lastAccuracy; }
    public boolean hasValidLocation() { return hasValidLocation; }
    public long   getLastUpdateTime() { return lastUpdateTime; }

    public long getAgeMs() {
        if (lastUpdateTime == 0) return Long.MAX_VALUE;
        return System.currentTimeMillis() - lastUpdateTime;
    }

    public boolean isFresh() {
        return hasValidLocation && getAgeMs() < 30000;
    }

    private boolean hasPermission() {
        return ActivityCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
}