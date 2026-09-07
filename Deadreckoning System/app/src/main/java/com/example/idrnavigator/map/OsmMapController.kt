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

private const val MIN_MAP_ZOOM = 3.0
// Level 18 can also return unavailable tiles in some areas, so keep the reliable ceiling at 17.
private const val MAX_MAP_ZOOM = 17.0

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
    destination: GeoPoint? = null,
    routePoints: List<GeoPoint> = emptyList(),
    modifier: Modifier = Modifier,
    mapStyle: AppMapStyle = AppMapStyle.DARK_COCKPIT,
    followVehicle: Boolean = true,
    onUserPan: () -> Unit = {},
    onMapTap: (GeoPoint) -> Unit = {},
    onMapReady: (MapView) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasInitialCentered by remember { mutableStateOf(false) }
    var lastHistorySize by remember { mutableIntStateOf(0) }
    var lastClassicalSize by remember { mutableIntStateOf(0) }
    var lastGpsSize by remember { mutableIntStateOf(0) }
    var lastHistoryLastPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var lastClassicalLastPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var lastGpsLastPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var lastCenteredPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var lastMapOrientation by remember { mutableStateOf(0f) }
    var lastDestination by remember { mutableStateOf<GeoPoint?>(null) }
    var lastRoutePoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }

    val polyline = remember {
        Polyline().apply {
            outlinePaint.color = Color.parseColor("#4FD8E8") // AI path — cyan
            outlinePaint.strokeWidth = 8f
            outlinePaint.isAntiAlias = true
        }
    }

    val classicalPolyline = remember {
        Polyline().apply {
            outlinePaint.color = Color.parseColor("#FF8C42") // Classical path — orange
            outlinePaint.strokeWidth = 6f
            outlinePaint.isAntiAlias = true
            outlinePaint.alpha = 180
        }
    }

    val gpsPolyline = remember {
        Polyline().apply {
            outlinePaint.color = Color.parseColor("#4CAF50") // GPS ground truth — green
            outlinePaint.strokeWidth = 5f
            outlinePaint.isAntiAlias = true
            outlinePaint.alpha = 160
        }
    }

    val routePolyline = remember {
        Polyline().apply {
            outlinePaint.color = routeColorFor(mapStyle)
            outlinePaint.strokeWidth = 14f
            outlinePaint.isAntiAlias = true
            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(24f, 10f), 0f)
        }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(MapTileSources.getTileSource(mapStyle))
            setMultiTouchControls(true)
            isTilesScaledToDpi = true // High-DPI scaling: fast loading, sharp labels, 70% less network usage
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setMinZoomLevel(MIN_MAP_ZOOM)
            setMaxZoomLevel(MAX_MAP_ZOOM)
            controller.setZoom(MAX_MAP_ZOOM)
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

            overlays.add(gpsPolyline)
            overlays.add(classicalPolyline)
            overlays.add(polyline)
            overlays.add(routePolyline)
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
        routePolyline.outlinePaint.color = routeColorFor(mapStyle)
        mapView.invalidate()
    }

    val vehicleMarker = remember {
        VehicleMarker(mapView).also {
            mapView.overlays.add(it)
        }
    }
    val destinationMarker = remember { DestinationMarker(mapView) }

    DisposableEffect(mapView) {
        onMapReady(mapView)
        val gestureDetector = android.view.GestureDetector(
            context,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                    val tapped = mapView.projection.fromPixels(event.x.toInt(), event.y.toInt())
                    onMapTap(GeoPoint(tapped.latitude, tapped.longitude))
                    return false
                }
            }
        )
        // Detect touch to release camera auto-follow smoothly
        mapView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN) {
                onUserPan()
            }
            gestureDetector.onTouchEvent(event)
            false
        }
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
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
                var mapChanged = false

                // First time centering
                if (!hasInitialCentered) {
                    map.controller.setCenter(currentPoint)
                    map.controller.setZoom(MAX_MAP_ZOOM)
                    hasInitialCentered = true
                    lastCenteredPoint = currentPoint
                    mapChanged = true
                } else if (followVehicle &&
                    (lastCenteredPoint == null || lastCenteredPoint!!.distanceToAsDouble(currentPoint) >= 1.0)
                ) {
                    map.controller.setCenter(currentPoint)
                    lastCenteredPoint = currentPoint
                    mapChanged = true
                }

                // Course-Up mode: rotate map so heading is "up"
                // North-Up mode: map stays fixed at 0 degrees
                val targetOrientation = if (uiState.isCourseUpMode) {
                    -uiState.headingDeg.toFloat()
                } else {
                    0f
                }
                if (targetOrientation != lastMapOrientation) {
                    map.mapOrientation = targetOrientation
                    lastMapOrientation = targetOrientation
                    mapChanged = true
                }

                // Update vehicle marker directly
                mapChanged = vehicleMarker.updatePositionAndHeading(
                    currentPoint,
                    if (uiState.isCourseUpMode) 0f else uiState.headingDeg.toFloat()
                ) || mapChanged
                vehicleMarker.updateState(uiState.gnssState)

                // Update AI track (primary cyan polyline)
                val historyLastPoint = uiState.locationHistory.lastOrNull()
                if (uiState.locationHistory.size != lastHistorySize ||
                    historyLastPoint?.latitude != lastHistoryLastPoint?.latitude ||
                    historyLastPoint?.longitude != lastHistoryLastPoint?.longitude
                ) {
                    polyline.setPoints(uiState.locationHistory)
                    lastHistorySize = uiState.locationHistory.size
                    lastHistoryLastPoint = historyLastPoint
                    mapChanged = true
                }

                // Update Classical track (orange polyline)
                val classicalLastPoint = uiState.classicalPathHistory.lastOrNull()
                if (uiState.classicalPathHistory.size != lastClassicalSize ||
                    classicalLastPoint?.latitude != lastClassicalLastPoint?.latitude ||
                    classicalLastPoint?.longitude != lastClassicalLastPoint?.longitude
                ) {
                    classicalPolyline.setPoints(uiState.classicalPathHistory)
                    lastClassicalSize = uiState.classicalPathHistory.size
                    lastClassicalLastPoint = classicalLastPoint
                    mapChanged = true
                }

                // Update GPS ground truth track (green polyline)
                val gpsLastPoint = uiState.gpsPathHistory.lastOrNull()
                if (uiState.gpsPathHistory.size != lastGpsSize ||
                    gpsLastPoint?.latitude != lastGpsLastPoint?.latitude ||
                    gpsLastPoint?.longitude != lastGpsLastPoint?.longitude
                ) {
                    gpsPolyline.setPoints(uiState.gpsPathHistory)
                    lastGpsSize = uiState.gpsPathHistory.size
                    lastGpsLastPoint = gpsLastPoint
                    mapChanged = true
                }

                if (mapChanged) map.invalidate()
            }

            var routeChanged = false
            if (destination != lastDestination) {
                routeChanged = true
                lastDestination = destination
            }
            if (routePoints !== lastRoutePoints) {
                routeChanged = true
                lastRoutePoints = routePoints
            }

            if (routeChanged && destination != null) {
                destinationMarker.setDestination(destination)
                if (!map.overlays.contains(destinationMarker)) map.overlays.add(destinationMarker)
            } else if (routeChanged) {
                destinationMarker.setVisible(false)
                map.overlays.remove(destinationMarker)
            }

            if (routeChanged) {
                routePolyline.setPoints(routePoints)
                if (routePoints.isNotEmpty()) {
                    if (!map.overlays.contains(routePolyline)) map.overlays.add(routePolyline)
                } else {
                    map.overlays.remove(routePolyline)
                }
                map.invalidate()
            }
        }
    )
}

private fun routeColorFor(mapStyle: AppMapStyle): Int = when (mapStyle) {
    AppMapStyle.STREET_MAP -> Color.rgb(28, 34, 42)
    AppMapStyle.SATELLITE -> Color.rgb(255, 193, 7)
    AppMapStyle.DARK_COCKPIT -> Color.rgb(79, 216, 232)
}
