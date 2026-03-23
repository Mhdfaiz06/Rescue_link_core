package com.example.rescuelink.map;

import com.example.rescuelink.location.NodeLocationStore.NodeInfo;

public class MapStyleHelper {

    public static final String OWN_NODE_COLOR      = "#1565C0";
    public static final String ACTIVE_GPS_COLOR    = "#2E7D32";
    public static final String STALE_GPS_COLOR     = "#F9A825";
    public static final String ESTIMATED_COLOR     = "#6A1B9A";
    public static final String OFFLINE_COLOR       = "#9E9E9E";
    public static final String SOS_COLOR           = "#C62828";
    public static final String LOW_BATTERY_COLOR   = "#E65100";

    public static String getNodeColor(NodeInfo node) {
        if (!node.isOnline())         return OFFLINE_COLOR;
        if (node.isSosActive)         return SOS_COLOR;
        if (node.battery < 20)        return LOW_BATTERY_COLOR;
        if (!node.hasDirectGps)       return ESTIMATED_COLOR;
        if (node.isLocationStale())   return STALE_GPS_COLOR;
        return ACTIVE_GPS_COLOR;
    }
}