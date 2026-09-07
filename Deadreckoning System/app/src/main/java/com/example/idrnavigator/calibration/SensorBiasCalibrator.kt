package com.example.idrnavigator.calibration

import android.content.Context
import android.util.Log
import com.example.idrnavigator.sensors.ImuData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

data class CalibrationState(
    val isCalibrating: Boolean = false,
    val isCalibrated: Boolean = false,
    val progress: Float = 0f,
    val secondsRemaining: Int = 0,
    val gyroBiasX: Float = 0f,
    val gyroBiasY: Float = 0f,
    val gyroBiasZ: Float = 0f,
    val accelScaleCorrection: Float = 1.0f,
    val movementDetected: Boolean = false
)

/**
 * Startup sensor bias calibrator.
 * Measures zero-rotation gyro bias and stationary 1g accelerometer scale
 * during a 3-5 second still window, then subtracts/scales all subsequent raw IMU readings.
 */
class SensorBiasCalibrator(context: Context) {

    companion object {
        private const val TAG = "SensorBiasCalibrator"
        private const val PREFS_NAME = "idr_sensor_calibration"
        private const val KEY_IS_CALIBRATED = "is_calibrated"
        private const val KEY_GYRO_BIAS_X = "gyro_bias_x"
        private const val KEY_GYRO_BIAS_Y = "gyro_bias_y"
        private const val KEY_GYRO_BIAS_Z = "gyro_bias_z"
        private const val KEY_ACCEL_SCALE = "accel_scale"
        const val DEFAULT_CALIBRATION_DURATION_MS = 3500L
        const val STANDARD_GRAVITY = 9.80665f
            private const val MOVEMENT_ACCEL_TOLERANCE = 0.75f
            private const val MOVEMENT_GYRO_THRESHOLD = 0.20f
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isCalibrated: Boolean = prefs.getBoolean(KEY_IS_CALIBRATED, false)
        private set

    var gyroBiasX: Float = prefs.getFloat(KEY_GYRO_BIAS_X, 0f)
        private set
    var gyroBiasY: Float = prefs.getFloat(KEY_GYRO_BIAS_Y, 0f)
        private set
    var gyroBiasZ: Float = prefs.getFloat(KEY_GYRO_BIAS_Z, 0f)
        private set
    var accelScaleCorrection: Float = prefs.getFloat(KEY_ACCEL_SCALE, 1.0f)
        private set

    private var isCalibrating = false
    private var calibrationStartTimeMs = 0L
    private var targetDurationMs = DEFAULT_CALIBRATION_DURATION_MS

    private var sampleCount = 0
    private var sumAx = 0.0
    private var sumAy = 0.0
    private var sumAz = 0.0
    private var sumGx = 0.0
    private var sumGy = 0.0
    private var sumGz = 0.0

    private val _stateFlow = MutableStateFlow(
        CalibrationState(
            isCalibrating = false,
            isCalibrated = isCalibrated,
            progress = if (isCalibrated) 1.0f else 0.0f,
            secondsRemaining = 0,
            gyroBiasX = gyroBiasX,
            gyroBiasY = gyroBiasY,
            gyroBiasZ = gyroBiasZ,
            accelScaleCorrection = accelScaleCorrection
        )
    )
    val stateFlow: StateFlow<CalibrationState> = _stateFlow.asStateFlow()

    @Synchronized
    fun startCalibration(durationMs: Long = DEFAULT_CALIBRATION_DURATION_MS) {
        targetDurationMs = durationMs
        calibrationStartTimeMs = System.currentTimeMillis()
        sampleCount = 0
        sumAx = 0.0
        sumAy = 0.0
        sumAz = 0.0
        sumGx = 0.0
        sumGy = 0.0
        sumGz = 0.0
        isCalibrating = true

        _stateFlow.value = _stateFlow.value.copy(
            isCalibrating = true,
            progress = 0f,
            secondsRemaining = ((durationMs + 999) / 1000).toInt()
        )
        Log.d(TAG, "Sensor bias calibration started for ${durationMs}ms")
    }

    @Synchronized
    fun resetCalibration() {
        isCalibrating = false
        isCalibrated = false
        gyroBiasX = 0f
        gyroBiasY = 0f
        gyroBiasZ = 0f
        accelScaleCorrection = 1.0f

        prefs.edit().clear().apply()

        _stateFlow.value = CalibrationState(
            isCalibrating = false,
            isCalibrated = false,
            progress = 0f,
            secondsRemaining = 0
        )
        Log.d(TAG, "Sensor bias calibration reset")
    }

    /**
     * Ingest raw sensor sample, update calibration if active, and return bias-corrected ImuData.
     */
    @Synchronized
    fun processAndApply(raw: ImuData): ImuData {
        if (isCalibrating) {
            val now = System.currentTimeMillis()
            val elapsed = now - calibrationStartTimeMs
            val accelMagnitude = sqrt(raw.accelX * raw.accelX + raw.accelY * raw.accelY + raw.accelZ * raw.accelZ)
            val gyroMagnitude = sqrt(raw.gyroX * raw.gyroX + raw.gyroY * raw.gyroY + raw.gyroZ * raw.gyroZ)
            val sampleIsStable = kotlin.math.abs(accelMagnitude - STANDARD_GRAVITY) <= MOVEMENT_ACCEL_TOLERANCE &&
                gyroMagnitude <= MOVEMENT_GYRO_THRESHOLD

            if (sampleIsStable) {
                sumAx += raw.accelX
                sumAy += raw.accelY
                sumAz += raw.accelZ
                sumGx += raw.gyroX
                sumGy += raw.gyroY
                sumGz += raw.gyroZ
                sampleCount++
            }

            val progress = (elapsed.toFloat() / targetDurationMs).coerceIn(0f, 1f)
            val remainingSec = (((targetDurationMs - elapsed).coerceAtLeast(0L) + 999) / 1000).toInt()

            _stateFlow.value = _stateFlow.value.copy(
                isCalibrating = true,
                progress = progress,
                secondsRemaining = remainingSec,
                movementDetected = !sampleIsStable
            )

            if (elapsed >= targetDurationMs && sampleCount >= 20) {
                // Complete calibration
                gyroBiasX = (sumGx / sampleCount).toFloat()
                gyroBiasY = (sumGy / sampleCount).toFloat()
                gyroBiasZ = (sumGz / sampleCount).toFloat()

                val meanAx = (sumAx / sampleCount).toFloat()
                val meanAy = (sumAy / sampleCount).toFloat()
                val meanAz = (sumAz / sampleCount).toFloat()
                val measuredMag = sqrt(meanAx * meanAx + meanAy * meanAy + meanAz * meanAz)

                accelScaleCorrection = if (measuredMag in 7.0f..12.0f) {
                    STANDARD_GRAVITY / measuredMag
                } else {
                    1.0f
                }

                isCalibrated = true
                isCalibrating = false

                prefs.edit()
                    .putBoolean(KEY_IS_CALIBRATED, true)
                    .putFloat(KEY_GYRO_BIAS_X, gyroBiasX)
                    .putFloat(KEY_GYRO_BIAS_Y, gyroBiasY)
                    .putFloat(KEY_GYRO_BIAS_Z, gyroBiasZ)
                    .putFloat(KEY_ACCEL_SCALE, accelScaleCorrection)
                    .apply()

                _stateFlow.value = CalibrationState(
                    isCalibrating = false,
                    isCalibrated = true,
                    progress = 1.0f,
                    secondsRemaining = 0,
                    gyroBiasX = gyroBiasX,
                    gyroBiasY = gyroBiasY,
                    gyroBiasZ = gyroBiasZ,
                    accelScaleCorrection = accelScaleCorrection,
                    movementDetected = false
                )

                Log.d(TAG, "Calibration completed: samples=$sampleCount, gyroBias=($gyroBiasX, $gyroBiasY, $gyroBiasZ), accelScale=$accelScaleCorrection")
            }
        }

        if (isCalibrated) {
            return ImuData(
                accelX = raw.accelX * accelScaleCorrection,
                accelY = raw.accelY * accelScaleCorrection,
                accelZ = raw.accelZ * accelScaleCorrection,
                gyroX = raw.gyroX - gyroBiasX,
                gyroY = raw.gyroY - gyroBiasY,
                gyroZ = raw.gyroZ - gyroBiasZ,
                magX = raw.magX,
                magY = raw.magY,
                magZ = raw.magZ,
                timestamp = raw.timestamp
            )
        }

        return raw
    }
}
