package com.example.idrnavigator.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.dp
import com.example.idrnavigator.ui.theme.*
import com.example.idrnavigator.fusion.GnssDeficitHandler
import com.example.idrnavigator.fusion.GnssState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.widget.Toast
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import com.example.idrnavigator.map.AppMapStyle
import com.example.idrnavigator.map.GeocodingResult
import com.example.idrnavigator.map.GeocodingService
import com.example.idrnavigator.map.OsmMapController
import org.osmdroid.util.GeoPoint

@Composable
fun NavigationHudScreen(
    viewModel: NavigationViewModel,
    mapStyle: AppMapStyle,
    onMapStyleChanged: (AppMapStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState(initial = NavigationUiState())
    val destination by viewModel.destination.collectAsState()
    val routeState by viewModel.routeState.collectAsState()
    var currentMapView by remember { mutableStateOf<org.osmdroid.views.MapView?>(null) }
    var followVehicle by remember { mutableStateOf(true) }
    var areMapControlsExpanded by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GeocodingResult>>(emptyList()) }
    var searchMessage by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var isSearchFocused by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val geocodingService = remember { GeocodingService() }
    val searchScope = rememberCoroutineScope()

    BackHandler(enabled = isSearchFocused || searchText.isNotEmpty() || searchResults.isNotEmpty() || searchMessage != null) {
        if (isSearchFocused) {
            focusManager.clearFocus()
            isSearchFocused = false
        } else {
            searchText = ""
            searchResults = emptyList()
            searchMessage = null
        }
    }

    // Mode-duration counter: tracks how long current GNSS state has been active
    var lastTrackedState by remember { mutableStateOf(state.gnssState) }
    var stateStartTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var currentClockMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(state.gnssState) {
        if (state.gnssState != lastTrackedState) {
            lastTrackedState = state.gnssState
            stateStartTimeMs = System.currentTimeMillis()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            currentClockMs = System.currentTimeMillis()
        }
    }

    val stateElapsedSec = ((currentClockMs - stateStartTimeMs) / 1000L).coerceAtLeast(0L)
    val stateMinutes = stateElapsedSec / 60
    val stateSeconds = stateElapsedSec % 60
    val modeDurationStr = String.format(Locale.US, "%s: %02d:%02d", state.gnssState.name, stateMinutes, stateSeconds)
    val topStackPadding = if (searchResults.isNotEmpty() || isSearching || searchMessage != null) 280.dp else 76.dp

    val targetPillColor = when (state.gnssState) {
        GnssState.GNSS_ACTIVE   -> GnssActive       // #4FD8E8 Cyan
        GnssState.TRANSITIONING -> GnssDegraded     // #F5A623 Amber
        GnssState.INS_ONLY      -> InsDeadReckoning // #7C5CFF Violet
    }

    val animatedPillColor by animateColorAsState(
        targetValue = targetPillColor,
        animationSpec = tween(durationMillis = 400),
        label = "pillGnssColor"
    )

    val gnssLabel = when (state.gnssState) {
        GnssState.GNSS_ACTIVE   -> if (state.gpsAccuracy > 20f) "GNSS DEGRADED" else "GNSS LOCKED"
        GnssState.TRANSITIONING -> "TRANSITIONING"
        GnssState.INS_ONLY      -> "INS ONLY"
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // The live Map
        OsmMapController(
            uiState = state,
            destination = destination,
            routePoints = routeState.points,
            modifier = Modifier.fillMaxSize(),
            mapStyle = mapStyle,
            followVehicle = followVehicle,
            onUserPan = { followVehicle = false },
            onMapTap = { viewModel.setDestination(it) },
            onMapReady = { currentMapView = it }
        )

        OutlinedTextField(
            value = searchText,
            onValueChange = {
                searchText = it
                searchMessage = null
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .fillMaxWidth(0.88f)
                .onFocusChanged { isSearchFocused = it.isFocused },
            shape = RoundedCornerShape(18.dp),
            singleLine = true,
            placeholder = { Text("Search destination") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search destination") },
            trailingIcon = {
                if (searchText.isNotEmpty()) {
                    IconButton(onClick = {
                        searchText = ""
                        searchResults = emptyList()
                        searchMessage = null
                        focusManager.clearFocus()
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                searchScope.launch {
                    focusManager.clearFocus()
                    isSearching = true
                    searchMessage = null
                    searchResults = geocodingService.search(searchText).fold(
                        onSuccess = { places ->
                            if (places.isEmpty()) searchMessage = "No destinations found"
                            places
                        },
                        onFailure = {
                            searchMessage = "Search unavailable offline"
                            emptyList()
                        }
                    )
                    isSearching = false
                }
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CockpitSurface.copy(alpha = 0.95f),
                unfocusedContainerColor = CockpitSurface.copy(alpha = 0.9f),
                focusedBorderColor = VehicleMarkerAccent,
                unfocusedBorderColor = CockpitDivider
            )
        )

        if (searchResults.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .fillMaxWidth(0.88f)
                    .background(CockpitSurface.copy(alpha = 0.98f), RoundedCornerShape(16.dp))
            ) {
                searchResults.forEachIndexed { index, result ->
                    TextButton(
                        onClick = {
                            val point = GeoPoint(result.latitude, result.longitude)
                            viewModel.setDestination(point)
                            currentMapView?.controller?.animateTo(point)
                            searchResults = emptyList()
                            searchText = result.name.substringBefore(",")
                            focusManager.clearFocus()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = VehicleMarkerAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                result.name,
                                maxLines = 2,
                                color = CockpitPrimaryText,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    if (index < searchResults.lastIndex) {
                        HorizontalDivider(color = CockpitDivider.copy(alpha = 0.7f))
                    }
                }
            }
        } else if (isSearching || searchMessage != null) {
            Text(
                text = if (isSearching) "Searching..." else searchMessage.orEmpty(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .background(CockpitSurface.copy(alpha = 0.95f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = CockpitSecondaryText,
                style = MaterialTheme.typography.labelMedium
            )
        }

        // Top Floating Pill
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topStackPadding)
                .fillMaxWidth(0.94f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                .background(
                    color = CockpitSurface.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(animatedPillColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = gnssLabel,
                    color = CockpitPrimaryText,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "🛰 ${state.satelliteCount} Sats",
                    color = if (state.satelliteCount > 0) CockpitPrimaryText else CockpitSecondaryText,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                    color = CockpitSecondaryText,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Dead Reckoning Model Toggle Chip (AI TinyTCN vs Classical)
            Surface(
                onClick = {
                    viewModel.toggleDeadReckoningMode()
                    val newMode = if (state.deadReckoningMode == com.example.idrnavigator.fusion.DeadReckoningMode.AI_TCN) "Classical (Strapdown)" else "AI (TinyTCN ONNX)"
                    Toast.makeText(context, "Switched to: $newMode", Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(16.dp),
                color = CockpitSurface.copy(alpha = 0.85f),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (state.deadReckoningMode == com.example.idrnavigator.fusion.DeadReckoningMode.AI_TCN) InsDeadReckoning else CockpitSecondaryText,
                                CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (state.deadReckoningMode == com.example.idrnavigator.fusion.DeadReckoningMode.AI_TCN && state.isAiModelLoaded) {
                            "MODEL: AI TCN (${state.aiLatencyMs}ms)"
                        } else {
                            "MODEL: CLASSICAL"
                        },
                        color = CockpitPrimaryText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
            }

            if (state.isCalibratingSensors) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.82f),
                shape = RoundedCornerShape(12.dp),
                color = CockpitSurface.copy(alpha = 0.95f),
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (state.calibrationMovementDetected) "Movement detected — hold still" else "Keep phone still",
                        color = CockpitPrimaryText,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Calibrating sensors... ${state.sensorCalibrationCountdown}s",
                        color = CockpitSecondaryText,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.sensorCalibrationProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            }

            // Mount Slip Warning Banner
            if (state.isMountSlipped) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    shape = RoundedCornerShape(12.dp),
                    color = GnssDegraded.copy(alpha = 0.9f),
                    tonalElevation = 4.dp
                ) {
                    Text(
                        text = "⚠ Phone mount slipped — recalibrating...",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // GPS Simulation Active Banner
            if (state.isGpsSimulationActive) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    shape = RoundedCornerShape(12.dp),
                    color = InsDeadReckoning.copy(alpha = 0.9f),
                    tonalElevation = 4.dp
                ) {
                    Text(
                        text = "🛰 GPS OUTAGE SIMULATION ACTIVE",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // Side Floating Action Buttons
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Re-center / Follow Vehicle Button
            FloatingActionButton(
                onClick = {
                    followVehicle = true
                    currentMapView?.let { map ->
                        if (state.gpsData.lat != 0.0 && state.gpsData.lon != 0.0) {
                            map.controller.animateTo(org.osmdroid.util.GeoPoint(state.gpsData.lat, state.gpsData.lon))
                        }
                    }
                },
                containerColor = CockpitSurface,
                contentColor = if (followVehicle) VehicleMarkerAccent else CockpitSecondaryText,
                modifier = Modifier.size(48.dp),
                shape = CircleShape
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Center on Vehicle")
            }

            FloatingActionButton(
                onClick = { areMapControlsExpanded = !areMapControlsExpanded },
                containerColor = CockpitSurface,
                contentColor = CockpitPrimaryText,
                modifier = Modifier.size(48.dp),
                shape = CircleShape
            ) {
                Icon(
                    if (areMapControlsExpanded) Icons.Default.Close else Icons.Default.MoreVert,
                    contentDescription = if (areMapControlsExpanded) "Close map controls" else "Open map controls"
                )
            }

            AnimatedVisibility(visible = areMapControlsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Map Style Switcher (Dark Cockpit, Street Navigation, Satellite View)
                    FloatingActionButton(
                        onClick = {
                            val nextMapStyle = when (mapStyle) {
                                AppMapStyle.SATELLITE -> AppMapStyle.STREET_MAP
                                AppMapStyle.STREET_MAP -> AppMapStyle.DARK_COCKPIT
                                AppMapStyle.DARK_COCKPIT -> AppMapStyle.SATELLITE
                            }
                            onMapStyleChanged(nextMapStyle)
                            Toast.makeText(context, nextMapStyle.title, Toast.LENGTH_SHORT).show()
                        },
                        containerColor = CockpitSurface,
                        contentColor = CockpitPrimaryText,
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Layers, contentDescription = "Switch Map Style")
                    }

                    // Recalibrate IMU Button
                    FloatingActionButton(
                        onClick = {
                            if (!state.isCalibratingSensors) {
                                viewModel.recalibrate()
                                viewModel.startSensorCalibration()
                            }
                        },
                        containerColor = CockpitSurface,
                        contentColor = if (state.isCalibratingSensors) CockpitSecondaryText else GnssDegraded,
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Calibrate sensors")
                    }

                    // GPS Outage Simulation Toggle
                    FloatingActionButton(
                        onClick = {
                            viewModel.toggleGpsSimulation()
                            val msg = if (!state.isGpsSimulationActive) "GPS Outage Simulation ON" else "GPS Outage Simulation OFF"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        containerColor = if (state.isGpsSimulationActive) InsDeadReckoning.copy(alpha = 0.8f) else CockpitSurface,
                        contentColor = if (state.isGpsSimulationActive) Color.White else CockpitSecondaryText,
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.GpsOff, contentDescription = "Simulate GPS Outage")
                    }

                    // Course-Up / North-Up Toggle
                    FloatingActionButton(
                        onClick = {
                            viewModel.toggleCourseUpMode()
                            val msg = if (!state.isCourseUpMode) "Course-Up Mode" else "North-Up Mode"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        containerColor = CockpitSurface,
                        contentColor = if (state.isCourseUpMode) VehicleMarkerAccent else CockpitSecondaryText,
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape
                    ) {
                        Icon(
                            if (state.isCourseUpMode) Icons.Default.Navigation else Icons.Default.Explore,
                            contentDescription = "Toggle Map Orientation"
                        )
                    }
                }
            }
        }

        if (destination != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 132.dp)
                    .fillMaxWidth(0.86f),
                shape = RoundedCornerShape(14.dp),
                color = CockpitSurface.copy(alpha = 0.96f),
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                routeState.isLoading -> "Calculating route..."
                                routeState.errorMessage != null -> routeState.errorMessage.orEmpty()
                                else -> "Route to destination"
                            },
                            color = CockpitPrimaryText,
                            style = MaterialTheme.typography.labelLarge
                        )
                        if (!routeState.isLoading && routeState.errorMessage == null) {
                            Text(
                                text = "${"%.1f".format(routeState.distanceMeters / 1000.0)} km · ${formatDuration(routeState.durationSeconds)}",
                                color = CockpitSecondaryText,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    TextButton(onClick = { viewModel.clearDestination() }) {
                        Text("Clear", color = VehicleMarkerAccent)
                    }
                }
            }
        }

        // Bottom Instrument Panel
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .background(CockpitSurface, RoundedCornerShape(16.dp))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            InstrumentDataBlock(
                label = "Speed",
                value = "%.0f".format(state.speedKmh),
                unit = "km/h"
            )
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .width(1.dp)
                    .background(CockpitDivider)
            )
            // Drift color shifts amber→red as INS_ONLY duration increases
            val driftColor = when {
                state.gnssStatus != GnssStatus.INS_ONLY -> CockpitPrimaryText
                state.insOnlyDurationSec >= GnssDeficitHandler.INS_DRIFT_WARNING_THRESHOLD_SEC -> DriftCriticalRed
                state.insOnlyDurationSec >= GnssDeficitHandler.INS_DRIFT_WARNING_THRESHOLD_SEC * 0.67f -> DriftWarningAmber
                else -> CockpitPrimaryText
            }
            InstrumentDataBlock(
                label = if (state.gnssStatus == GnssStatus.INS_ONLY && state.insOnlyDurationSec >= GnssDeficitHandler.INS_DRIFT_WARNING_THRESHOLD_SEC)
                    "⚠ Drift Est." else "Drift Est.",
                value = if (state.hasFix || state.gnssStatus == GnssStatus.INS_ONLY) "%.1f".format(state.driftEstMeters) else "—",
                unit = "m",
                valueColor = driftColor,
                subText = modeDurationStr
            )
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .width(1.dp)
                    .background(CockpitDivider)
            )
            InstrumentDataBlock(
                label = "Heading",
                value = "${state.headingDeg}",
                unit = "° ${state.headingCardinal}"
            )
        }
    }
}

private fun formatDuration(seconds: Double): String {
    val totalMinutes = (seconds / 60.0).toInt()
    return if (totalMinutes < 60) {
        "${totalMinutes} min"
    } else {
        "${totalMinutes / 60}h ${totalMinutes % 60}m"
    }
}

@Composable
fun InstrumentDataBlock(
    label: String,
    value: String,
    unit: String,
    valueColor: Color = CockpitPrimaryText,
    subText: String? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = CockpitSecondaryText,
            style = MaterialTheme.typography.labelMedium
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = valueColor,
                style = MaterialTheme.typography.displayMedium
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = unit,
                color = CockpitSecondaryText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        if (subText != null) {
            Text(
                text = subText,
                color = valueColor.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
