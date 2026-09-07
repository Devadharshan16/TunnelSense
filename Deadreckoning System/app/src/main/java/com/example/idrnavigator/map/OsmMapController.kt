package com.example.idrnavigator.map

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.idrnavigator.ui.NavigationUiState
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

enum class AppMapStyle(val title: String) {
    DARK_COCKPIT("Dark Cockpit"),
    STREET_MAP("Street Navigation"),
    SATELLITE("Satellite View")
}

// ESRI World Street Map (Free, no API key required, high-speed CDN, no 403 blocks)
class EsriStreetTileSource : OnlineTileSourceBase(
    "EsriStreetV2",
    0, 19, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/"),
    "© Esri, USGS, NOAA"
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val z = MapTileIndex.getZoom(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        return "$baseUrl$z/$y/$x"
    }
}

// ESRI World Imagery Satellite (Free, no API key required, high-res satellite photos)
class EsriSatelliteTileSource : OnlineTileSourceBase(
    "EsriSatelliteV2",
    0, 19, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "© Esri, Maxar, Earthstar Geographics"
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val z = MapTileIndex.getZoom(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        return "$baseUrl$z/$y/$x"
    }
}

// OpenStreetMap France (Free OSM community server, no API key, no 403 blocks)
val OSM_FRANCE_TILE_SOURCE = XYTileSource(
    "OsmFranceV2",
    0, 19, 256, ".png", arrayOf(
        "https://a.tile.openstreetmap.fr/osmfr/",
        "https://b.tile.openstreetmap.fr/osmfr/",
        "https://c.tile.openstreetmap.fr/osmfr/"
    ),
    "© OpenStreetMap contributors, OpenStreetMap France"
)

object MapTileSources {
    val ESRI_STREET = EsriStreetTileSource()
    val ESRI_SATELLITE = EsriSatelliteTileSource()
    val OSM_FRANCE = OSM_FRANCE_TILE_SOURCE

    // Cyberpunk/Cockpit dark color matrix: Inverts lights and keeps roads/contrasts high
    val DARK_COLOR_FILTER = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                -0.80f,  0.00f,  0.00f, 0.0f, 220f,
                 0.00f, -0.80f,  0.00f, 0.0f, 220f,
                 0.00f,  0.00f, -0.75f, 0.0f, 230f,
                 0.00f,  0.00f,  0.00f, 1.0f,   0f
            )
        )
    )

    fun getTileSource(style: AppMapStyle): ITileSource {
        return when (style) {
            AppMapStyle.DARK_COCKPIT -> ESRI_STREET
            AppMapStyle.STREET_MAP -> ESRI_STREET
            AppMapStyle.SATELLITE -> ESRI_SATELLITE
        }
    }
}

@Composable
fun OsmMapController(
    uiState: NavigationUiState,
    modifier: Modifier = Modifier,
    mapStyle: AppMapStyle = AppMapStyle.DARK_COCKPIT,
    followVehicle: Boolean = true,
    onUserPan: () -> Unit = {},
    onMapReady: (MapView) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasInitialCentered by remember { mutableStateOf(false) }
    var lastHistorySize by remember { mutableIntStateOf(0) }

    val polyline = remember {
        Polyline().apply {
            outlinePaint.color = Color.parseColor("#4FD8E8") // VehicleMarkerAccent cyan
            outlinePaint.strokeWidth = 8f
            outlinePaint.isAntiAlias = true
        }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(MapTileSources.getTileSource(mapStyle))
            setMultiTouchControls(true)
            isTilesScaledToDpi = true // High-DPI scaling: fast loading, sharp labels, 70% less network usage
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(17.5)
            setUseDataConnection(true)
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false

            // Dark placeholder background while tiles stream in
            overlayManager.tilesOverlay.loadingBackgroundColor = Color.parseColor("#12161A")
            overlayManager.tilesOverlay.loadingLineColor = Color.parseColor("#1E242B")

            // Apply dark filter if initially Dark Cockpit
            if (mapStyle == AppMapStyle.DARK_COCKPIT) {
                overlayManager.tilesOverlay.setColorFilter(MapTileSources.DARK_COLOR_FILTER)
            } else {
                overlayManager.tilesOverlay.setColorFilter(null)
            }

            overlays.add(polyline)
        }
    }

    // Switch tile source and color filter dynamically when mapStyle changes
    LaunchedEffect(mapStyle) {
        val newSource = MapTileSources.getTileSource(mapStyle)
        if (mapView.tileProvider.tileSource != newSource) {
            mapView.setTileSource(newSource)
        }
        if (mapStyle == AppMapStyle.DARK_COCKPIT) {
            mapView.overlayManager.tilesOverlay.setColorFilter(MapTileSources.DARK_COLOR_FILTER)
        } else {
            mapView.overlayManager.tilesOverlay.setColorFilter(null)
        }
        mapView.invalidate()
    }

    val vehicleMarker = remember {
        VehicleMarker(mapView).also {
            mapView.overlays.add(it)
        }
    }

    DisposableEffect(mapView) {
        onMapReady(mapView)
        // Detect touch to release camera auto-follow smoothly
        mapView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN) {
                onUserPan()
            }
            false
        }
        onDispose { }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            if (uiState.hasFix || (uiState.gpsData.lat != 0.0 && uiState.gpsData.lon != 0.0)) {
                val currentPoint = GeoPoint(uiState.gpsData.lat, uiState.gpsData.lon)

                // First time centering
                if (!hasInitialCentered) {
                    map.controller.setCenter(currentPoint)
                    map.controller.setZoom(17.5)
                    hasInitialCentered = true
                } else if (followVehicle) {
                    // Update center directly without thrashing animation queues
                    map.controller.setCenter(currentPoint)
                }

                // Update vehicle marker directly
                vehicleMarker.updatePositionAndHeading(currentPoint, uiState.headingDeg.toFloat())
                vehicleMarker.updateState(uiState.gnssState)

                // Update track only when list size changes to avoid redundant CPU work
                if (uiState.locationHistory.size != lastHistorySize) {
                    polyline.setPoints(uiState.locationHistory)
                    lastHistorySize = uiState.locationHistory.size
                }

                map.invalidate()
            }
        }
    )
}
