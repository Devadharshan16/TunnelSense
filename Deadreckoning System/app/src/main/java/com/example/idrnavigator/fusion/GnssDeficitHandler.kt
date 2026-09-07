package com.example.idrnavigator.fusion

import com.example.idr.core.estimator.IdrPositionEstimator
import com.example.idr.core.fusion.CoreGnssDeficitHandler
import com.example.idr.core.model.IdrGnssState
import com.example.idr.core.model.IdrGpsSample
import com.example.idr.core.model.IdrImuSample
import com.example.idr.core.model.IdrLatLon
import com.example.idrnavigator.inference.AiPositionEstimator
import com.example.idrnavigator.inference.AndroidIdrLogger
import com.example.idrnavigator.inference.ClassicalDeadReckoner
import com.example.idrnavigator.inference.PositionEstimator
import com.example.idrnavigator.sensors.GpsData
import com.example.idrnavigator.sensors.ImuData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GnssState {
    GNSS_ACTIVE,
    TRANSITIONING,
    INS_ONLY
}

enum class DeadReckoningMode(val displayName: String) {
    AI_TCN("AI (TinyTCN)"),
    CLASSICAL("Classical (Strapdown)")
}

data class FusedPosition(
    val lat: Double,
    val lon: Double,
    val speedMps: Float,
    val headingDeg: Float,
    val state: GnssState,
    val driftMeters: Float,
    val gpsAccuracy: Float,
    val satelliteCount: Int,
    val hasFix: Boolean,
    /** How many seconds INS_ONLY mode has been active continuously. 0 when GNSS is active. */
    val insOnlyDurationSec: Float = 0f,
    val deadReckoningMode: DeadReckoningMode = DeadReckoningMode.AI_TCN,
    val aiLatencyMs: Long = 0L,
    val rawAiVelocityKmH: Float = 0f
)

/**
 * Android facade for GNSS Deficit handling.
 * Supports hot-swapping between Classical Strapdown and AI (TinyTCN) Position Estimators.
 */
