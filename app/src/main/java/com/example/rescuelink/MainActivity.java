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
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends AppCompatActivity {

    public final String USER_NICKNAME = Build.MANUFACTURER + " " + Build.MODEL;
    public String myMeshId;

    // --- Core Architecture Managers ---
    private PacketDeduplicator deduplicator;
    private NearbyMeshManager nearbyManager;
    private HotspotMeshManager hotspotManager;
    private TransportBroker broker;
    private OpusAudioManager audioManager;
    private BleBeaconManager bleBeacon;
    // private DtnQueue dtnQueue; // Uncomment when you add SQLite DTN layer

    public boolean isAppInBackground = false;
    private final Map<String, Integer> deviceScores = new ConcurrentHashMap<>();

    // --- UI Elements ---
    private TextView statusText, myIdText, debugLog;
    private ScrollView logScrollView;
    // btnSos kept but only initialized after infrastructure is ready
    private Button btnPtt, btnSend, btnDisconnect, btnSos;
    private EditText inputMessage;
    private SharedPreferences prefs;

    // ==========================================
    //      LIFECYCLE
    // ==========================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
    protected void onPause() {
        super.onPause();
        isAppInBackground = true;
        log("MODE: Repeater (Background)");
    }

    @Override
    protected void onResume() {
        super.onResume();
        isAppInBackground = false;
        log("MODE: Active Node (Foreground)");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nearbyManager != null) nearbyManager.disconnectAll();
        if (hotspotManager != null) hotspotManager.stop();
        if (audioManager != null) audioManager.release();
    }

    // ==========================================
    //      UI INIT
    // ==========================================

    private void initUI() {
        statusText   = findViewById(R.id.statusText);
        myIdText     = findViewById(R.id.myIdText);
        debugLog     = findViewById(R.id.debugLog);
        logScrollView = (ScrollView) debugLog.getParent();
        btnPtt       = findViewById(R.id.btnPtt);
        btnSend      = findViewById(R.id.btnSend);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnSos       = findViewById(R.id.btnSos);
        inputMessage  = findViewById(R.id.inputMessage); // ← add this line

        myIdText.setText(String.format("%s [%s]", USER_NICKNAME, myMeshId));
        debugLog.setMovementMethod(new ScrollingMovementMethod());

        // Hide SOS and PTT until infrastructure is ready
        // Prevents clicks before broker is initialized
        btnSos.setVisibility(View.GONE);
        btnPtt.setVisibility(View.GONE);
    }

    // ==========================================
    //      WIRING THE DUAL-LAYER MESH
    // ==========================================

    private void initMeshInfrastructure() {
        log("SYSTEM: Booting Hybrid Mesh Architecture...");

        // 1. Build shared deduplication — single instance covers both networks
        deduplicator = new PacketDeduplicator();

        // 2. Build transport layers
        nearbyManager  = new NearbyMeshManager(this);
        hotspotManager = new HotspotMeshManager(this, null); // listener set inside broker
        bleBeacon      = new BleBeaconManager(this);

        // 3. Build the transport broker — single routing brain above both networks
        broker = new TransportBroker(
                nearbyManager,
                hotspotManager,
                deduplicator,
                this::handleIncomingPacket
        );

        // 4. Build audio layer — sits entirely above the broker
        audioManager = new OpusAudioManager(broker, myMeshId, this);

        // 5. Wire up all UI listeners now that infrastructure is ready
        setupListeners();

        // 6. Ignite the network
        nearbyManager.startAutonomousNetwork();
        startOptimizationLoop();

        log("SYSTEM: Mesh infrastructure ready. MeshID = " + myMeshId);
    }

    // ==========================================
    //      USER INTERFACE & LISTENERS
    // ==========================================

    private void setupListeners() {

        // ── PTT Button ────────────────────────────────────────────────────
        // Only shown on API 29+ devices that can encode Opus
        // API 24–28 devices are repeater-only — PTT stays hidden
        if (audioManager.canTransmitAudio()) {
            btnPtt.setVisibility(View.VISIBLE);
            btnPtt.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    audioManager.startRecording();
                    btnPtt.setText(R.string.ptt_talking);
                } else if (event.getAction() == MotionEvent.ACTION_UP
                        || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    audioManager.stopRecording();
                    btnPtt.setText(R.string.ptt_idle);
                }
                v.performClick();
                return true;
            });
        } else {
            // Repeater-only device — hide PTT, show informational status
            btnPtt.setVisibility(View.GONE);
            log("DEVICE: Repeater-only mode (API " + Build.VERSION.SDK_INT
                    + "). PTT unavailable. Relaying mesh traffic only.");
        }

        // ── Text Send Button ──────────────────────────────────────────────
        // Works on all devices regardless of API level
        btnSend.setOnClickListener(v -> {
            String m = inputMessage.getText().toString().trim();
            if (!m.isEmpty()) {
                MeshPacket textPacket = new MeshPacket(
                        'M', myMeshId, MeshPacket.BROADCAST_ID,
                        m.getBytes(StandardCharsets.UTF_8));
                broker.send(textPacket);
                log("Me: " + m);
                inputMessage.setText("");
            }
        });

        // ── SOS Button ────────────────────────────────────────────────────
        // Visible on all devices — even repeater-only devices can send SOS
        btnSos.setVisibility(View.VISIBLE);
        btnSos.setOnClickListener(v -> {
            // Send SOS packet through mesh on both networks simultaneously
            MeshPacket sosPacket = new MeshPacket(
                    'E', myMeshId, MeshPacket.BROADCAST_ID,
                    "SOS".getBytes(StandardCharsets.UTF_8));
            broker.send(sosPacket);

            // Also blast via BLE advertisement for passive detection
            // beyond the current mesh range
            bleBeacon.startSosBeacon(0.0, 0.0, getBatteryLevel(), myMeshId);

            log("🚨 SOS BROADCAST SENT on all channels");
            statusText.setText("SOS ACTIVE — Broadcasting on all channels");
        });

        // ── Disconnect / Reset Button ─────────────────────────────────────
        btnDisconnect.setOnClickListener(v -> {
            log("MANUAL RESET: Clearing all links and re-scanning...");
            nearbyManager.disconnectAll();
            hotspotManager.stop();
            nearbyManager.startAutonomousNetwork();
        });
    }

    // ==========================================
    //      THE UNIFIED PACKET HANDLER
    // ==========================================

    private void handleIncomingPacket(MeshPacket packet, String sourceId) {
        // Teach the routing table how to reach this origin next time
        nearbyManager.updateRoutingTable(packet.originId, sourceId);

        boolean isForMe = packet.isBroadcast() || packet.targetId.equals(myMeshId);

        if (isForMe) {
            switch (packet.tag) {
                case 'A':
                    // Audio — decoded and played on all devices (decoder works API 21+)
                    audioManager.playIncomingAudio(packet.payload);
                    break;
                case 'M':
                    log(packet.originId + ": "
                            + new String(packet.payload, StandardCharsets.UTF_8));
                    break;
                case 'S':
                    handleStatusUpdate(packet.originId, packet.payload);
                    break;
                case 'E':
                    log("🚨 SOS RECEIVED from " + packet.originId + "!");
                    runOnUiThread(() -> statusText.setText(
                            "🚨 SOS FROM " + packet.originId));
                    break;
            }
        }

        // Broker decides how/whether to relay this packet to other nodes
        broker.relay(packet, sourceId, isForMe);
    }

    private void handleStatusUpdate(String originId, byte[] body) {
        try {
            int peerScore = Integer.parseInt(
                    new String(body, StandardCharsets.UTF_8));
            deviceScores.put(originId, peerScore);

            // Check if we should become the Wi-Fi hotspot backbone
            int ourScore = HotspotMeshManager.calculateNodeScore(
                    this, nearbyManager.getPeerCount(), !isAppInBackground);
            hotspotManager.evaluateBackboneRole(ourScore, deviceScores);

            // Re-cluster if the current Nearby host has critically low battery
            if (!nearbyManager.isHost() && peerScore < 15) {
                log("Host battery critical. Re-clustering mesh...");
                nearbyManager.disconnectAll();
                nearbyManager.startAutonomousNetwork();
            }
        } catch (Exception ignored) {}
    }

    private void startOptimizationLoop() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (nearbyManager.isConnected() && !isAppInBackground) {
                    int myScore = HotspotMeshManager.calculateNodeScore(
                            MainActivity.this, nearbyManager.getPeerCount(), true);
                    MeshPacket statusPacket = new MeshPacket(
                            'S', myMeshId, MeshPacket.BROADCAST_ID,
                            String.valueOf(myScore).getBytes(StandardCharsets.UTF_8));
                    broker.send(statusPacket);
                }
                new Handler(Looper.getMainLooper()).postDelayed(this, 10000);
            }
        }, 5000);
    }

    // ==========================================
    //      HELPERS
    // ==========================================

    private int getBatteryLevel() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
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
                if (logScrollView != null) {
                    logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
                }
            }
        });
    }

    // ==========================================
    //      PERMISSIONS
    // ==========================================

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this,
                    Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this,
                    "android.permission.NEARBY_WIFI_DEVICES") == PackageManager.PERMISSION_GRANTED;
            // CHANGE_NETWORK_STATE intentionally excluded —
            // it is a normal permission granted automatically at install time,
            // checking it at runtime always returns DENIED on API 33+
        }
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.RECORD_AUDIO,
                "android.permission.NEARBY_WIFI_DEVICES",
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
                // CHANGE_NETWORK_STATE and ACCESS_WIFI_STATE excluded —
                // normal permissions, declared in manifest only, not requested at runtime
        }, 123);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 123) {
            if (hasPermissions()) {
                // User granted permissions — now safe to initialize mesh
                initMeshInfrastructure();
            } else {
                // User denied one or more required permissions
                log("ERROR: Required permissions denied. Mesh cannot start.");
                log("Please grant Location, Microphone, and Nearby Devices permissions.");
                statusText.setText("Permissions required — please restart and allow all");
            }
        }
    }

    // ==========================================
    //      CRASH HANDLER
    // ==========================================

    private void setupCrashHandler() {
        prefs = getSharedPreferences("RescuePrefs", MODE_PRIVATE);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            prefs.edit().putString("last_crash", Log.getStackTraceString(e)).apply();
            System.exit(2);
        });
    }
}