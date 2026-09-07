package com.example.idrnavigator.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.idrnavigator.calibration.AlignmentEngine
import com.example.idrnavigator.fusion.DeadReckoningMode
import com.example.idrnavigator.fusion.FusedPosition
import com.example.idrnavigator.fusion.GnssDeficitHandler
import com.example.idrnavigator.fusion.GnssState
import com.example.idrnavigator.inference.AiPositionEstimator
import com.example.idrnavigator.map.RouteManager
import com.example.idrnavigator.map.RouteState
import com.example.idrnavigator.sensors.GnssManager
import com.example.idrnavigator.sensors.GpsData
import com.example.idrnavigator.sensors.ImuData
import com.example.idrnavigator.sensors.ImuSensorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import kotlin.math.atan2
import kotlin.math.roundToInt

enum class GnssStatus {
    LOCKED,
    DEGRADED,
    INS_ONLY
}

data class NavigationUiState(
    val speedKmh: Float = 0f,
    val headingDeg: Int = 0,
    val headingCardinal: String = "N",
    val driftEstMeters: Float = 0f,
    val gnssStatus: GnssStatus = GnssStatus.INS_ONLY,
    val gnssState: GnssState = GnssState.GNSS_ACTIVE,
    val satelliteCount: Int = 0,
    val gpsAccuracy: Float = 0f,
    val hasFix: Boolean = false,
    val isCalibrated: Boolean = false,
    val locationHistory: List<GeoPoint> = emptyList(),
    /** How long we have been in INS_ONLY mode continuously (seconds). */
    val insOnlyDurationSec: Float = 0f,
    val deadReckoningMode: DeadReckoningMode = DeadReckoningMode.AI_TCN,
    val aiLatencyMs: Long = 0L,
    val rawAiVelocityKmH: Float = 0f,
    // Raw or aligned vectors for engineering display
    val imuData: ImuData = ImuData(),
    val gpsData: GpsData = GpsData(),
    // GPS simulation toggle
    val isGpsSimulationActive: Boolean = false,
    // Dual-path comparison tracks
    val classicalPathHistory: List<GeoPoint> = emptyList(),
    val gpsPathHistory: List<GeoPoint> = emptyList(),
    // Map orientation mode
    val isCourseUpMode: Boolean = false,
    val isMountSlipped: Boolean = false,
    // Sensor bias calibration
    val isCalibratingSensors: Boolean = false,
    val isSensorCalibrated: Boolean = false,
    val sensorCalibrationProgress: Float = 0f,
    val sensorCalibrationCountdown: Int = 0,
    val calibrationMovementDetected: Boolean = false,
    val isAiModelLoaded: Boolean = false
)

