package com.example.idr.core.fusion

import com.example.idr.core.estimator.CoreDeadReckoner
import com.example.idr.core.estimator.IdrPositionEstimator
import com.example.idr.core.logging.ConsoleIdrLogger
import com.example.idr.core.logging.IdrLogger
import com.example.idr.core.model.IdrGnssState
import com.example.idr.core.model.IdrGpsSample
import com.example.idr.core.model.IdrImuSample
import com.example.idr.core.model.IdrLatLon
import com.example.idr.core.model.IdrPositionEstimate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure Kotlin GNSS deficit state machine managing:
 *   GNSS_ACTIVE <───> INS_ONLY <───> TRANSITIONING
 *
 * Can be run on Android, embedded Linux (Raspberry Pi/Jetson),
 * or backend evaluation pipelines without Android runtime classes.
 */
class CoreGnssDeficitHandler(
    private val positionEstimator: IdrPositionEstimator = CoreDeadReckoner(),
    private val logger: IdrLogger = ConsoleIdrLogger
) {

    companion object {
        private const val TAG = "CoreGnssDeficit"

        // ──────────────────────────────────────────────────────────
        // TUNABLE PARAMETERS
        // ──────────────────────────────────────────────────────────

        var GPS_ACCURACY_THRESHOLD = 20f
        var GPS_LOSS_TIMEOUT_MS = 2000L
        var GPS_REACQUIRE_ACCURACY = 15f
        var CONSECUTIVE_GOOD_FIXES_REQUIRED = 2
        var TRANSITION_BLEND_DURATION_MS = 1500L
        var INS_DRIFT_WARNING_THRESHOLD_SEC = 90f
        var BASE_DRIFT_FRACTION = 0.10f
        var AGGRESSIVE_DRIFT_FRACTION = 0.25f
    }

    private val _positionEstimate = MutableStateFlow(
        IdrPositionEstimate(0.0, 0.0, 0f, 0f, IdrGnssState.GNSS_ACTIVE, 0f, 0f, 0, false)
    )
    val positionEstimate: StateFlow<IdrPositionEstimate> = _positionEstimate.asStateFlow()

    private var currentState = IdrGnssState.GNSS_ACTIVE
    private var lastGoodGpsTime = 0L
    private var lastGoodLatLon: IdrLatLon? = null
    private var consecutiveGoodFixes = 0
    private var lastGpsData: IdrGpsSample? = null

    private val imuWindow = mutableListOf<IdrImuSample>()

    private var currentInsPosition: IdrLatLon? = null
    private var insVelocity = 0f
    private var insHeading = 0f
    private var lastInsUpdateTime = 0L
    private var cumulativeInsDistance = 0f
    private var insOnlyStartTime = 0L
    private var lastHeadingUpdateTime = 0L

    private var transitionStartTime = 0L
    private var transitionStartInsPos: IdrLatLon? = null

    fun reset() {
        currentState = IdrGnssState.GNSS_ACTIVE
        lastGoodGpsTime = 0L
        lastGoodLatLon = null
        consecutiveGoodFixes = 0
        lastGpsData = null
        imuWindow.clear()
        currentInsPosition = null
        insVelocity = 0f
        insHeading = 0f
        lastInsUpdateTime = 0L
        cumulativeInsDistance = 0f
        insOnlyStartTime = 0L
        lastHeadingUpdateTime = 0L
        transitionStartTime = 0L
        transitionStartInsPos = null
        positionEstimator.reset()
        _positionEstimate.value = IdrPositionEstimate(
            0.0, 0.0, 0f, 0f, IdrGnssState.GNSS_ACTIVE, 0f, 0f, 0, false
        )
    }

    /**
     * Process one epoch of sensor data.
     * Returns the latest [IdrPositionEstimate] synchronously.
     */
    fun update(
        gpsData: IdrGpsSample,
        imuData: IdrImuSample,
        magHeadingDeg: Float
    ): IdrPositionEstimate {
        val currentTime = imuData.timestampMs

        imuWindow.add(imuData)
        while (imuWindow.isNotEmpty() && (currentTime - imuWindow.first().timestampMs) > 500) {
            imuWindow.removeAt(0)
        }

        val headingDt = if (lastHeadingUpdateTime > 0L) {
            ((currentTime - lastHeadingUpdateTime).coerceAtLeast(0L) / 1000f).coerceAtMost(0.5f)
        } else {
            0f
        }
        val estimatedHeading = if (headingDt > 0f) {
            positionEstimator.estimateHeading(listOf(imuData), headingDt, magHeadingDeg)
        } else {
            positionEstimator.estimateHeading(emptyList(), 0f, magHeadingDeg)
        }
        lastHeadingUpdateTime = currentTime

        val isGpsGood = gpsData.hasFix && gpsData.accuracyMeters <= GPS_ACCURACY_THRESHOLD

        if (lastGoodGpsTime == 0L && isGpsGood) {
            lastGoodGpsTime = currentTime
            lastGoodLatLon = IdrLatLon(gpsData.lat, gpsData.lon)
        }

        when (currentState) {
            IdrGnssState.GNSS_ACTIVE -> {
                val timeSinceGoodGps = if (lastGoodGpsTime > 0L) currentTime - lastGoodGpsTime else 0L
                if (lastGoodLatLon != null && !isGpsGood && timeSinceGoodGps > GPS_LOSS_TIMEOUT_MS) {
                    val handoffPos = lastGoodLatLon!!
                    logger.d(
                        TAG,
                        "▼ GNSS_ACTIVE → INS_ONLY | " +
                        "time=$currentTime | " +
                        "gpsAccuracy=${gpsData.accuracyMeters}m | " +
                        "timeSinceGoodGps=${timeSinceGoodGps}ms | " +
                        "handoffPos=(${handoffPos.lat}, ${handoffPos.lon})"
                    )

                    currentState = IdrGnssState.INS_ONLY
                    currentInsPosition = handoffPos
                    insVelocity = gpsData.speedMps
                    insHeading = gpsData.bearingDeg
                    lastInsUpdateTime = currentTime
                    cumulativeInsDistance = 0f
                    insOnlyStartTime = currentTime
                    consecutiveGoodFixes = 0

                    _positionEstimate.value = IdrPositionEstimate(
                        lat = handoffPos.lat,
                        lon = handoffPos.lon,
                        speedMps = insVelocity,
                        headingDeg = insHeading,
                        state = IdrGnssState.INS_ONLY,
                        driftMeters = 0f,
                        gpsAccuracy = gpsData.accuracyMeters,
                        satelliteCount = gpsData.satelliteCount,
                        hasFix = false,
                        insOnlyDurationSec = 0f
                    )
                } else {
                    if (isGpsGood) {
                        lastGoodGpsTime = currentTime
                        lastGoodLatLon = IdrLatLon(gpsData.lat, gpsData.lon)
                    }
                    _positionEstimate.value = IdrPositionEstimate(
                        lat = gpsData.lat,
                        lon = gpsData.lon,
                        speedMps = gpsData.speedMps,
                        headingDeg = estimatedHeading,
                        state = IdrGnssState.GNSS_ACTIVE,
                        driftMeters = 0f,
                        gpsAccuracy = gpsData.accuracyMeters,
                        satelliteCount = gpsData.satelliteCount,
                        hasFix = gpsData.hasFix,
                        insOnlyDurationSec = 0f
                    )
                }
            }

            IdrGnssState.INS_ONLY -> {
                val dt = (currentTime - lastInsUpdateTime) / 1000f
                val insOnlyDurationSec = (currentTime - insOnlyStartTime) / 1000f

                if (dt > 0 && currentInsPosition != null) {
                    insVelocity = positionEstimator.estimateVelocity(imuWindow)
                    insHeading = positionEstimator.estimateHeading(imuWindow, dt, magHeadingDeg)

                    val newPos = positionEstimator.estimatePosition(
                        currentInsPosition!!,
                        insVelocity,
                        insHeading,
                        dt
                    )

                    val distTraveled = insVelocity * dt
                    cumulativeInsDistance += distTraveled

                    val drift = if (insOnlyDurationSec < INS_DRIFT_WARNING_THRESHOLD_SEC) {
                        cumulativeInsDistance * BASE_DRIFT_FRACTION
                    } else {
                        val baseDrift = cumulativeInsDistance * BASE_DRIFT_FRACTION
                        val excessFactor = (insOnlyDurationSec - INS_DRIFT_WARNING_THRESHOLD_SEC) / INS_DRIFT_WARNING_THRESHOLD_SEC
                        baseDrift * (1f + excessFactor * (AGGRESSIVE_DRIFT_FRACTION / BASE_DRIFT_FRACTION))
                    }

                    currentInsPosition = newPos
                    lastInsUpdateTime = currentTime

                    _positionEstimate.value = IdrPositionEstimate(
                        lat = newPos.lat,
                        lon = newPos.lon,
                        speedMps = insVelocity,
                        headingDeg = insHeading,
                        state = IdrGnssState.INS_ONLY,
                        driftMeters = drift,
                        gpsAccuracy = gpsData.accuracyMeters,
                        satelliteCount = gpsData.satelliteCount,
                        hasFix = gpsData.hasFix,
                        insOnlyDurationSec = insOnlyDurationSec
                    )
                }

                if (gpsData != lastGpsData) {
                    if (gpsData.hasFix && gpsData.accuracyMeters <= GPS_REACQUIRE_ACCURACY) {
                        consecutiveGoodFixes++
                        if (consecutiveGoodFixes >= CONSECUTIVE_GOOD_FIXES_REQUIRED) {
                            logger.d(
                                TAG,
                                "▲ INS_ONLY → TRANSITIONING | " +
                                "time=$currentTime | " +
                                "insDuration=${(currentTime - insOnlyStartTime) / 1000f}s | " +
                                "gpsAccuracy=${gpsData.accuracyMeters}m | " +
                                "insPos=(${currentInsPosition?.lat}, ${currentInsPosition?.lon}) | " +
                                "gpsPos=(${gpsData.lat}, ${gpsData.lon})"
                            )

                            currentState = IdrGnssState.TRANSITIONING
                            transitionStartTime = currentTime
                            transitionStartInsPos = currentInsPosition
                            consecutiveGoodFixes = 0
                        }
                    } else {
                        consecutiveGoodFixes = 0
                    }
                }
            }

            IdrGnssState.TRANSITIONING -> {
                val progress = (currentTime - transitionStartTime).toFloat() / TRANSITION_BLEND_DURATION_MS

                if (progress >= 1.0f) {
                    logger.d(
                        TAG,
                        "✓ TRANSITIONING → GNSS_ACTIVE | " +
                        "time=$currentTime | " +
                        "gpsAccuracy=${gpsData.accuracyMeters}m | " +
                        "finalPos=(${gpsData.lat}, ${gpsData.lon})"
                    )

                    currentState = IdrGnssState.GNSS_ACTIVE
                    lastGoodGpsTime = currentTime
                    lastGoodLatLon = IdrLatLon(gpsData.lat, gpsData.lon)
                    insOnlyStartTime = 0L
                    cumulativeInsDistance = 0f
                    transitionStartInsPos = null
                    positionEstimator.reset()

                    _positionEstimate.value = IdrPositionEstimate(
                        lat = gpsData.lat,
                        lon = gpsData.lon,
                        speedMps = gpsData.speedMps,
                        headingDeg = estimatedHeading,
                        state = IdrGnssState.GNSS_ACTIVE,
                        driftMeters = 0f,
                        gpsAccuracy = gpsData.accuracyMeters,
                        satelliteCount = gpsData.satelliteCount,
                        hasFix = gpsData.hasFix,
                        insOnlyDurationSec = 0f
                    )
                } else if (gpsData.hasFix && gpsData.accuracyMeters <= GPS_ACCURACY_THRESHOLD) {
                    val start = transitionStartInsPos
                    val target = IdrLatLon(gpsData.lat, gpsData.lon)

                    if (start != null) {
                        val blendedLat = start.lat + (target.lat - start.lat) * progress
                        val blendedLon = start.lon + (target.lon - start.lon) * progress
                        currentInsPosition = IdrLatLon(blendedLat, blendedLon)

                        _positionEstimate.value = IdrPositionEstimate(
                            lat = blendedLat,
                            lon = blendedLon,
                            speedMps = gpsData.speedMps,
                            headingDeg = gpsData.bearingDeg,
                            state = IdrGnssState.TRANSITIONING,
                            driftMeters = 0f,
                            gpsAccuracy = gpsData.accuracyMeters,
                            satelliteCount = gpsData.satelliteCount,
                            hasFix = gpsData.hasFix,
                            insOnlyDurationSec = 0f
                        )
                    }
                }

                if (gpsData != lastGpsData) {
                    if (!gpsData.hasFix || gpsData.accuracyMeters > GPS_ACCURACY_THRESHOLD) {
                        logger.d(
                            TAG,
                            "✗ TRANSITIONING → INS_ONLY (GPS degraded during blend) | " +
                            "time=$currentTime | " +
                            "gpsAccuracy=${gpsData.accuracyMeters}m"
                        )

                        currentState = IdrGnssState.INS_ONLY
                        lastInsUpdateTime = currentTime
                        insOnlyStartTime = currentTime
                        cumulativeInsDistance = 0f
                        consecutiveGoodFixes = 0
                    }
                }
            }
        }

        lastGpsData = gpsData
        return _positionEstimate.value
    }
}
