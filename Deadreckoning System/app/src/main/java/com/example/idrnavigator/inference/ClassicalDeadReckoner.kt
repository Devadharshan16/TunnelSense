package com.example.idrnavigator.inference

import android.util.Log
import com.example.idr.core.estimator.CoreDeadReckoner
import com.example.idr.core.logging.IdrLogger
import com.example.idr.core.model.IdrImuSample
import com.example.idr.core.model.IdrLatLon
import com.example.idrnavigator.sensors.ImuData

/**
 * Android logger adapter forwarding IDR Core events to Android Logcat.
 */
object AndroidIdrLogger : IdrLogger {
    override fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}

/**
 * Android adapter for Classical Dead Reckoning.
 * Delegates core numerical integration and 3D ZUPT logic to the portable [CoreDeadReckoner].
 */
class ClassicalDeadReckoner : PositionEstimator {

    companion object {
        const val ZUPT_ACCEL_MAGNITUDE_THRESHOLD = CoreDeadReckoner.ZUPT_ACCEL_MAGNITUDE_THRESHOLD
        const val ZUPT_GYRO_MAGNITUDE_THRESHOLD = CoreDeadReckoner.ZUPT_GYRO_MAGNITUDE_THRESHOLD
        const val ZUPT_CONSENSUS_RATIO = CoreDeadReckoner.ZUPT_CONSENSUS_RATIO
        const val GRAVITY = CoreDeadReckoner.GRAVITY
        const val MAG_COMPLEMENTARY_WEIGHT = CoreDeadReckoner.MAG_COMPLEMENTARY_WEIGHT
    }

    private val core = CoreDeadReckoner(logger = AndroidIdrLogger)

    override fun estimateVelocity(imuWindow: List<ImuData>): Float {
        val samples = imuWindow.map {
            IdrImuSample(
                timestampMs = it.timestamp,
                accelX = it.accelX, accelY = it.accelY, accelZ = it.accelZ,
                gyroX = it.gyroX, gyroY = it.gyroY, gyroZ = it.gyroZ,
                magX = it.magX, magY = it.magY, magZ = it.magZ
            )
        }
        return core.estimateVelocity(samples)
    }

    override fun estimateHeading(imuWindow: List<ImuData>, dt: Float, currentMagHeading: Float): Float {
        val samples = imuWindow.map {
            IdrImuSample(
                timestampMs = it.timestamp,
                accelX = it.accelX, accelY = it.accelY, accelZ = it.accelZ,
                gyroX = it.gyroX, gyroY = it.gyroY, gyroZ = it.gyroZ,
                magX = it.magX, magY = it.magY, magZ = it.magZ
            )
        }
        return core.estimateHeading(samples, dt, currentMagHeading)
    }

    override fun estimatePosition(
        lastPosition: LatLon,
        velocity: Float,
        headingDeg: Float,
        deltaTimeSeconds: Float
    ): LatLon {
        return core.estimatePosition(lastPosition, velocity, headingDeg, deltaTimeSeconds)
    }
}
