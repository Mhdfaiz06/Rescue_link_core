package com.example.rescuelink.positioning;

import java.util.List;

public class TriangulationHelper {

    public static double rssiToDistance(int rssi) {
        double pathLossExponent = 2.5;
        double referenceRssi    = -69;
        double exponent = (referenceRssi - rssi) / (10.0 * pathLossExponent);
        return Math.pow(10, exponent);
    }

    public static EstimatedPosition triangulate(List<AnchorNode> anchors) {
        if (anchors == null || anchors.size() < 3) return null;

        double totalWeight = 0;
        double weightedLat = 0;
        double weightedLon = 0;

        for (AnchorNode anchor : anchors) {
            double distance = rssiToDistance(anchor.rssi);
            double weight   = 1.0 / (distance * distance);
            weightedLat    += anchor.latitude  * weight;
            weightedLon    += anchor.longitude * weight;
            totalWeight    += weight;
        }

        if (totalWeight == 0) return null;

        EstimatedPosition result = new EstimatedPosition();
        result.latitude  = weightedLat / totalWeight;
        result.longitude = weightedLon / totalWeight;

        float totalDist = 0;
        for (AnchorNode a : anchors) totalDist += rssiToDistance(a.rssi);
        result.accuracyMeters = totalDist / anchors.size();

        return result;
    }

    public static double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    public static class AnchorNode {
        public String meshId;
        public double latitude;
        public double longitude;
        public int    rssi;
    }

    public static class EstimatedPosition {
        public double latitude;
        public double longitude;
        public float  accuracyMeters;
    }
}