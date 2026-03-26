package com.example.rescuelink;

import android.Manifest;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.maplibre.android.MapLibre;

public class MainActivity extends AppCompatActivity {

    public final String USER_NICKNAME = Build.MANUFACTURER + " " + Build.MODEL;
    public String myMeshId;

    // --- Mesh Location & Map Tools ---
    private com.example.rescuelink.location.RescueLinkLocationManager locationManager;
    private com.example.rescuelink.location.LocationBroadcaster locationBroadcaster;
    private com.example.rescuelink.location.NodeLocationStore nodeLocationStore;
    private com.example.rescuelink.map.MapUIManager mapUIManager;

    // --- Core Architecture Managers ---
    private PacketDeduplicator deduplicator;
    private NearbyMeshManager nearbyManager;
    private HotspotMeshManager hotspotManager;
    private com.example.rescuelink.TransportBroker audioBroker; // The Unified Pipe
    private OpusAudioManager audioManager;
    private BleBeaconManager bleBeacon;

    public boolean isAppInBackground = false;
    private final Map<String, Integer> deviceScores = new ConcurrentHashMap<>();

    // --- Half-Duplex Channel Control ---
    private String channelOwner = null;
    private static final int CHANNEL_TIMEOUT_MS = 10000;
    private final Handler channelTimeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable channelTimeoutRunnable = () -> {
        if (channelOwner != null) {
            log("CHANNEL: Auto-released after timeout (owner: " + channelOwner + ")");
            releaseChannel();
        }
    };

    // --- UI Elements ---
    private TextView statusText, myIdText, debugLog, channelStatusText;
    private ScrollView logScrollView;
    private Button btnPtt, btnSend, btnDisconnect, btnSos;
    private EditText inputMessage;
    private SharedPreferences prefs;

    // ==========================================
    //      LIFECYCLE
    // ==========================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        org.maplibre.android.MapLibre.getInstance(this);
        setContentView(R.layout.activity_main);

        SharedPreferences p = getSharedPreferences("RescuePrefs", MODE_PRIVATE);
        String lastCrash = p.getString("last_crash", null);
        if (lastCrash != null) {
            Log.e("LAST_CRASH", lastCrash);
            p.edit().remove("last_crash").apply();
        }

        myMeshId = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        initUI();
        setupCrashHandler();

