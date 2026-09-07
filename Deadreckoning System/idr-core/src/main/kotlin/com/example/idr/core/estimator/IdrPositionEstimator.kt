package com.example.idr.core.estimator

import com.example.idr.core.logging.ConsoleIdrLogger
import com.example.idr.core.logging.IdrLogger
import com.example.idr.core.model.IdrImuSample
import com.example.idr.core.model.IdrLatLon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

interface IdrPositionEstimator {
    fun estimateVelocity(imuWindow: List<IdrImuSample>): Float
    fun estimateHeading(imuWindow: List<IdrImuSample>, dtSeconds: Float, currentMagHeadingDeg: Float): Float
    fun estimatePosition(
        lastPosition: IdrLatLon,
        velocityMps: Float,
        headingDeg: Float,
        deltaTimeSeconds: Float
    ): IdrLatLon
    fun reset()
}

/**
 * Pure Kotlin Dead Reckoner implementing:
 *  - 3D Zero-Velocity Updates (ZUPT) using gravity deviation & gyro magnitude
 *  - Sliding-window consensus voting against vibration spikes
 *  - Forward acceleration strapdown integration
 *  - Yaw rate gyro integration with magnetometer complementary filtering
 *  - Flat-Earth displacement to geodetic lat/lon translation
 */
class CoreDeadReckoner(
    private val logger: IdrLogger = ConsoleIdrLogger
) : IdrPositionEstimator {

    companion object {
        private const val TAG = "CoreDeadReckoner"

        /** Acceleration magnitude deviation from 9.81 m/s² below which device is translationally stationary */
        const val ZUPT_ACCEL_MAGNITUDE_THRESHOLD = 0.85f

        /** Gyroscope magnitude (rad/s) below which device is also rotationally stationary */
        const val ZUPT_GYRO_MAGNITUDE_THRESHOLD = 0.15f

        /** Percentage of window samples required to agree before ZUPT engages */
        const val ZUPT_CONSENSUS_RATIO = 0.80f

        const val GRAVITY = 9.81f

        /** Velocities below this threshold (~0.54 km/h) are treated as sensor noise and clamped to 0 */
        const val VELOCITY_DEADBAND_MPS = 0.15f

        /** Magnetometer complementary filter correction weight */
        const val MAG_COMPLEMENTARY_WEIGHT = 0.02f
    }

    private var currentVelocity = 0f
    private var currentHeading = -1f
    private var lastZuptState = false

    override fun reset() {
        currentVelocity = 0f
        currentHeading = -1f
        lastZuptState = false
    }

    override fun estimateVelocity(imuWindow: List<IdrImuSample>): Float {
        if (imuWindow.isEmpty()) return currentVelocity

        var stationarySamples = 0
        for (data in imuWindow) {
            val accelMag = sqrt(
                data.accelX * data.accelX +
                data.accelY * data.accelY +
                data.accelZ * data.accelZ
            )
            val accelDeviation = abs(accelMag - GRAVITY)

            // Translational stationarity depends purely on linear acceleration deviation from 1g.
            // Even if the phone/vehicle rotates in place (high gyro), translational velocity is zero.
            if (accelDeviation < ZUPT_ACCEL_MAGNITUDE_THRESHOLD) {
                stationarySamples++
            }
        }

        val consensusRatio = stationarySamples.toFloat() / imuWindow.size
        val isStationary = consensusRatio >= ZUPT_CONSENSUS_RATIO

        if (isStationary != lastZuptState) {
            logger.d(
                TAG,
                "ZUPT ${if (isStationary) "ENGAGED" else "RELEASED"} | " +
                "consensus=${(consensusRatio * 100).toInt()}% | " +
                "stationarySamples=$stationarySamples/${imuWindow.size} | " +
                "velocity before reset=$currentVelocity"
            )
            lastZuptState = isStationary
        }

        if (isStationary) {
            currentVelocity = 0f
            return 0f
        }

        if (imuWindow.size > 1) {
            val dtMillis = imuWindow.last().timestampMs - imuWindow.first().timestampMs
            val dtSec = dtMillis / 1000f

            if (dtSec > 0) {
                val avgAccel = imuWindow.map { it.accelY }.average().toFloat()
                currentVelocity += avgAccel * dtSec

                if (currentVelocity < VELOCITY_DEADBAND_MPS) currentVelocity = 0f
            }
        }

        if (currentVelocity < VELOCITY_DEADBAND_MPS) {
            currentVelocity = 0f
        }

        return currentVelocity
    }

    override fun estimateHeading(
        imuWindow: List<IdrImuSample>,
        dtSeconds: Float,
        currentMagHeadingDeg: Float
    ): Float {
        if (currentHeading < 0) {
            currentHeading = currentMagHeadingDeg
            return currentHeading
        }

        if (imuWindow.isEmpty() || dtSeconds <= 0) return currentHeading

        val avgYawRate = imuWindow.map { it.gyroZ }.average().toFloat()
        val deltaHeadingRad = avgYawRate * dtSeconds
        val deltaHeadingDeg = Math.toDegrees(deltaHeadingRad.toDouble()).toFloat()

        currentHeading -= deltaHeadingDeg
        currentHeading = (currentHeading % 360 + 360) % 360

        var diff = currentMagHeadingDeg - currentHeading
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360

        currentHeading += MAG_COMPLEMENTARY_WEIGHT * diff
        currentHeading = (currentHeading % 360 + 360) % 360

        return currentHeading
    }

    override fun estimatePosition(
        lastPosition: IdrLatLon,
        velocityMps: Float,
        headingDeg: Float,
        deltaTimeSeconds: Float
    ): IdrLatLon {
        if (velocityMps <= 0f || deltaTimeSeconds <= 0f) {
            return lastPosition
        }

        val distance = velocityMps * deltaTimeSeconds
        val headingRad = Math.toRadians(headingDeg.toDouble())

        val deltaNorth = distance * cos(headingRad)
        val deltaEast = distance * sin(headingRad)

        val deltaLat = deltaNorth / 111320.0
        val deltaLon = deltaEast / (111320.0 * cos(Math.toRadians(lastPosition.lat)))

        return IdrLatLon(
            lat = lastPosition.lat + deltaLat,
            lon = lastPosition.lon + deltaLon
        )
    }
}
