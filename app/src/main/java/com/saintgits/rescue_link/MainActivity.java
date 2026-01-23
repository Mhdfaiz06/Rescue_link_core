package com.saintgits.rescue_link;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

// REAL ENGINE IMPORTS
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Strategy;

import java.util.Random;

public class MainActivity extends AppCompatActivity {

    // 1. CONFIGURATION
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;
    private static final String SERVICE_ID = "com.saintgits.rescuelink";

    // 2. VARIABLES
    private ConnectionsClient connectionsClient;
    private String codename;
    private TextView statusText;
    private Button btnAdvertise;
    private Button btnDiscover;

    // 3. PERMISSION REQUEST CODE
    private static final int REQUEST_CODE_REQUIRED_PERMISSIONS = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // A. Setup Name & UI
        codename = "Rescuer-" + new Random().nextInt(100);
        statusText = findViewById(R.id.statusText);
        btnAdvertise = findViewById(R.id.btnAdvertise);
        btnDiscover = findViewById(R.id.btnDiscover);
        connectionsClient = Nearby.getConnectionsClient(this);

        statusText.setText("My ID: " + codename + "\nStatus: Idle");

        // B. Setup Buttons with REAL Logic
        btnAdvertise.setOnClickListener(v -> startAdvertising());
        btnDiscover.setOnClickListener(v -> startDiscovery());
    }

    // --- REAL RADIO LOGIC ---

    private void startAdvertising() {
        AdvertisingOptions advertisingOptions =
                new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();

        // The "Call" to the Hardware
        connectionsClient.startAdvertising(
                        codename,       // The name others will see
                        SERVICE_ID,     // The secret channel
                        connectionLifecycleCallback, // The function to run when connected
                        advertisingOptions
                )
                .addOnSuccessListener(
                        (Void unused) -> {
                            // SUCCESS: The radio is actually On now
                            Toast.makeText(this, "Radio ON: Broadcasting...", Toast.LENGTH_SHORT).show();
                            statusText.setText("Status: Broadcasting...");
                            btnAdvertise.setEnabled(false); // Disable button so we don't click twice
                        })
                .addOnFailureListener(
                        (Exception e) -> {
                            // FAILURE: Bluetooth might be off, or another app is using it
                            Toast.makeText(this, "Radio ERROR: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        });
    }

    private void startDiscovery() {
        DiscoveryOptions discoveryOptions =
                new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();

        connectionsClient.startDiscovery(
                        SERVICE_ID,
                        endpointDiscoveryCallback, // The function to run when a signal is found
                        discoveryOptions
                )
                .addOnSuccessListener(
                        (Void unused) -> {
                            Toast.makeText(this, "Scanner ON: Searching...", Toast.LENGTH_SHORT).show();
                            statusText.setText("Status: Searching...");
                            btnDiscover.setEnabled(false);
                        })
                .addOnFailureListener(
                        (Exception e) -> {
                            Toast.makeText(this, "Scanner ERROR: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        });
    }

    // --- CALLBACKS: What happens when phones talk? ---

    // 1. When a Signal is Found (Discovery Mode)
    private final EndpointDiscoveryCallback endpointDiscoveryCallback =
            new EndpointDiscoveryCallback() {
                @Override
                public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
                    // We found someone! Automatically try to connect.
                    Toast.makeText(getApplicationContext(), "Found: " + info.getEndpointName(), Toast.LENGTH_SHORT).show();
                    connectionsClient.requestConnection(codename, endpointId, connectionLifecycleCallback);
                }

                @Override
                public void onEndpointLost(String endpointId) {
                    Toast.makeText(getApplicationContext(), "Lost Signal!", Toast.LENGTH_SHORT).show();
                }
            };

    // 2. When a Connection is Made (Handshake)
    private final ConnectionLifecycleCallback connectionLifecycleCallback =
            new ConnectionLifecycleCallback() {
                @Override
                public void onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo) {
                    // Automatically accept the handshake
                    connectionsClient.acceptConnection(endpointId, payloadCallback);
                    Toast.makeText(getApplicationContext(), "Accepting connection...", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onConnectionResult(String endpointId, ConnectionResolution result) {
                    if (result.getStatus().isSuccess()) {
                        statusText.setText("Status: Connected to " + endpointId);
                        Toast.makeText(getApplicationContext(), "CONNECTED!", Toast.LENGTH_LONG).show();
                        // Turn buttons Green to show success? (Optional)
                    } else {
                        statusText.setText("Status: Connection Failed");
                    }
                }

                @Override
                public void onDisconnected(String endpointId) {
                    statusText.setText("Status: Disconnected");
                }
            };

    // 3. Data Transfer (We aren't sending data yet, but we need this placeholder)
    private final com.google.android.gms.nearby.connection.PayloadCallback payloadCallback =
            new com.google.android.gms.nearby.connection.PayloadCallback() {
                @Override
                public void onPayloadReceived(String endpointId, com.google.android.gms.nearby.connection.Payload payload) {
                    // Real data logic goes here later
                }

                @Override
                public void onPayloadTransferUpdate(String endpointId, com.google.android.gms.nearby.connection.PayloadTransferUpdate update) {
                }
            };

    // --- PERMISSION LOGIC (UNCHANGED) ---

    @Override
    protected void onStart() {
        super.onStart();
        if (!hasPermissions(this, getRequiredPermissions())) {
            requestPermissions(getRequiredPermissions(), REQUEST_CODE_REQUIRED_PERMISSIONS);
        }
    }

    // UPDATED PERMISSION LIST (Includes Fix for Error 8029)
    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13 (Tiramisu) and above: NEEDS NEARBY_WIFI_DEVICES
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES // <--- The Missing Key
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 (S): Bluetooth + Location
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10/11: Fine Location
            return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        } else {
            // Older phones
            return new String[]{Manifest.permission.ACCESS_COARSE_LOCATION};
        }
    }

   /* private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        } else {
            return new String[]{Manifest.permission.ACCESS_COARSE_LOCATION};
        }
    }*/

    private static boolean hasPermissions(MainActivity context, String... permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_REQUIRED_PERMISSIONS) {
            for (int grantResult : grantResults) {
                if (grantResult == PackageManager.PERMISSION_DENIED) {
                    Toast.makeText(this, "Permissions Missing!", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
            }
            recreate();
        }
    }
}