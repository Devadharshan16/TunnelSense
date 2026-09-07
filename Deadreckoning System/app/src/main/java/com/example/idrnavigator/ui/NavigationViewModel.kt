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
import com.example.idrnavigator.sensors.GnssManager
import com.example.idrnavigator.sensors.GpsData
import com.example.idrnavigator.sensors.ImuData
import com.example.idrnavigator.sensors.ImuSensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
    val gpsData: GpsData = GpsData()
)

class NavigationViewModel(
    context: Context,
    val imuSensorManager: ImuSensorManager,
    val gnssManager: GnssManager
) : ViewModel() {

    private val alignmentEngine = AlignmentEngine()
    private val aiEstimator = AiPositionEstimator(context.applicationContext)
    private val gnssDeficitHandler = GnssDeficitHandler(aiEstimator = aiEstimator)
    private val _locationHistory = MutableStateFlow<List<GeoPoint>>(emptyList())

    val alignedImuFlow = imuSensorManager.imuDataFlow
        .combine(gnssManager.gpsDataFlow) { imu, gps ->
            alignmentEngine.processAndAlign(imu, gps)
        }

    val uiState = alignedImuFlow
        .combine(gnssManager.gpsDataFlow) { alignedImu, gps ->
            val mx = alignedImu.magX.toDouble()
            val my = alignedImu.magY.toDouble()
            val rawDeg = Math.toDegrees(atan2(mx, my))
            val magHeading = ((rawDeg + 360) % 360).toFloat()
            
            gnssDeficitHandler.update(gps, alignedImu, magHeading)
            gnssDeficitHandler.fusedPosition.value to alignedImu
        }
        .combine(_locationHistory) { (fused, alignedImu), history ->
            buildUiState(fused, alignedImu, history)
        }

    init {
        viewModelScope.launch {
            gnssDeficitHandler.fusedPosition.collect { fused ->
                if (fused.hasFix || fused.state == GnssState.INS_ONLY) {
                    val newPoint = GeoPoint(fused.lat, fused.lon)
                    val history = _locationHistory.value.toMutableList()
                    val last = history.lastOrNull()
                    // Only add if moved more than 1 meter to reduce list noise
                    if (last == null || last.distanceToAsDouble(newPoint) > 1.0) {
                        history.add(newPoint)
                        if (history.size > 1000) history.removeAt(0)
                        _locationHistory.value = history
                    }
                }
            }
        }
    }

    fun recalibrate() {
        alignmentEngine.reset()
    }

    fun toggleDeadReckoningMode() {
        gnssDeficitHandler.deadReckoningMode = if (gnssDeficitHandler.deadReckoningMode == DeadReckoningMode.AI_TCN) {
            DeadReckoningMode.CLASSICAL
        } else {
            DeadReckoningMode.AI_TCN
        }
    }

    fun setDeadReckoningMode(mode: DeadReckoningMode) {
        gnssDeficitHandler.deadReckoningMode = mode
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
            )
        )
    }
}
