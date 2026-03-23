package com.example.rescuelink.map;

import com.example.rescuelink.location.NodeLocationStore.NodeInfo;

public class MapStyleHelper {

    // Node colors — hex strings for MapLibre
    public static final String OWN_NODE_COLOR      = "#1565C0"; // blue — you
    public static final String ACTIVE_GPS_COLOR    = "#2E7D32"; // green — online with GPS
    public static final String STALE_GPS_COLOR     = "#F9A825"; // amber — online, GPS old
    public static final String ESTIMATED_COLOR     = "#6A1B9A"; // purple — triangulated
    public static final String OFFLINE_COLOR       = "#9E9E9E"; // grey — not seen recently
    public static final String SOS_COLOR           = "#C62828"; // red — SOS active
    public static final String LOW_BATTERY_COLOR   = "#E65100"; // orange — battery < 20%

    public static String getNodeColor(NodeInfo node) {
        if (!node.isOnline())         return OFFLINE_COLOR;
        if (node.isSosActive)         return SOS_COLOR;
        if (node.battery < 20)        return LOW_BATTERY_COLOR;
        if (!node.hasDirectGps)       return ESTIMATED_COLOR;
        if (node.isLocationStale())   return STALE_GPS_COLOR;
        return ACTIVE_GPS_COLOR;
    }

    // Human readable status for tap popup
    public static String getNodeStatusText(NodeInfo node) {
        if (!node.isOnline())       return "Offline";
        if (node.isSosActive)       return "🚨 SOS ACTIVE";
        if (node.battery < 20)      return "⚠️ Low Battery (" + node.battery + "%)";
        if (!node.hasDirectGps)     return "📍 Position estimated";
        if (node.isLocationStale()) return "📍 Last known position";
        return "✓ Online — " + node.battery + "% battery";
    }

    // Legend entries for the map UI
    public static String[][] getLegend() {
        return new String[][] {
                { OWN_NODE_COLOR,    "You" },
                { ACTIVE_GPS_COLOR,  "Active node (GPS)" },
                { STALE_GPS_COLOR,   "Active node (old GPS)" },
                { ESTIMATED_COLOR,   "Estimated position" },
                { SOS_COLOR,         "SOS active" },
                { LOW_BATTERY_COLOR, "Low battery" },
                { OFFLINE_COLOR,     "Offline" },
        };
    }
}