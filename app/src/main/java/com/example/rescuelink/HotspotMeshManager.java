package com.example.rescuelink;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class HotspotMeshManager {

    public interface PacketListener {
        void onPacketReceived(byte[] data, String sourceAddress);
    }

    private static final int MESH_PORT = 9876;
    private static final String HOTSPOT_GATEWAY_IP = "192.168.43.1";

    private PacketListener listener;

    private ServerSocket serverSocket;
    private final List<Socket> connectedClients = new CopyOnWriteArrayList<>();
    private final List<Socket> outboundConnections = new CopyOnWriteArrayList<>();

    private boolean isRunning = false;
    private boolean isBackboneNode = false;

    private final WifiManager wifiManager;
    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;

    public HotspotMeshManager(Context context, PacketListener listener) {
        this.listener = listener;
        // Grabbing the system service, we don't need to save the Context globally
        this.wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    public void setPacketListener(PacketListener listener) {
        this.listener = listener;
    }

    public void evaluateBackboneRole(int ourScore, Map<String, Integer> peerScores) {
        boolean weShouldBeBackbone = true;
        for (int peerScore : peerScores.values()) {
            if (peerScore > ourScore) { weShouldBeBackbone = false; break; }
        }

        if (weShouldBeBackbone && !isBackboneNode) {
            startAsBackboneNode();
        } else if (!weShouldBeBackbone && isBackboneNode) {
            stopBackboneNode();
            connectToBackbone(HOTSPOT_GATEWAY_IP);
        }
    }

    public static int calculateNodeScore(Context ctx, int connectedPeerCount, boolean isForegrounded) {
        int score = 0;
        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent bat = ctx.registerReceiver(null, intentFilter);
        if (bat != null) {
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, 50);
            int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            score += (int)((level / (float) scale) * 100);
            int status = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) {
                score += 50;
            }
        }
        score += connectedPeerCount * 10;
        if (!isForegrounded) score -= 20;
        return score;
    }

    @SuppressLint("MissingPermission") // Suppressed because MainActivity already handles permission requests
    public void startAsBackboneNode() {
        isBackboneNode = true;
        isRunning = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wifiManager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    hotspotReservation = reservation;
                    startTcpServer();
                }
                @Override
                public void onFailed(int reason) { startTcpServer(); }
            }, new Handler(Looper.getMainLooper()));
        } else {
            startTcpServer();
        }
    }

    private void startTcpServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(MESH_PORT);
                serverSocket.setReuseAddress(true);
                while (isRunning) {
                    Socket client = serverSocket.accept();
                    client.setKeepAlive(true);
                    client.setSoTimeout(30000);
                    connectedClients.add(client);
                    handleIncomingClient(client);
                }
            } catch (IOException e) {
                if (isRunning) Log.e("Hotspot", "Server error: " + e.getMessage());
            }
        }, "HotspotServer").start();
    }

    private void handleIncomingClient(Socket client) {
        new Thread(() -> {
            try (DataInputStream dis = new DataInputStream(client.getInputStream())) {
                while (isRunning && client.isConnected()) {
                    int length = dis.readInt();
                    if (length <= 0 || length > 65536) break;
                    byte[] data = new byte[length];
                    dis.readFully(data);
                    if (listener != null) listener.onPacketReceived(data, client.getInetAddress().getHostAddress());
                    relayToOtherClients(client, data);
                }
            } catch (IOException e) {
                Log.d("Hotspot", "Client disconnected: " + e.getMessage());
            } finally {
                connectedClients.remove(client);
                try { client.close(); } catch (IOException ignored) {}
            }
        }).start();
    }

    public void connectToBackbone(String backboneIp) {
        new Thread(() -> {
            int retries = 0;
            while (isRunning && retries < 5) {
                try {
                    Socket socket = new Socket();
                    socket.setKeepAlive(true);
                    socket.connect(new InetSocketAddress(backboneIp, MESH_PORT), 5000);
                    outboundConnections.add(socket);
                    listenOnOutboundSocket(socket);
                    return;
                } catch (IOException e) {
                    retries++;
                    try { Thread.sleep(2000L * retries); } catch (InterruptedException ignored) {}
                }
            }
        }, "HotspotConnector").start();
    }

    private void listenOnOutboundSocket(Socket socket) {
        new Thread(() -> {
            try (DataInputStream dis = new DataInputStream(socket.getInputStream())) {
                while (isRunning && socket.isConnected()) {
                    int length = dis.readInt();
                    if (length <= 0 || length > 65536) break;
                    byte[] data = new byte[length];
                    dis.readFully(data);
                    if (listener != null) listener.onPacketReceived(data, socket.getInetAddress().getHostAddress());
                }
            } catch (IOException e) {
                Log.d("Hotspot", "Backbone disconnected");
            } finally {
                outboundConnections.remove(socket);
                try { socket.close(); } catch (IOException ignored) {}
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (isRunning && !isBackboneNode) connectToBackbone(HOTSPOT_GATEWAY_IP);
                }, 5000);
            }
        }).start();
    }

    public boolean send(byte[] data) {
        boolean sent = false;
        byte[] framed = framePacket(data);
        for (Socket client : connectedClients) { if (writeToSocket(client, framed)) sent = true; }
        for (Socket conn : outboundConnections) { if (writeToSocket(conn, framed)) sent = true; }
        return sent;
    }

    private boolean writeToSocket(Socket socket, byte[] framedData) {
        try {
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            dos.write(framedData);
            dos.flush();
            return true;
        } catch (IOException e) { return false; }
    }

    private void relayToOtherClients(Socket sender, byte[] data) {
        byte[] framed = framePacket(data);
        for (Socket client : connectedClients) {
            if (client != sender) writeToSocket(client, framed);
        }
    }

    private byte[] framePacket(byte[] data) {
        ByteBuffer bb = ByteBuffer.allocate(4 + data.length);
        bb.putInt(data.length);
        bb.put(data);
        return bb.array();
    }

    public boolean isConnected() {
        return !connectedClients.isEmpty() || !outboundConnections.isEmpty();
    }

    private void stopBackboneNode() {
        isBackboneNode = false;

        // Wrap the close() method in an API check to satisfy the minSDK requirements
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (hotspotReservation != null) {
                hotspotReservation.close();
                hotspotReservation = null;
            }
        }

        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        for (Socket s : connectedClients) { try { s.close(); } catch (IOException ignored) {} }
        connectedClients.clear();
    }

    public void stop() {
        isRunning = false;
        stopBackboneNode();
        for (Socket s : outboundConnections) { try { s.close(); } catch (IOException ignored) {} }
        outboundConnections.clear();
    }
}