package com.example.rescuelink.map;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.example.rescuelink.R;
import com.example.rescuelink.location.NodeLocationStore;
import com.example.rescuelink.location.RescueLinkLocationManager;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.plugins.annotation.Circle;
import org.maplibre.android.plugins.annotation.CircleManager;
import org.maplibre.android.plugins.annotation.CircleOptions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MapUIManager {

    private final Activity activity;
    private final NodeLocationStore nodeStore;
    private final RescueLinkLocationManager locationManager;

    private MapView mapView;
    private View mapContainer;
    private Button btnToggleMap;
    private MapLibreMap mapLibreMap;

    // --- DRAWING MANAGERS ---
    private CircleManager circleManager;
    private NodeLineManager nodeLineManager; // <--- Claude's new manager

    private final Map<String, Circle> nodeCircles = new ConcurrentHashMap<>();
    private boolean isMapVisible = false;
    private boolean isMapLoaded = false;

    private static final String OFFLINE_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";

    public MapUIManager(Activity activity, NodeLocationStore nodeStore, RescueLinkLocationManager locManager) {
        this.activity = activity;
        this.nodeStore = nodeStore;
        this.locationManager = locManager;
    }

    public void init(Bundle savedInstanceState) {
        mapContainer = activity.findViewById(R.id.mapContainer);
        mapView = activity.findViewById(R.id.mapView);
        btnToggleMap = activity.findViewById(R.id.btnToggleMap);

        mapContainer.post(() -> mapContainer.setTranslationY(mapContainer.getHeight()));
        mapContainer.setVisibility(View.VISIBLE);

        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(map -> {
            mapLibreMap = map;
            map.setStyle(OFFLINE_STYLE_URL, style -> {

                // Initialize both managers
                circleManager = new CircleManager(mapView, map, style);
                nodeLineManager = new NodeLineManager(map); // <--- Setup the lines

                isMapLoaded = true;

                // Tap a dot to see battery/status
                circleManager.addClickListener(circle -> {
                    for (NodeLocationStore.NodeInfo node : nodeStore.getAllNodes()) {
                        if (node.latitude == circle.getLatLng().getLatitude() &&
                                node.longitude == circle.getLatLng().getLongitude()) {
                            String status = MapStyleHelper.getNodeStatusText(node);
                            Toast.makeText(activity, "Node " + node.meshId + "\n" + status, Toast.LENGTH_LONG).show();
                            return true;
                        }
                    }
                    return false;
                });

                startDrawingLoop();
                // downloadOfflineMapRegion(); // Uncomment if you have the offline logic ready
            });
        });

        btnToggleMap.setOnClickListener(v -> toggleMapVisibility());
    }

    private void toggleMapVisibility() {
        if (!isMapLoaded) return;
        float targetY = isMapVisible ? mapContainer.getHeight() : 0;
        mapContainer.animate()
                .translationY(targetY)
                .setDuration(300)
                .withStartAction(() -> { if (!isMapVisible) btnToggleMap.setText("🔽 Close Map"); })
                .withEndAction(() -> {
                    if (isMapVisible) btnToggleMap.setText("🗺 Map");
                    isMapVisible = !isMapVisible;
                }).start();
    }

    private void startDrawingLoop() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isMapVisible && mapLibreMap != null && circleManager != null) {
                    refreshMap();
                }
                new Handler(Looper.getMainLooper()).postDelayed(this, 3000);
            }
        }, 3000);
    }

    private void refreshMap() {
        if (locationManager == null || !locationManager.hasValidLocation()) return;

        double myLat = locationManager.getLatitude();
        double myLon = locationManager.getLongitude();
        LatLng myPos = new LatLng(myLat, myLon);

        // 1. Draw ourselves (Blue dot)
        Circle myCircle = nodeCircles.get("__me__");
        if (myCircle != null) {
            myCircle.setLatLng(myPos);
            circleManager.update(myCircle);
        } else {
            CircleOptions options = new CircleOptions()
                    .withLatLng(myPos).withCircleRadius(10f)
                    .withCircleColor(MapStyleHelper.OWN_NODE_COLOR)
                    .withCircleStrokeWidth(2f).withCircleStrokeColor("#FFFFFF");
            nodeCircles.put("__me__", circleManager.create(options));
            mapLibreMap.setCameraPosition(new CameraPosition.Builder().target(myPos).zoom(15).build());
        }

        // 2. Update other nodes' dots
        if (nodeStore == null) return;
        for (NodeLocationStore.NodeInfo node : nodeStore.getAllNodes()) {
            if (node.latitude == 0 && node.longitude == 0) continue;
            LatLng theirPos = new LatLng(node.latitude, node.longitude);

            Circle existingNode = nodeCircles.get(node.meshId);
            if (existingNode != null) {
                existingNode.setLatLng(theirPos);
                existingNode.setCircleColor(MapStyleHelper.getNodeColor(node));
                existingNode.setCircleRadius(node.isSosActive ? 16f : 10f);
                circleManager.update(existingNode);
            } else {
                CircleOptions options = new CircleOptions()
                        .withLatLng(theirPos).withCircleRadius(node.isSosActive ? 16f : 10f)
                        .withCircleColor(MapStyleHelper.getNodeColor(node))
                        .withCircleStrokeWidth(1.5f).withCircleStrokeColor("#FFFFFF");
                nodeCircles.put(node.meshId, circleManager.create(options));
            }
        }

        // 3. Update the Lines and Distances via GeoJSON
        if (nodeLineManager != null) {
            nodeLineManager.updateLines(myLat, myLon, nodeStore.getAllNodes());
        }
    }

    // --- Lifecycle Pass-throughs ---
    public void onStart() { if (mapView != null) mapView.onStart(); }
    public void onResume() { if (mapView != null) mapView.onResume(); }
    public void onPause() { if (mapView != null) mapView.onPause(); }
    public void onStop() { if (mapView != null) mapView.onStop(); }
    public void onDestroy() { if (mapView != null) mapView.onDestroy(); }
    public void onSaveInstanceState(Bundle outState) { if (mapView != null) mapView.onSaveInstanceState(outState); }
}