package com.example.rescuelink.map;

import android.location.Location;

import com.example.rescuelink.location.NodeLocationStore;

import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;

import java.util.List;

public class NodeLineManager {

    private static final String SOURCE_ID = "node-lines-source";
    private static final String LINE_LAYER_ID  = "node-lines-layer";
    private static final String LABEL_LAYER_ID = "node-distance-labels";

    private final MapLibreMap map;
    private GeoJsonSource lineSource;

    public NodeLineManager(MapLibreMap map) {
        this.map = map;
        setupLayers();
    }

    private void setupLayers() {
        map.getStyle(style -> {
            // GeoJSON source — we update this with fresh data every refresh cycle
            lineSource = new GeoJsonSource(SOURCE_ID, buildEmptyGeoJson());
            style.addSource(lineSource);

            // Line layer — thin white line, non-distracting
            LineLayer lineLayer = new LineLayer(LINE_LAYER_ID, SOURCE_ID);
            lineLayer.setProperties(
                    PropertyFactory.lineColor("#4A90E2"), // Soft Blue
                    PropertyFactory.lineWidth(2.0f),
                    PropertyFactory.lineOpacity(0.5f)
            );
            style.addLayer(lineLayer);

            // Distance label layer — small text at midpoint of each line
            SymbolLayer labelLayer = new SymbolLayer(LABEL_LAYER_ID, SOURCE_ID);
            labelLayer.setProperties(
                    PropertyFactory.textField(org.maplibre.android.style.expressions.Expression.get("distance")),
                    //PropertyFactory.textField("{distance}"),
                    PropertyFactory.textSize(14f),
                    PropertyFactory.textColor("#FFFFFF"),
                    PropertyFactory.textHaloColor("#000000"),
                    PropertyFactory.textHaloWidth(1.5f),
                    PropertyFactory.textOffset(new Float[]{0f, -0.7f})
            );
            style.addLayer(labelLayer);
        });
    }

    // Call this every time node positions update
    public void updateLines(double myLat, double myLon, List<NodeLocationStore.NodeInfo> nodes) {
        if (lineSource == null) return;

        StringBuilder geoJson = new StringBuilder();
        geoJson.append("{\"type\":\"FeatureCollection\",\"features\":[");

        boolean first = true;
        float[] results = new float[1]; // Used for distance calculation

        for (NodeLocationStore.NodeInfo node : nodes) {
            if (node.latitude == 0 && node.longitude == 0) continue;
            if (!node.isOnline()) continue;

            // Calculate highly accurate distance
            Location.distanceBetween(myLat, myLon, node.latitude, node.longitude, results);
            double distanceMeters = results[0];

            String distanceLabel = formatDistance(distanceMeters);

            // Midpoint for label placement
            double midLat = (myLat + node.latitude) / 2.0;
            double midLon = (myLon + node.longitude) / 2.0;

            if (!first) geoJson.append(",");
            first = false;

            // Line feature
            geoJson.append("{\"type\":\"Feature\",")
                    .append("\"properties\":{\"distance\":\"")
                    .append(distanceLabel).append("\"},")
                    .append("\"geometry\":{\"type\":\"LineString\",")
                    .append("\"coordinates\":[[")
                    .append(myLon).append(",").append(myLat).append("],[")
                    .append(node.longitude).append(",").append(node.latitude)
                    .append("]]}}");

            // Label point at midpoint
            geoJson.append(",{\"type\":\"Feature\",")
                    .append("\"properties\":{\"distance\":\"")
                    .append(distanceLabel).append("\"},")
                    .append("\"geometry\":{\"type\":\"Point\",")
                    .append("\"coordinates\":[")
                    .append(midLon).append(",").append(midLat)
                    .append("]}}");
        }

        geoJson.append("]}");
        lineSource.setGeoJson(geoJson.toString());
    }

    private String formatDistance(double meters) {
        if (meters < 1000) {
            return Math.round(meters) + "m";
        } else {
            return String.format("%.1fkm", meters / 1000.0);
        }
    }

    private String buildEmptyGeoJson() {
        return "{\"type\":\"FeatureCollection\",\"features\":[]}";
    }
}