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
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;

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

    private static final String OFFLINE_STYLE_URL = "https://demotiles.maplibre.org/style.json";

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

            // Add a Toast so you know it's trying to download
            Toast.makeText(activity, "Downloading Map Tiles...", Toast.LENGTH_SHORT).show();

            map.setStyle(OFFLINE_STYLE_URL, new org.maplibre.android.maps.Style.OnStyleLoaded() {
                @Override
                public void onStyleLoaded(@androidx.annotation.NonNull org.maplibre.android.maps.Style style) {
                    Toast.makeText(activity, "Map Loaded! Waiting for GPS...", Toast.LENGTH_LONG).show();

                    circleManager = new CircleManager(mapView, map, style);
                    nodeLineManager = new NodeLineManager(map);
                    isMapLoaded = true;

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
                }
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

    // [CHANGE APPLIED]: Field-level handler to prevent object creation loop
    private final Handler mapRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable mapRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (isMapVisible && mapLibreMap != null && circleManager != null) {
                refreshMap();
            }
            mapRefreshHandler.postDelayed(this, 3000);
        }
    };

    private void startDrawingLoop() {
        mapRefreshHandler.postDelayed(mapRefreshRunnable, 3000);
    }

    private void refreshMap() {
        try {
            if (locationManager == null || !locationManager.hasValidLocation()) return;

            double myLat = locationManager.getLatitude();
            double myLon = locationManager.getLongitude();

            if (myLat == 0.0 && myLon == 0.0) return;
            LatLng myPos = new LatLng(myLat, myLon);

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

            if (nodeStore == null) return;
            for (NodeLocationStore.NodeInfo node : nodeStore.getAllNodes()) {
                if (node.latitude == 0.0 && node.longitude == 0.0) continue;
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

            if (nodeLineManager != null) {
                nodeLineManager.updateLines(myLat, myLon, nodeStore.getAllNodes());
            }

            // [CHANGE APPLIED]: Prune dead nodes from the map
            Set<String> activeIds = new HashSet<>();
            activeIds.add("__me__");
            for (NodeLocationStore.NodeInfo node : nodeStore.getAllNodes()) {
                activeIds.add(node.meshId);
            }
            for (String id : new ArrayList<>(nodeCircles.keySet())) {
                if (!activeIds.contains(id)) {
                    Circle stale = nodeCircles.remove(id);
                    if (stale != null) circleManager.delete(stale);
                }
            }

        } catch (Exception e) {
            android.util.Log.e("MapUIManager", "Silent Map Crash Prevented: " + e.getMessage());
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