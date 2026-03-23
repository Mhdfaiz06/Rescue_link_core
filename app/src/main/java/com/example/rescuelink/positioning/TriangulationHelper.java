package com.example.rescuelink.positioning;

import java.util.List;

public class TriangulationHelper {

    // Convert RSSI (signal strength in dBm) to approximate distance in meters
    public static double rssiToDistance(int rssi) {
        double pathLossExponent = 2.5;
        double referenceRssi    = -69;
        double exponent = (referenceRssi - rssi) / (10.0 * pathLossExponent);
        return Math.pow(10, exponent);
    }

    // Estimate position from 3+ anchor nodes with known GPS and measured RSSI
    public static EstimatedPosition triangulate(List<AnchorNode> anchors) {
        if (anchors == null || anchors.size() < 3) return null;

        // Weighted centroid — closer nodes (stronger RSSI) get more weight
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
        result.accuracyMeters = estimateAccuracy(anchors);

        return result;
    }

    private static float estimateAccuracy(List<AnchorNode> anchors) {
        float totalDist = 0;
        for (AnchorNode a : anchors) {
            totalDist += rssiToDistance(a.rssi);
        }
        return totalDist / anchors.size();
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