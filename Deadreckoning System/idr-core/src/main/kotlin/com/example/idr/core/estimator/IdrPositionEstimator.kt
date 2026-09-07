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
    fun reset(seedHeading: Float = -1f)
}

class CoreDeadReckoner(
    private val logger: IdrLogger = ConsoleIdrLogger
) : IdrPositionEstimator {

    companion object {
        private const val TAG = "CoreDeadReckoner"

        const val ZUPT_ACCEL_MAGNITUDE_THRESHOLD = 0.85f
        const val ZUPT_GYRO_MAGNITUDE_THRESHOLD = 0.15f
        const val ZUPT_CONSENSUS_RATIO = 0.80f
        const val GRAVITY = 9.81f
        const val VELOCITY_DEADBAND_MPS = 0.15f
        const val MAG_COMPLEMENTARY_WEIGHT = 0.00f // Disabled to prevent tunnel rebar from destroying heading

        const val MAX_PLAUSIBLE_ACCEL_MPS2 = 1.5f
        const val SUSTAINED_HIGH_ACCEL_LIMIT = 3
        const val MAX_PLAUSIBLE_VELOCITY_MPS = 50f
    }

    private var currentVelocity = 0f
    private var currentHeading = -1f
    private var lastZuptState = false
    private var sustainedHighAccelCount = 0

    override fun reset(seedHeading: Float) {
        currentVelocity = 0f
        lastZuptState = false
        sustainedHighAccelCount = 0
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
            val gyroMag = sqrt(
                data.gyroX * data.gyroX +
                        data.gyroY * data.gyroY +
                        data.gyroZ * data.gyroZ
            )

            if (accelMag < ZUPT_ACCEL_MAGNITUDE_THRESHOLD && gyroMag < ZUPT_GYRO_MAGNITUDE_THRESHOLD) {
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
            sustainedHighAccelCount = 0
            return 0f
        }

        if (imuWindow.size > 1) {
            val dtMillis = imuWindow.last().timestampMs - imuWindow.first().timestampMs
            val dtSec = dtMillis / 1000f

            if (dtSec > 0) {
                val avgAccel = imuWindow.map { it.accelY }.average().toFloat()

                if (abs(avgAccel) > MAX_PLAUSIBLE_ACCEL_MPS2) {
                    sustainedHighAccelCount++
                    if (sustainedHighAccelCount > SUSTAINED_HIGH_ACCEL_LIMIT) {
                        logger.d(
                            TAG,
                            "REJECTED sustained implausible accel=$avgAccel " +
                                    "(tilt leakage guard, count=$sustainedHighAccelCount) — velocity forced to 0"
                        )
                        currentVelocity = 0f
                        return 0f
                    }
                } else {
                    sustainedHighAccelCount = 0
                }

                currentVelocity += avgAccel * dtSec
                currentVelocity = currentVelocity.coerceIn(0f, MAX_PLAUSIBLE_VELOCITY_MPS)

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