class NavigationViewModel(
    context: Context,
    val imuSensorManager: ImuSensorManager,
    val gnssManager: GnssManager
) : ViewModel() {

    val sensorBiasCalibrator = com.example.idrnavigator.calibration.SensorBiasCalibrator(context.applicationContext)
    private val alignmentEngine = AlignmentEngine()
    private val aiEstimator = AiPositionEstimator(context.applicationContext)
    private val gnssDeficitHandler = GnssDeficitHandler(aiEstimator = aiEstimator)
    private val settings = context.applicationContext.getSharedPreferences("idr_settings", Context.MODE_PRIVATE)
    private val routeManager = RouteManager(context, viewModelScope)
    private val _destination = MutableStateFlow<GeoPoint?>(null)
    val destination: StateFlow<GeoPoint?> = _destination
    val routeState: StateFlow<RouteState> = routeManager.state
    private val _locationHistory = MutableStateFlow<List<GeoPoint>>(emptyList())

    // Dual-path tracking for side-by-side comparison
    private val _classicalPathHistory = MutableStateFlow<List<GeoPoint>>(emptyList())
    private val _gpsPathHistory = MutableStateFlow<List<GeoPoint>>(emptyList())

    // GPS outage simulation
    private val _isGpsSimulationActive = MutableStateFlow(false)

    companion object {
        const val MAX_POLYLINE_POINTS = 3000
        const val MIN_POINT_DISTANCE_METERS = 2.0
        const val UI_THROTTLE_SAMPLE_MS = 50L // ~20Hz update rate for smooth Compose rendering
    }

    // Map orientation mode
    private val _isCourseUpMode = MutableStateFlow(false)

    init {
        val savedMode = settings.getString("dead_reckoning_mode", DeadReckoningMode.AI_TCN.name)
        if (savedMode == DeadReckoningMode.AI_TCN.name && aiEstimator.isModelLoaded) {
            gnssDeficitHandler.deadReckoningMode = DeadReckoningMode.AI_TCN
        } else {
            gnssDeficitHandler.deadReckoningMode = DeadReckoningMode.CLASSICAL
        }
        _isCourseUpMode.value = settings.getBoolean("course_up_mode", false)
        _isGpsSimulationActive.value = settings.getBoolean("gps_simulation", false)
    }

    private val alignedImuSource = imuSensorManager.imuDataFlow
        .map { rawImu ->
            val biasCorrectedImu = sensorBiasCalibrator.processAndApply(rawImu)
            alignmentEngine.processAndAlign(biasCorrectedImu, gnssManager.gpsDataFlow.value)
        }
        .flowOn(Dispatchers.Default)

    val alignedImuFlow = alignedImuSource.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ImuData()
    )

    @OptIn(FlowPreview::class)
    private val throttledUiStateFlow = alignedImuFlow
        .map { alignedImu ->
            val gps = gnssManager.gpsDataFlow.value
            val mx = alignedImu.magX.toDouble()
            val my = alignedImu.magY.toDouble()
            val rawDeg = Math.toDegrees(atan2(mx, my))
            val magHeading = ((rawDeg + 360) % 360).toFloat()

            // If GPS simulation is active, mask GPS as lost
            val effectiveGps = if (_isGpsSimulationActive.value) {
                gps.copy(hasFix = false, accuracy = 999f, satelliteCount = 0)
            } else {
                gps
            }

            // Track real GPS path separately for ground truth comparison
            if (gps.hasFix && gps.lat != 0.0 && gps.lon != 0.0) {
                val pt = GeoPoint(gps.lat, gps.lon)
                val gpsHist = _gpsPathHistory.value.toMutableList()
                val lastGps = gpsHist.lastOrNull()
                if (lastGps == null || lastGps.distanceToAsDouble(pt) > MIN_POINT_DISTANCE_METERS) {
                    gpsHist.add(pt)
                    if (gpsHist.size > MAX_POLYLINE_POINTS) gpsHist.removeAt(0)
                    _gpsPathHistory.value = gpsHist
                }
            }

            gnssDeficitHandler.update(effectiveGps, alignedImu, magHeading)
            alignedImu
        }
        .sample(UI_THROTTLE_SAMPLE_MS)
        .map { alignedImu ->
            buildUiState(
                fused = gnssDeficitHandler.fusedPosition.value,
                imu = alignedImu,
                history = _locationHistory.value
            )
        }
        .flowOn(Dispatchers.Default)

    @OptIn(FlowPreview::class)
    val uiState: StateFlow<NavigationUiState> = throttledUiStateFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = NavigationUiState()
        )

    init {
        viewModelScope.launch(Dispatchers.Default) {
            gnssDeficitHandler.fusedPosition.collect { fused ->
                if ((fused.hasFix || fused.state == GnssState.INS_ONLY) &&
                    fused.lat != 0.0 && fused.lon != 0.0
                ) {
                    val newPoint = GeoPoint(fused.lat, fused.lon)
                    val history = _locationHistory.value.toMutableList()
                    val last = history.lastOrNull()
                    // Only add if moved more than MIN_POINT_DISTANCE_METERS to eliminate noise and save memory
                    if (last == null || last.distanceToAsDouble(newPoint) > MIN_POINT_DISTANCE_METERS) {
                        history.add(newPoint)
                        if (history.size > MAX_POLYLINE_POINTS) history.removeAt(0)
                        _locationHistory.value = history
                    }
                }
            }
        }
    }

    fun recalibrate() {
        alignmentEngine.reset()
    }

    fun setDestination(destination: GeoPoint) {
        _destination.value = destination
        val current = gnssDeficitHandler.fusedPosition.value
        if (current.lat != 0.0 && current.lon != 0.0) {
            routeManager.requestRoute(GeoPoint(current.lat, current.lon), destination)
        } else {
            routeManager.clear()
        }
    }

    fun clearDestination() {
        _destination.value = null
        routeManager.clear()
    }

    fun startSensorCalibration(durationMs: Long = 3500L) {
        sensorBiasCalibrator.startCalibration(durationMs)
    }

    fun resetSensorCalibration() {
        sensorBiasCalibrator.resetCalibration()
    }

    fun toggleDeadReckoningMode() {
        val nextMode = if (gnssDeficitHandler.deadReckoningMode == DeadReckoningMode.AI_TCN) {
            DeadReckoningMode.CLASSICAL
        } else {
            DeadReckoningMode.AI_TCN
        }
        setDeadReckoningMode(nextMode)
    }

    fun setDeadReckoningMode(mode: DeadReckoningMode) {
        if (mode == DeadReckoningMode.AI_TCN && !aiEstimator.isModelLoaded) return
        gnssDeficitHandler.deadReckoningMode = mode
        settings.edit().putString("dead_reckoning_mode", mode.name).apply()
    }

    fun toggleGpsSimulation() {
        _isGpsSimulationActive.value = !_isGpsSimulationActive.value
        settings.edit().putBoolean("gps_simulation", _isGpsSimulationActive.value).apply()
    }

    fun toggleCourseUpMode() {
        _isCourseUpMode.value = !_isCourseUpMode.value
        settings.edit().putBoolean("course_up_mode", _isCourseUpMode.value).apply()
    }

    private fun buildUiState(fused: FusedPosition, imu: ImuData, history: List<GeoPoint>): NavigationUiState {
        val headingDeg = fused.headingDeg.roundToInt()

        val headingCardinal = when {
            headingDeg < 23  -> "N"
            headingDeg < 68  -> "NE"
            headingDeg < 113 -> "E"
            headingDeg < 158 -> "SE"
            headingDeg < 203 -> "S"
            headingDeg < 248 -> "SW"
            headingDeg < 293 -> "W"
            headingDeg < 338 -> "NW"
            else             -> "N"
        }

        val gnssStatus = when (fused.state) {
            GnssState.GNSS_ACTIVE -> if (fused.gpsAccuracy > 20f) GnssStatus.DEGRADED else GnssStatus.LOCKED
            GnssState.TRANSITIONING -> GnssStatus.DEGRADED
            GnssState.INS_ONLY -> GnssStatus.INS_ONLY
        }

        val calib = sensorBiasCalibrator.stateFlow.value

        return NavigationUiState(
            speedKmh = fused.speedMps * 3.6f,
            headingDeg = headingDeg,
            headingCardinal = headingCardinal,
            driftEstMeters = fused.driftMeters,
            gnssStatus = gnssStatus,
            gnssState = fused.state,
            satelliteCount = fused.satelliteCount,
            gpsAccuracy = fused.gpsAccuracy,
            hasFix = fused.hasFix,
            isCalibrated = alignmentEngine.isCalibrated,
            locationHistory = history,
            insOnlyDurationSec = fused.insOnlyDurationSec,
            deadReckoningMode = fused.deadReckoningMode,
            aiLatencyMs = fused.aiLatencyMs,
            rawAiVelocityKmH = fused.rawAiVelocityKmH,
            imuData = imu,
            gpsData = GpsData(
                lat = fused.lat,
                lon = fused.lon,
                speed = fused.speedMps,
                bearing = fused.headingDeg,
                accuracy = fused.gpsAccuracy,
                hasFix = fused.hasFix,
                hasBearing = true,
                satelliteCount = fused.satelliteCount
            ),
            isGpsSimulationActive = _isGpsSimulationActive.value,
            classicalPathHistory = _classicalPathHistory.value,
            gpsPathHistory = _gpsPathHistory.value,
            isCourseUpMode = _isCourseUpMode.value,
            isMountSlipped = alignmentEngine.isMountSlipped,
            isCalibratingSensors = calib.isCalibrating,
            isSensorCalibrated = calib.isCalibrated,
            sensorCalibrationProgress = calib.progress,
            sensorCalibrationCountdown = calib.secondsRemaining,
            calibrationMovementDetected = calib.movementDetected,
            isAiModelLoaded = aiEstimator.isModelLoaded
        )
    }

    override fun onCleared() {
        super.onCleared()
        aiEstimator.onnxRunner.close()
    }
}
