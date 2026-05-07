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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class MeshForegroundService extends Service {

    private static final String CHANNEL_ID = "RescueLinkMeshChannel";
    private static final int NOTIFICATION_ID = 1;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    // [CHANGE APPLIED]: 9-Minute WakeLock renewal handler
    private static final long WAKELOCK_RENEW_INTERVAL_MS = 9 * 60 * 1000L;
    private final Handler wakeLockRenewHandler = new Handler(Looper.getMainLooper());
    private final Runnable wakeLockRenewer = new Runnable() {
        @Override
        public void run() {
            if (wakeLock != null) {
                if (wakeLock.isHeld()) wakeLock.release();
                wakeLock.acquire(10 * 60 * 1000L);
            }
            wakeLockRenewHandler.postDelayed(this, WAKELOCK_RENEW_INTERVAL_MS);
        }
    };

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
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
        return START_STICKY;
    }

    private void acquireHardwareLocks() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RescueLink::CpuWakeLock");
            wakeLock.acquire(10 * 60 * 1000L);
            // [CHANGE APPLIED]: Start renewal cycle
            wakeLockRenewHandler.postDelayed(wakeLockRenewer, WAKELOCK_RENEW_INTERVAL_MS);
        }

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
        // [CHANGE APPLIED]: Stop renewal timer
        wakeLockRenewHandler.removeCallbacks(wakeLockRenewer);
        releaseHardwareLocks();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}