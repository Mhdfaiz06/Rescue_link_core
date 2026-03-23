package com.example.rescuelink.map;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.example.rescuelink.R;
import com.example.rescuelink.location.NodeLocationStore;
import com.example.rescuelink.location.NodeLocationStore.NodeInfo;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.plugins.annotation.CircleManager;
import org.maplibre.android.plugins.annotation.CircleOptions;
import org.maplibre.android.plugins.annotation.Circle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MapActivity extends AppCompatActivity {

    private MapView mapView;
    private MapLibreMap mapLibreMap;
    private CircleManager circleManager;

    public static NodeLocationStore nodeStore;
    public static double myLatitude  = 0;
    public static double myLongitude = 0;

    private final Map<String, Circle> nodeCircles = new HashMap<>();
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private static final String OFFLINE_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MapLibre.getInstance(this);
        setContentView(R.layout.activity_map);

        mapView = findViewById(R.id.mapView);
        mapView.onCreate(savedInstanceState);

        mapView.getMapAsync(map -> {
            mapLibreMap = map;
            map.setStyle(OFFLINE_STYLE_URL, style -> {
                if (myLatitude != 0) {
                    map.setCameraPosition(new CameraPosition.Builder()
                            .target(new LatLng(myLatitude, myLongitude))
                            .zoom(15)
                            .build());
                }
                circleManager = new CircleManager(mapView, map, style);
                refreshNodeDots();
                startAutoRefresh();
            });
        });
    }

    private void startAutoRefresh() {
        refreshHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshNodeDots();
                refreshHandler.postDelayed(this, 3000);
            }
        }, 3000);
    }

    private void refreshNodeDots() {
        if (circleManager == null || nodeStore == null) return;

        if (myLatitude != 0) drawOwnPosition();

        List<NodeInfo> nodes = nodeStore.getAllNodes();
        for (NodeInfo node : nodes) {
            if (node.latitude == 0 && node.longitude == 0) continue;
            drawNodeDot(node);
        }
    }

    private void drawOwnPosition() {
        CircleOptions options = new CircleOptions()
                .withLatLng(new LatLng(myLatitude, myLongitude))
                .withCircleRadius(10f)
                .withCircleColor(MapStyleHelper.OWN_NODE_COLOR)
                .withCircleStrokeWidth(2f)
                .withCircleStrokeColor("#FFFFFF");

        Circle existing = nodeCircles.get("__me__");
        if (existing != null) {
            existing.setLatLng(new LatLng(myLatitude, myLongitude));
            circleManager.update(existing);
        } else {
            Circle circle = circleManager.create(options);
            nodeCircles.put("__me__", circle);
        }
    }

    private void drawNodeDot(NodeInfo node) {
        String color = MapStyleHelper.getNodeColor(node);
        float radius = node.isSosActive ? 16f : 10f;
        LatLng position = new LatLng(node.latitude, node.longitude);

        Circle existing = nodeCircles.get(node.meshId);
        if (existing != null) {
            existing.setLatLng(position);
            existing.setCircleColor(color);
            existing.setCircleRadius(radius);
            circleManager.update(existing);
        } else {
            CircleOptions options = new CircleOptions()
                    .withLatLng(position)
                    .withCircleRadius(radius)
                    .withCircleColor(color)
                    .withCircleStrokeWidth(1.5f)
                    .withCircleStrokeColor("#FFFFFF")
                    .withCircleOpacity(node.isOnline() ? 1.0f : 0.4f);
            Circle circle = circleManager.create(options);
            nodeCircles.put(node.meshId, circle);
        }
    }

    @Override protected void onStart()   { super.onStart();   mapView.onStart(); }
    @Override protected void onResume()  { super.onResume();  mapView.onResume(); }
    @Override protected void onPause()   { super.onPause();   mapView.onPause(); }
    @Override protected void onStop()    { super.onStop();    mapView.onStop(); refreshHandler.removeCallbacksAndMessages(null); }
    @Override protected void onDestroy() { super.onDestroy(); mapView.onDestroy(); }
    @Override public void onLowMemory() { super.onLowMemory(); mapView.onLowMemory(); }
    @Override protected void onSaveInstanceState(Bundle outState) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState); }
}