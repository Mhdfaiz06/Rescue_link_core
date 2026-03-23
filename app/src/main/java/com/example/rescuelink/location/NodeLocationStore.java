package com.example.rescuelink.location;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NodeLocationStore {

    public static class NodeInfo {
        public final String meshId;
        public double latitude;
        public double longitude;
        public float  accuracy;
        public int    battery;
        public long   lastSeenMs;
        public boolean isSosActive;
        public boolean hasDirectGps;
        public String  lastMessage;
        public long    lastMessageTime;

        public NodeInfo(String meshId) {
            this.meshId = meshId;
        }

        public long ageMs() { return System.currentTimeMillis() - lastSeenMs; }
        public boolean isOnline() { return ageMs() < 120000; }
        public boolean isLocationStale() { return ageMs() > 300000; }
    }

    private final Map<String, NodeInfo> nodes = new ConcurrentHashMap<>();

    public interface NodeStoreListener {
        void onNodeUpdated(NodeInfo node);
        void onNodeOffline(String meshId);
    }
    private NodeStoreListener listener;
    public void setListener(NodeStoreListener listener) { this.listener = listener; }

    public void updateLocation(String meshId, double lat, double lon, float accuracy, int battery, boolean hasGps) {
        NodeInfo node = getOrCreate(meshId);
        node.latitude    = lat;
        node.longitude   = lon;
        node.accuracy    = accuracy;
        node.battery     = battery;
        node.lastSeenMs  = System.currentTimeMillis();
        node.hasDirectGps = hasGps;
        if (listener != null) listener.onNodeUpdated(node);
    }

    public void markSosActive(String meshId) {
        NodeInfo node = getOrCreate(meshId);
        node.isSosActive = true;
        node.lastSeenMs  = System.currentTimeMillis();
        if (listener != null) listener.onNodeUpdated(node);
    }

    public void clearSos(String meshId) {
        NodeInfo node = nodes.get(meshId);
        if (node != null) {
            node.isSosActive = false;
            if (listener != null) listener.onNodeUpdated(node);
        }
    }

    public void updateLastMessage(String meshId, String message) {
        NodeInfo node = getOrCreate(meshId);
        node.lastMessage     = message;
        node.lastMessageTime = System.currentTimeMillis();
        node.lastSeenMs      = System.currentTimeMillis();
        if (listener != null) listener.onNodeUpdated(node);
    }

    public void updateStatus(String meshId, int battery) {
        NodeInfo node = getOrCreate(meshId);
        node.battery    = battery;
        node.lastSeenMs = System.currentTimeMillis();
        if (listener != null) listener.onNodeUpdated(node);
    }

    public NodeInfo getNode(String meshId) { return nodes.get(meshId); }
    public List<NodeInfo> getAllNodes() { return new ArrayList<>(nodes.values()); }

    public List<NodeInfo> getOnlineNodes() {
        List<NodeInfo> online = new ArrayList<>();
        for (NodeInfo node : nodes.values()) { if (node.isOnline()) online.add(node); }
        return online;
    }

    public int getOnlineCount() {
        int count = 0;
        for (NodeInfo node : nodes.values()) { if (node.isOnline()) count++; }
        return count;
    }

    private NodeInfo getOrCreate(String meshId) {
        return nodes.computeIfAbsent(meshId, NodeInfo::new);
    }
}