        if (hasPermissions()) {
            initMeshInfrastructure();
        } else {
            requestPermissions();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mapUIManager != null) mapUIManager.onStart();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isAppInBackground = true;
        log("MODE: Repeater (Background)");
        if (mapUIManager != null) mapUIManager.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isAppInBackground = false;
        log("MODE: Active Node (Foreground)");
        if (mapUIManager != null) mapUIManager.onResume();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mapUIManager != null) mapUIManager.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        channelTimeoutHandler.removeCallbacks(channelTimeoutRunnable);
        if (mapUIManager != null) mapUIManager.onDestroy();
        if (locationManager != null) locationManager.stopUpdates();
        if (locationBroadcaster != null) locationBroadcaster.stop();
        if (nearbyManager != null) nearbyManager.disconnectAll();
        if (hotspotManager != null) hotspotManager.stop();
        if (audioManager != null) audioManager.release();
    }

    // ==========================================
    //      UI INIT
    // ==========================================

    private void initUI() {
        statusText        = findViewById(R.id.statusText);
        myIdText          = findViewById(R.id.myIdText);
        debugLog          = findViewById(R.id.debugLog);
        logScrollView     = (ScrollView) debugLog.getParent();
        btnPtt            = findViewById(R.id.btnPtt);
        btnSend           = findViewById(R.id.btnSend);
        btnDisconnect     = findViewById(R.id.btnDisconnect);
        btnSos            = findViewById(R.id.btnSos);
        inputMessage      = findViewById(R.id.inputMessage);
        channelStatusText = findViewById(R.id.channelStatusText);

        myIdText.setText(String.format("%s [%s]", USER_NICKNAME, myMeshId));
        debugLog.setMovementMethod(new ScrollingMovementMethod());

        btnPtt.setVisibility(View.GONE);
        btnSos.setVisibility(View.GONE);
    }

    // ==========================================
    //      MESH INFRASTRUCTURE
    // ==========================================

    private void initMeshInfrastructure() {
        log("SYSTEM: Booting Hybrid Mesh Architecture...");

        deduplicator  = new PacketDeduplicator();
        nearbyManager = new NearbyMeshManager(this);
        hotspotManager = new HotspotMeshManager(this, null);
        bleBeacon     = new BleBeaconManager(this);

        nodeLocationStore = new com.example.rescuelink.location.NodeLocationStore();
        locationManager   = new com.example.rescuelink.location.RescueLinkLocationManager(this);
        locationManager.startUpdates();

        // 1. Initialize Unified Broker
        audioBroker = new com.example.rescuelink.TransportBroker(
                nearbyManager, hotspotManager, deduplicator, this::handleIncomingPacket
        );

        // 2. Initialize Broadcaster using audioBroker
        locationBroadcaster = new com.example.rescuelink.location.LocationBroadcaster(audioBroker, myMeshId, locationManager);
        locationBroadcaster.setStatusProvider(new com.example.rescuelink.location.LocationBroadcaster.StatusProvider() {
            @Override
            public int getBatteryLevel() {
                return MainActivity.this.getBatteryLevel();
            }
            @Override
            public boolean isSosActive() {
                return statusText.getText().toString().contains("SOS");
            }
        });
        locationBroadcaster.start();

        audioManager = new OpusAudioManager(audioBroker, myMeshId, this);

        setupListeners();
        nearbyManager.startAutonomousNetwork();
        startOptimizationLoop();

        Intent serviceIntent = new Intent(this, MeshForegroundService.class);
        serviceIntent.putExtra("MESH_ID", myMeshId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        mapUIManager = new com.example.rescuelink.map.MapUIManager(this, nodeLocationStore, locationManager);
        mapUIManager.init(null);

        log("SYSTEM: Mesh ready. ID = " + myMeshId);
    }

    // ==========================================
    //      LISTENERS
    // ==========================================

    private void setupListeners() {
        if (audioManager.canTransmitAudio()) {
            btnPtt.setVisibility(View.VISIBLE);
            btnPtt.setOnTouchListener((v, event) -> {
                int action = event.getAction();

                if (action == MotionEvent.ACTION_DOWN) {
                    if (channelOwner != null) {
                        log("CHANNEL: Busy — " + channelOwner + " is talking");
                        v.performClick();
                        return true;
                    }

                    claimChannel(myMeshId);
                    sendControlPacket("PTT_START:" + myMeshId);

                    // Central Gate - Tell the broker to pause GPS/Status traffic
                    if (audioBroker != null) audioBroker.setAudioActive(true);

                    audioManager.startRecording();
                    btnPtt.setText(R.string.ptt_talking);
                    v.performClick();

                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (myMeshId.equals(channelOwner)) {
                        // 1. Stop audio first (clears the bandwidth pipe immediately)
                        audioManager.stopRecording();

                        // 2. Open the gate (allow GPS/Status again)
                        if (audioBroker != null) audioBroker.setAudioActive(false);

                        // 3. Release locally
                        releaseChannel();

                        // 4. Progressive resend (Fires through the newly cleared pipe)
                        sendControlPacket("PTT_END:" + myMeshId);
                        new Handler(Looper.getMainLooper()).postDelayed(() -> sendControlPacket("PTT_END:" + myMeshId), 150);
                        new Handler(Looper.getMainLooper()).postDelayed(() -> sendControlPacket("PTT_END:" + myMeshId), 400);
                        new Handler(Looper.getMainLooper()).postDelayed(() -> sendControlPacket("PTT_END:" + myMeshId), 800);
                    }
                    v.performClick();
                }
                return true;
            });
        } else {
            btnPtt.setVisibility(View.GONE);
            log(getString(R.string.log_repeater_only, Build.VERSION.SDK_INT));
        }

        btnSend.setOnClickListener(v -> {
            if (audioBroker == null) { log("ERROR: Mesh not ready."); return; }
            String m = inputMessage.getText().toString().trim();
            if (!m.isEmpty()) {
                MeshPacket textPacket = new MeshPacket(
                        'M', myMeshId, MeshPacket.BROADCAST_ID,
                        m.getBytes(StandardCharsets.UTF_8));
                audioBroker.send(textPacket);
                log("Me: " + m);
                inputMessage.setText("");
            }
        });

        btnSos.setVisibility(View.VISIBLE);
        btnSos.setOnClickListener(v -> {
            MeshPacket sosPacket = new MeshPacket(
                    'E', myMeshId, MeshPacket.BROADCAST_ID,
                    "SOS".getBytes(StandardCharsets.UTF_8));
            audioBroker.send(sosPacket);
            bleBeacon.startSosBeacon(0.0, 0.0, getBatteryLevel(), myMeshId);
            log("SOS BROADCAST SENT on all channels");
            statusText.setText(R.string.status_sos_active);
            if (nodeLocationStore != null) nodeLocationStore.markSosActive(myMeshId);
        });

        btnDisconnect.setOnClickListener(v -> {
            log("MANUAL RESET: Clearing all links...");
            nearbyManager.disconnectAll();
            hotspotManager.stop();
            nearbyManager.startAutonomousNetwork();
        });
    }

    // ==========================================
    //      CHANNEL CONTROL
    // ==========================================

    private void claimChannel(String meshId) {
        channelOwner = meshId;
        channelTimeoutHandler.removeCallbacks(channelTimeoutRunnable);
        channelTimeoutHandler.postDelayed(channelTimeoutRunnable, CHANNEL_TIMEOUT_MS);
        updateChannelStatus();
    }

    private void releaseChannel() {
        channelOwner = null;
        channelTimeoutHandler.removeCallbacks(channelTimeoutRunnable);
        updateChannelStatus();
    }

    private void updateChannelStatus() {
        runOnUiThread(() -> {
            if (channelOwner == null) {
                if (audioManager != null && audioManager.canTransmitAudio()) {
                    btnPtt.setEnabled(true);
                    btnPtt.setAlpha(1.0f);
                    btnPtt.setText(R.string.ptt_idle);
                }
                if (channelStatusText != null) {
                    channelStatusText.setText("Channel: Free");
                    channelStatusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
                }
            } else if (myMeshId.equals(channelOwner)) {
                if (channelStatusText != null) {
                    channelStatusText.setText("Channel: YOU are transmitting");
                    channelStatusText.setTextColor(getResources().getColor(android.R.color.holo_orange_dark, null));
                }
            } else {
                if (audioManager != null && audioManager.canTransmitAudio()) {
                    btnPtt.setEnabled(false);
                    btnPtt.setAlpha(0.4f);
                    btnPtt.setText(channelOwner + " talking...");
                }
                if (channelStatusText != null) {
                    channelStatusText.setText("Channel: " + channelOwner + " is talking");
                    channelStatusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark, null));
                }
            }
        });
    }

    private void sendControlPacket(String controlMessage) {
        if (audioBroker == null) return;
        MeshPacket controlPacket = new MeshPacket(
                'C', myMeshId, MeshPacket.BROADCAST_ID,
                controlMessage.getBytes(StandardCharsets.UTF_8));
        audioBroker.send(controlPacket);
    }

    // ==========================================
    //      PACKET HANDLER (CRASH-PROOF)
    // ==========================================

    private void handleIncomingPacket(MeshPacket packet, String sourceId) {
        try {
            if (packet == null || sourceId == null) return;
            nearbyManager.updateRoutingTable(packet.originId, sourceId);
            boolean isForMe = packet.isBroadcast() || packet.targetId.equals(myMeshId);

            if (isForMe) {
                switch (packet.tag) {
                    case 'A':
                        if (audioManager != null) audioManager.playIncomingAudio(packet.payload);
                        break;
                    case 'M':
                        if (packet.payload != null) {
                            String msg = new String(packet.payload, StandardCharsets.UTF_8);
                            log(packet.originId + ": " + msg);
                            if (nodeLocationStore != null) nodeLocationStore.updateLastMessage(packet.originId, msg);
                        }
                        break;
                    case 'L':
                        if (packet.payload != null) {
                            String locData = new String(packet.payload, StandardCharsets.UTF_8);
                            String[] parts = locData.split("\\|");
                            if (parts.length >= 7) {
                                double lat = Double.parseDouble(parts[2]);
                                double lon = Double.parseDouble(parts[3]);
                                float acc = Float.parseFloat(parts[4]);
                                int bat = Integer.parseInt(parts[5]);
                                boolean isSos = Boolean.parseBoolean(parts[6]);
                                if (nodeLocationStore != null) {
                                    nodeLocationStore.updateLocation(packet.originId, lat, lon, acc, bat, true);
                                    if (isSos) nodeLocationStore.markSosActive(packet.originId);
                                }
                            }
                        }
                        break;
                    case 'S':
                        handleStatusUpdate(packet.originId, packet.payload);
                        break;
                    case 'E':
                        log("SOS RECEIVED from " + packet.originId);
                        if (nodeLocationStore != null) nodeLocationStore.markSosActive(packet.originId);
                        runOnUiThread(() -> statusText.setText(getString(R.string.status_sos_received, packet.originId)));
                        break;
                    case 'C':
                        handleControlPacket(packet.originId, packet.payload);
                        break;
                }
            }
            if (audioBroker != null) audioBroker.relay(packet, sourceId, isForMe);
        } catch (Exception e) {
            Log.e("MainActivity", "handleIncomingPacket error: " + e.getMessage());
        }
    }

    private void handleControlPacket(String originId, byte[] payload) {
        String control = new String(payload, StandardCharsets.UTF_8);
        if (control.startsWith("PTT_START:")) {
            String talkerId = control.substring(10);
            if (!myMeshId.equals(talkerId)) {
                channelOwner = talkerId;
                runOnUiThread(() -> {
                    if (audioManager != null && audioManager.canTransmitAudio()) {
                        btnPtt.setEnabled(false);
                        btnPtt.setAlpha(0.4f);
                        btnPtt.setText(talkerId + " talking...");
                    }
                    if (channelStatusText != null) channelStatusText.setText("Channel: " + talkerId + " is talking");
                });
                log("🎙 " + talkerId + " is transmitting");
            }
        } else if (control.startsWith("PTT_END:")) {
            String talkerId = control.substring(8);
            if (talkerId.equals(channelOwner)) {
                channelOwner = null;
                channelTimeoutHandler.removeCallbacks(channelTimeoutRunnable);
                runOnUiThread(() -> {
                    if (audioManager != null && audioManager.canTransmitAudio()) {
                        btnPtt.setEnabled(true);
                        btnPtt.setAlpha(1.0f);
                        btnPtt.setText(R.string.ptt_idle);
                    }
                    if (channelStatusText != null) channelStatusText.setText("Channel: Free");
                });
                log("✓ Channel free");
            }
        }
    }

    private void handleStatusUpdate(String originId, byte[] body) {
        if (body == null || originId == null) return;
        try {
            String bodyStr = new String(body, StandardCharsets.UTF_8).trim();
            int peerScore = Integer.parseInt(bodyStr);
            deviceScores.put(originId, peerScore);
            if (nodeLocationStore != null) nodeLocationStore.updateStatus(originId, peerScore);

            if (hotspotManager != null && nearbyManager != null) {
                int ourScore = HotspotMeshManager.calculateNodeScore(this, nearbyManager.getPeerCount(), !isAppInBackground);
                hotspotManager.evaluateBackboneRole(ourScore, deviceScores);
            }

            if (nearbyManager != null && !nearbyManager.isHost() && peerScore < 15) {
                log("Host battery critical. Re-clustering...");
                nearbyManager.disconnectAll();
                nearbyManager.startAutonomousNetwork();
            }
        } catch (Exception e) {
            Log.w("MainActivity", "handleStatusUpdate error: " + e.getMessage());
        }
    }

    private void startOptimizationLoop() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (nearbyManager != null && nearbyManager.isConnected() && !isAppInBackground) {
                    int myScore = HotspotMeshManager.calculateNodeScore(MainActivity.this, nearbyManager.getPeerCount(), true);
                    MeshPacket statusPacket = new MeshPacket('S', myMeshId, MeshPacket.BROADCAST_ID, String.valueOf(myScore).getBytes(StandardCharsets.UTF_8));
                    if (audioBroker != null) audioBroker.send(statusPacket);
                }
                new Handler(Looper.getMainLooper()).postDelayed(this, 10000);
            }
        }, 5000);
    }

    // ==========================================
    //      HELPERS
    // ==========================================

    private int getBatteryLevel() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, filter);
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            return (int) ((level / (float) scale) * 100);
        }
        return 50;
    }

    public void setStatusText(String text) {
        runOnUiThread(() -> statusText.setText(text));
    }

    public void log(String msg) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        runOnUiThread(() -> {
            if (debugLog != null) {
                debugLog.append("\n[" + t + "] " + msg);
                if (logScrollView != null) logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    // ==========================================
    //      PERMISSIONS
    // ==========================================

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, "android.permission.NEARBY_WIFI_DEVICES") == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add("android.permission.NEARBY_WIFI_DEVICES");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), 123);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 123) {
            if (hasPermissions()) initMeshInfrastructure();
            else {
                log(getString(R.string.log_permissions_denied));
                log(getString(R.string.log_permissions_instruction));
                statusText.setText(R.string.status_permissions_denied);
            }
        }
    }

    private void setupCrashHandler() {
        prefs = getSharedPreferences("RescuePrefs", MODE_PRIVATE);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            prefs.edit().putString("last_crash", Log.getStackTraceString(e)).apply();
            System.exit(2);
        });
    }
}