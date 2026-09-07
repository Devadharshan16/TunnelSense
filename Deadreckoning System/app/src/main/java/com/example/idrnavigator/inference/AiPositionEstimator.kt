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

    private var smoothedVelocityMps = 0f
    var rawPredictedKmH = 0f
        private set

    val lastInferenceLatencyMs: Long
        get() = onnxRunner.lastInferenceLatencyMs

    val isModelLoaded: Boolean
        get() = onnxRunner.isModelLoaded

    override fun estimateVelocity(imuWindow: List<ImuData>): Float {
        if (imuWindow.isEmpty()) return smoothedVelocityMps

        // 1. Check 3D Zero-Velocity Update (ZUPT)
        // If vehicle is physically stopped (e.g. red light / parked), clamp velocity to 0
        val isStationary = checkStationary(imuWindow)
        if (isStationary) {
            smoothedVelocityMps = 0f
            rawPredictedKmH = 0f
            return 0f
        }

        // 2. Feed IMU window samples into the model rolling buffer
        for (sample in imuWindow) {
            inputBuilder.addSample(sample)
        }

        // 3. Run ONNX model if ready and loaded
        if (onnxRunner.isModelLoaded && inputBuilder.isReady()) {
            val flatTensor = inputBuilder.buildNormalizedFlatTensor(onnxRunner.mean, onnxRunner.scale)
            if (flatTensor != null) {
                rawPredictedKmH = onnxRunner.predictVelocityKmH(flatTensor)
                val rawVelocityMps = rawPredictedKmH / 3.6f

                // 4. Apply EMA smoothing
                smoothedVelocityMps = if (smoothedVelocityMps == 0f) {
                    rawVelocityMps
                } else {
                    (VELOCITY_EMA_ALPHA * rawVelocityMps) + ((1f - VELOCITY_EMA_ALPHA) * smoothedVelocityMps)
                }
                return smoothedVelocityMps
            }
        }

        // Fallback to classical integration if ONNX is warming up or unavailable
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
        return classicalFallback.estimatePosition(lastPosition, velocity, headingDeg, deltaTimeSeconds)
    }

    private fun checkStationary(imuWindow: List<ImuData>): Boolean {
        var stationaryCount = 0
        for (data in imuWindow) {
            val accelMag = sqrt(data.accelX * data.accelX + data.accelY * data.accelY + data.accelZ * data.accelZ)
            val gyroMag = sqrt(data.gyroX * data.gyroX + data.gyroY * data.gyroY + data.gyroZ * data.gyroZ)

            if (abs(accelMag - GRAVITY) < ZUPT_ACCEL_THRESHOLD && gyroMag < ZUPT_GYRO_THRESHOLD) {
                stationaryCount++
            }
        }
        return (stationaryCount.toFloat() / imuWindow.size) >= CoreDeadReckoner.ZUPT_CONSENSUS_RATIO
    }

    fun reset() {
        inputBuilder.reset()
        smoothedVelocityMps = 0f
        rawPredictedKmH = 0f
    }
}
