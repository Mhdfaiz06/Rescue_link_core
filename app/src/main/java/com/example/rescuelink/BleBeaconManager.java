package com.example.rescuelink;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class BleBeaconManager {

    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advertiseCallback;
    private boolean isAdvertising = false;

    public BleBeaconManager(Context context) {
        BluetoothManager btManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (btManager != null) {
            BluetoothAdapter adapter = btManager.getAdapter();
            if (adapter != null && adapter.isEnabled()) {
                advertiser = adapter.getBluetoothLeAdvertiser();
            }
        }
    }

    public void startSosBeacon(double latitude, double longitude, int batteryLevel, String meshId) {
        if (advertiser == null || isAdvertising) return;

        byte[] payload = buildSosPayload(latitude, longitude, batteryLevel, meshId);

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(0)
                .build();

        ParcelUuid serviceUuid = ParcelUuid.fromString("0000EA62-0000-1000-8000-00805F9B34FB");

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(serviceUuid)
                .addServiceData(serviceUuid, payload)
                .setIncludeDeviceName(false)
                .build();

        advertiseCallback = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                isAdvertising = true;
            }
            @Override public void onStartFailure(int errorCode) {
                Log.e("BLE", "Advertise failed: " + errorCode);
            }
        };

        try {
            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (SecurityException e) {
            Log.e("BLE", "Missing Bluetooth Permission");
        }
    }

    private byte[] buildSosPayload(double lat, double lon, int battery, String nodeId) {
        // Fixed: allocate 14 bytes exactly matching what we write
        // [1] marker + [4] lat + [4] lon + [1] battery + [1] version + [3] nodeId truncated
        byte[] idBytes = nodeId.getBytes(StandardCharsets.US_ASCII);
        // Truncate nodeId to exactly 3 bytes to fit the 14-byte budget
        int idLen = Math.min(idBytes.length, 3);

        ByteBuffer bb = ByteBuffer.allocate(11 + idLen); // exact size
        bb.put((byte) 0xE5);                    // 1 byte — RescueLink SOS marker
        bb.putInt((int)(lat * 1e6));            // 4 bytes — latitude
        bb.putInt((int)(lon * 1e6));            // 4 bytes — longitude
        bb.put((byte) battery);                 // 1 byte  — battery level
        bb.put((byte) 0x01);                    // 1 byte  — protocol version
        bb.put(idBytes, 0, idLen);              // 3 bytes — node ID truncated
        return bb.array();
    }

    public void stopSosBeacon() {
        if (advertiser != null && advertiseCallback != null && isAdvertising) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (SecurityException e) {
                Log.e("BLE", "Missing Bluetooth Permission");
            }
            isAdvertising = false;
        }
    }
}
