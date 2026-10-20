package com.protectmyphone.app.ui

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.gson.JsonObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val MARKER_SOURCE_ID = "family-location-points"
private const val REMOTE_LAYER_ID = "family-device-markers"
private const val LOCAL_LAYER_ID = "this-phone-marker"

/** A compact MapLibre map. Coordinates are supplied by the local app; only base-map tiles are remote. */
@Composable
fun FamilyLocationMap(locations: List<DeviceLocationPin>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember(context) {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(Bundle()) }
    }
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var source by remember(mapView) { mutableStateOf<GeoJsonSource?>(null) }
    var mapLoaded by remember(mapView) { mutableStateOf(false) }

    DisposableEffect(mapView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    Box(modifier) {
        AndroidView(
            factory = {
                mapView.apply {
                    getMapAsync { readyMap ->
                        map = readyMap
                        // Public community tile sets stop at city-level detail. Keep very close
                        // phone positions from zooming past available vector tiles.
                        readyMap.setMaxZoomPreference(16.0)
                        readyMap.setStyle(MAP_STYLE_URL) { style ->
                            val markerSource = GeoJsonSource(MARKER_SOURCE_ID, createFeatures(locations))
                            style.addSource(markerSource)
                            style.addLayer(
                                CircleLayer(REMOTE_LAYER_ID, MARKER_SOURCE_ID)
                                    .withFilter(Expression.eq(Expression.get("isThisPhone"), Expression.literal(false)))
                                    .withProperties(
                                        // Draw the paired phone as the outer ring so it remains visible
                                        // when both devices report nearly identical coordinates.
                                        circleRadius(11f),
                                        circleColor(AndroidColor.rgb(23, 63, 59)),
                                        circleStrokeColor(AndroidColor.WHITE),
                                        circleStrokeWidth(2.5f),
                                    ),
                            )
                            style.addLayer(
                                CircleLayer(LOCAL_LAYER_ID, MARKER_SOURCE_ID)
                                    .withFilter(Expression.eq(Expression.get("isThisPhone"), Expression.literal(true)))
                                    .withProperties(
                                        circleRadius(6f),
                                        circleColor(AndroidColor.rgb(185, 73, 62)),
                                        circleStrokeColor(AndroidColor.WHITE),
                                        circleStrokeWidth(2.5f),
                                    ),
                            )
                            source = markerSource
                            mapLoaded = true
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        LaunchedEffect(locations, source, map) {
            val activeSource = source ?: return@LaunchedEffect
            val activeMap = map ?: return@LaunchedEffect
            activeSource.setGeoJson(createFeatures(locations))
            try {
                if (locations.isEmpty()) {
                    activeMap.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(20.5937, 78.9629))
                        .zoom(4.5)
                        .build()
                } else if (locations.size == 1) {
                    val point = locations.first()
                    activeMap.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 13.0),
                        250,
                    )
                } else {
                    val boundsBuilder = org.maplibre.android.geometry.LatLngBounds.Builder()
                    locations.forEach { boundsBuilder.include(LatLng(it.latitude, it.longitude)) }
                    activeMap.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 36), 250)
                }
            } catch (_: RuntimeException) {
                // MapLibre can reject a camera update briefly while the view is measuring.
            }
        }

        if (!mapLoaded) {
            Surface(
                modifier = Modifier.padding(12.dp).align(Alignment.BottomStart),
                color = Color.White.copy(alpha = 0.94f),
            ) {
                Text("Loading map · internet needed", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), fontSize = 9.sp, color = Color(0xFF687877))
            }
        }
    }
}

private fun createFeatures(locations: List<DeviceLocationPin>): FeatureCollection =
    FeatureCollection.fromFeatures(
        locations.map { location ->
            val properties = JsonObject().apply {
                addProperty("isThisPhone", location.isThisPhone)
                addProperty("name", location.name)
            }
            Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude), properties)
        }.toTypedArray(),
    )
