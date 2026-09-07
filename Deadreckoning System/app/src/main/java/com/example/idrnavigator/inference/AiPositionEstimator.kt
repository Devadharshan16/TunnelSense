package com.example.idrnavigator.inference

import android.content.Context
import android.util.Log
import com.example.idr.core.estimator.CoreDeadReckoner
import com.example.idrnavigator.sensors.ImuData
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * AI-powered dead reckoning position estimator using TinyTCN ONNX velocity predictions.
 *
 * Architecture:
 * - Velocity: Predicted by TinyTCN from high-frequency vibration patterns + 3D ZUPT clamping
 * - Noise Filter: Exponential Moving Average (EMA) smoothing
 * - Heading: Gyro yaw-rate strapdown integration with magnetometer complementary correction
 * - Displacement: Geodetic Flat-Earth projection
 */
class AiPositionEstimator(
    context: Context,
    val onnxRunner: OnnxVelocityRunner = OnnxVelocityRunner(context)
) : PositionEstimator {

    companion object {
        private const val TAG = "AiPositionEstimator"

        /** Exponential moving average filter factor (0.0 to 1.0) to smooth high-frequency TCN vibration noise */
        const val VELOCITY_EMA_ALPHA = 0.35f

        /** ZUPT stationary detection thresholds */
        const val ZUPT_ACCEL_THRESHOLD = CoreDeadReckoner.ZUPT_ACCEL_MAGNITUDE_THRESHOLD
        const val ZUPT_GYRO_THRESHOLD = CoreDeadReckoner.ZUPT_GYRO_MAGNITUDE_THRESHOLD
        const val GRAVITY = CoreDeadReckoner.GRAVITY
    }

    private val inputBuilder = ModelInputBuilder(windowLength = 10, targetSampleIntervalMs = 100L)
    private val classicalFallback = ClassicalDeadReckoner()
    val ekf = com.example.idr.core.estimator.ErrorStateEkf()

    private var smoothedVelocityMps = 0f
    var rawPredictedKmH = 0f
        private set

    val lastInferenceLatencyMs: Long
        get() = onnxRunner.lastInferenceLatencyMs

    val isModelLoaded: Boolean
        get() = onnxRunner.isModelLoaded

    override fun estimateVelocity(imuWindow: List<ImuData>): Float {
        if (imuWindow.isEmpty()) return smoothedVelocityMps

        try {
            // Always feed IMU samples into rolling buffer so ONNX is continuously warm
            for (sample in imuWindow) {
                inputBuilder.addSample(sample)
            }

            // 1. Check 3D Zero-Velocity Update (ZUPT)
            val isStationary = checkStationary(imuWindow)
            if (isStationary) {
                ekf.updateZupt()
                smoothedVelocityMps = 0f
                rawPredictedKmH = 0f

                // Allow EKF heading to track in-place rotation while velocity remains clamped
                val latest = imuWindow.last()
                val dt = if (imuWindow.size > 1) {
                    (imuWindow.last().timestamp - imuWindow.first().timestamp).coerceAtLeast(10L) / 1000f
                } else 0.05f
                ekf.predict(axBody = 0f, ayBody = 0f, gzBody = latest.gyroZ, dt = dt)
                return 0f
            }

            // 2. Run ONNX model if ready and loaded
            if (onnxRunner.isModelLoaded && inputBuilder.isReady()) {
                val flatTensor = inputBuilder.buildNormalizedFlatTensor(onnxRunner.mean, onnxRunner.scale)
                if (flatTensor != null) {
                    rawPredictedKmH = onnxRunner.predictVelocityKmH(flatTensor)
                    val rawVelocityMps = rawPredictedKmH / 3.6f

                    // Check for NaN / Inf
                    val safeVelocityMps = if (rawVelocityMps.isNaN() || rawVelocityMps.isInfinite() || rawVelocityMps < CoreDeadReckoner.VELOCITY_DEADBAND_MPS) {
                        0f
                    } else rawVelocityMps

                    // 3. Update EKF with body acceleration, NHC, and TCN velocity aiding
                    val latest = imuWindow.last()
                    val dt = if (imuWindow.size > 1) {
                        (imuWindow.last().timestamp - imuWindow.first().timestamp).coerceAtLeast(10L) / 1000f
                    } else 0.05f

                    ekf.predict(axBody = latest.accelY, ayBody = latest.accelX, gzBody = latest.gyroZ, dt = dt)
                    ekf.updateVelocity(safeVelocityMps, variance = com.example.idr.core.estimator.ErrorStateEkf.DEFAULT_R_VEL)
                    ekf.updateNhc()

                    smoothedVelocityMps = ekf.forwardVelocityMps
                    return smoothedVelocityMps
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in AI velocity/EKF estimation cycle, falling back to classical", t)
        }

        // Fallback to classical integration if ONNX is warming up, unavailable, or threw error
        smoothedVelocityMps = classicalFallback.estimateVelocity(imuWindow)
        return smoothedVelocityMps
    }

    override fun estimateHeading(imuWindow: List<ImuData>, dt: Float, currentMagHeading: Float): Float {
        return classicalFallback.estimateHeading(imuWindow, dt, currentMagHeading)
    }

    override fun estimatePosition(
        lastPosition: LatLon,
        velocity: Float,
        headingDeg: Float,
        deltaTimeSeconds: Float
    ): LatLon {
        if (velocity <= 0f || deltaTimeSeconds <= 0f) return lastPosition
        return classicalFallback.estimatePosition(lastPosition, velocity, headingDeg, deltaTimeSeconds)
    }

    private fun checkStationary(imuWindow: List<ImuData>): Boolean {
        var stationaryCount = 0
        for (data in imuWindow) {
            val accelMag = sqrt(data.accelX * data.accelX + data.accelY * data.accelY + data.accelZ * data.accelZ)
            // Translational stationarity: Net acceleration is close to 1g (gravity),
            // meaning the device is not undergoing linear forward/lateral translation.
            // Even if the phone is rotated in hand (high gyro), translational motion is zero.
            if (abs(accelMag - GRAVITY) < ZUPT_ACCEL_THRESHOLD) {
                stationaryCount++
            }
        }
        return (stationaryCount.toFloat() / imuWindow.size) >= CoreDeadReckoner.ZUPT_CONSENSUS_RATIO
    }

    fun reset() {
        inputBuilder.reset()
        ekf.reset()
        smoothedVelocityMps = 0f
        rawPredictedKmH = 0f
    }
}