class GnssDeficitHandler(
    val classicalEstimator: PositionEstimator = ClassicalDeadReckoner(),
    var aiEstimator: AiPositionEstimator? = null
) {

    companion object {
        // ──────────────────────────────────────────────────────────
        // TUNABLE CONSTANTS — mirror CoreGnssDeficitHandler
        // ──────────────────────────────────────────────────────────

        var GPS_ACCURACY_THRESHOLD: Float
            get() = CoreGnssDeficitHandler.GPS_ACCURACY_THRESHOLD
            set(value) { CoreGnssDeficitHandler.GPS_ACCURACY_THRESHOLD = value }

        var GPS_LOSS_TIMEOUT_MS: Long
            get() = CoreGnssDeficitHandler.GPS_LOSS_TIMEOUT_MS
            set(value) { CoreGnssDeficitHandler.GPS_LOSS_TIMEOUT_MS = value }

        var GPS_REACQUIRE_ACCURACY: Float
            get() = CoreGnssDeficitHandler.GPS_REACQUIRE_ACCURACY
            set(value) { CoreGnssDeficitHandler.GPS_REACQUIRE_ACCURACY = value }

        var CONSECUTIVE_GOOD_FIXES_REQUIRED: Int
            get() = CoreGnssDeficitHandler.CONSECUTIVE_GOOD_FIXES_REQUIRED
            set(value) { CoreGnssDeficitHandler.CONSECUTIVE_GOOD_FIXES_REQUIRED = value }

        var TRANSITION_BLEND_DURATION_MS: Long
            get() = CoreGnssDeficitHandler.TRANSITION_BLEND_DURATION_MS
            set(value) { CoreGnssDeficitHandler.TRANSITION_BLEND_DURATION_MS = value }

        var INS_DRIFT_WARNING_THRESHOLD_SEC: Float
            get() = CoreGnssDeficitHandler.INS_DRIFT_WARNING_THRESHOLD_SEC
            set(value) { CoreGnssDeficitHandler.INS_DRIFT_WARNING_THRESHOLD_SEC = value }

        var BASE_DRIFT_FRACTION: Float
            get() = CoreGnssDeficitHandler.BASE_DRIFT_FRACTION
            set(value) { CoreGnssDeficitHandler.BASE_DRIFT_FRACTION = value }

        var AGGRESSIVE_DRIFT_FRACTION: Float
            get() = CoreGnssDeficitHandler.AGGRESSIVE_DRIFT_FRACTION
            set(value) { CoreGnssDeficitHandler.AGGRESSIVE_DRIFT_FRACTION = value }
    }

    var deadReckoningMode: DeadReckoningMode = if (aiEstimator?.isModelLoaded == true) {
        DeadReckoningMode.AI_TCN
    } else {
        DeadReckoningMode.CLASSICAL
    }

    private val activeBridge = object : IdrPositionEstimator {
        private fun getActive(): PositionEstimator {
            return if (deadReckoningMode == DeadReckoningMode.AI_TCN && aiEstimator?.isModelLoaded == true) {
                aiEstimator!!
            } else {
                classicalEstimator
            }
        }

        override fun estimateVelocity(imuWindow: List<IdrImuSample>): Float {
            val appImu = imuWindow.map {
                ImuData(
                    timestamp = it.timestampMs,
                    accelX = it.accelX, accelY = it.accelY, accelZ = it.accelZ,
                    gyroX = it.gyroX, gyroY = it.gyroY, gyroZ = it.gyroZ,
                    magX = it.magX, magY = it.magY, magZ = it.magZ
                )
            }
            return getActive().estimateVelocity(appImu)
        }

        override fun estimateHeading(imuWindow: List<IdrImuSample>, dtSeconds: Float, currentMagHeadingDeg: Float): Float {
            val appImu = imuWindow.map {
                ImuData(
                    timestamp = it.timestampMs,
                    accelX = it.accelX, accelY = it.accelY, accelZ = it.accelZ,
                    gyroX = it.gyroX, gyroY = it.gyroY, gyroZ = it.gyroZ,
                    magX = it.magX, magY = it.magY, magZ = it.magZ
                )
            }
            return getActive().estimateHeading(appImu, dtSeconds, currentMagHeadingDeg)
        }

        override fun estimatePosition(
            lastPosition: IdrLatLon,
            velocityMps: Float,
            headingDeg: Float,
            deltaTimeSeconds: Float
        ): IdrLatLon {
            return getActive().estimatePosition(lastPosition, velocityMps, headingDeg, deltaTimeSeconds)
        }

        override fun reset() {
            // no-op
        }
    }

    private val coreHandler = CoreGnssDeficitHandler(
        positionEstimator = activeBridge,
        logger = AndroidIdrLogger
    )

    private val _fusedPosition = MutableStateFlow(
        FusedPosition(0.0, 0.0, 0f, 0f, GnssState.GNSS_ACTIVE, 0f, 0f, 0, false)
    )
    val fusedPosition: StateFlow<FusedPosition> = _fusedPosition.asStateFlow()

    fun update(gpsData: GpsData, imuData: ImuData, magHeadingDeg: Float) {
        val coreGps = IdrGpsSample(
            lat = gpsData.lat,
            lon = gpsData.lon,
            speedMps = gpsData.speed,
            bearingDeg = gpsData.bearing,
            accuracyMeters = gpsData.accuracy,
            hasFix = gpsData.hasFix,
            satelliteCount = gpsData.satelliteCount
        )
        val coreImu = IdrImuSample(
            timestampMs = imuData.timestamp,
            accelX = imuData.accelX,
            accelY = imuData.accelY,
            accelZ = imuData.accelZ,
            gyroX = imuData.gyroX,
            gyroY = imuData.gyroY,
            gyroZ = imuData.gyroZ,
            magX = imuData.magX,
            magY = imuData.magY,
            magZ = imuData.magZ
        )

        val estimate = coreHandler.update(coreGps, coreImu, magHeadingDeg)

        val state = when (estimate.state) {
            IdrGnssState.GNSS_ACTIVE -> GnssState.GNSS_ACTIVE
            IdrGnssState.TRANSITIONING -> GnssState.TRANSITIONING
            IdrGnssState.INS_ONLY -> GnssState.INS_ONLY
        }

        // Keep AI TCN model warm during GNSS_ACTIVE so buffer is ready and HUD displays live latency & predictions
        if (state == GnssState.GNSS_ACTIVE && deadReckoningMode == DeadReckoningMode.AI_TCN && aiEstimator?.isModelLoaded == true) {
            aiEstimator?.estimateVelocity(listOf(imuData))
        }

        _fusedPosition.value = FusedPosition(
            lat = estimate.lat,
            lon = estimate.lon,
            speedMps = estimate.speedMps,
            headingDeg = estimate.headingDeg,
            state = state,
            driftMeters = estimate.driftMeters,
            gpsAccuracy = estimate.gpsAccuracy,
            satelliteCount = estimate.satelliteCount,
            hasFix = estimate.hasFix,
            insOnlyDurationSec = estimate.insOnlyDurationSec,
            deadReckoningMode = deadReckoningMode,
            aiLatencyMs = aiEstimator?.lastInferenceLatencyMs ?: 0L,
            rawAiVelocityKmH = aiEstimator?.rawPredictedKmH ?: 0f
        )
    }
}
