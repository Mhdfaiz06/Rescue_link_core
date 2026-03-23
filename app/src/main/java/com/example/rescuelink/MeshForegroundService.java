package com.example.rescuelink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class MeshForegroundService extends Service {

    private static final String CHANNEL_ID = "RescueLinkMeshChannel";
    private static final int NOTIFICATION_ID = 1;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireHardwareLocks();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String meshId = intent != null ? intent.getStringExtra("MESH_ID") : "Unknown";

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("RescueLink Mesh Active")
                .setContentText("Routing packets for Mesh ID: " + meshId)
                .setSmallIcon(android.R.drawable.ic_menu_share) // Replace with your app's icon later
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build();

        // Start the service in the foreground to prevent OS kills
        startForeground(NOTIFICATION_ID, notification);

        // START_STICKY tells the OS to recreate the service if it absolutely has to kill it for memory
        return START_STICKY;
    }

    private void acquireHardwareLocks() {
        // 1. Keep the CPU running even when the screen turns off
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RescueLink::CpuWakeLock");
            wakeLock.acquire(10 * 60 * 1000L /*10 minutes maximum per acquire, standard safety practice*/);
        }

        // 2. Prevent the Wi-Fi radio from dropping into low-power scanning mode
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RescueLink::WifiRadioLock");
            wifiLock.acquire();
        }
    }

    private void releaseHardwareLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Mesh Routing Service",
                    NotificationManager.IMPORTANCE_HIGH
            );
            serviceChannel.setDescription("Keeps the decentralized network alive in the background");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        releaseHardwareLocks();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // We are using a Started Service, not a Bound Service
    }
}