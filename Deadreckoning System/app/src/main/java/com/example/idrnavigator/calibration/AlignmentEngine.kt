package com.example.idrnavigator.calibration

import android.hardware.SensorManager
import com.example.idrnavigator.sensors.GpsData
import com.example.idrnavigator.sensors.ImuData
import kotlin.math.cos
import kotlin.math.sin

class AlignmentEngine {
    var isCalibrated = false
        private set

    /** True if phone mount slip was detected and re-calibration was triggered */
    var isMountSlipped = false
        private set

    // Gravity vector low-pass filter
    private val alpha = 0.8f
    private val gravity = FloatArray(3)
    private var hasGravity = false

    // Locked gravity snapshot at the time of calibration
    private val lockedGravity = FloatArray(3)

    // Slip detection parameters
    private var sustainedSlipCounter = 0
    companion object {
        // Angle threshold ~15 degrees: cos(15 deg) ≈ 0.966
        private const val SLIP_COS_THRESHOLD = 0.966f
        // ~1.5 seconds of sustained deviation at 50Hz
        private const val SLIP_CONFIRMATION_SAMPLES = 75
    }

    // Fixed calibration snapshot
    private val rMatrix = FloatArray(9)
    private var gpsBearingRad = 0f

    fun reset() {
        isCalibrated = false
        hasGravity = false
        isMountSlipped = false
        sustainedSlipCounter = 0
    }

    fun triggerRecalibrate() {
        isCalibrated = false
        isMountSlipped = false
        sustainedSlipCounter = 0
    }

    fun processAndAlign(rawImu: ImuData, gps: GpsData): ImuData {
        // Continuously update low-pass filtered gravity
        if (!hasGravity) {
            gravity[0] = rawImu.accelX
            gravity[1] = rawImu.accelY
            gravity[2] = rawImu.accelZ
            hasGravity = true
        } else {
            gravity[0] = alpha * gravity[0] + (1 - alpha) * rawImu.accelX
            gravity[1] = alpha * gravity[1] + (1 - alpha) * rawImu.accelY
            gravity[2] = alpha * gravity[2] + (1 - alpha) * rawImu.accelZ
        }

        if (!isCalibrated) {
            // Check if we can lock calibration: speed > 4m/s (~15km/h) and has valid bearing
            if (gps.hasFix && gps.hasBearing && gps.speed > 4f) {
                val mag = floatArrayOf(rawImu.magX, rawImu.magY, rawImu.magZ)
                val success = SensorManager.getRotationMatrix(rMatrix, null, gravity, mag)
                if (success) {
                    gpsBearingRad = Math.toRadians(gps.bearing.toDouble()).toFloat()
                    lockedGravity[0] = gravity[0]
                    lockedGravity[1] = gravity[1]
                    lockedGravity[2] = gravity[2]
                    isCalibrated = true
                    isMountSlipped = false
                    sustainedSlipCounter = 0
                }
            }
            
            // If not calibrated, return raw data
            if (!isCalibrated) return rawImu
        } else {
            // Check for phone mount slip: compute dot product of normalized gravity vectors
            val dot = gravity[0] * lockedGravity[0] + gravity[1] * lockedGravity[1] + gravity[2] * lockedGravity[2]
            val magCurrent = kotlin.math.sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
            val magLocked = kotlin.math.sqrt(lockedGravity[0] * lockedGravity[0] + lockedGravity[1] * lockedGravity[1] + lockedGravity[2] * lockedGravity[2])

            if (magCurrent > 1e-3f && magLocked > 1e-3f) {
                val cosTheta = (dot / (magCurrent * magLocked)).coerceIn(-1.0f, 1.0f)
                if (cosTheta < SLIP_COS_THRESHOLD) {
                    sustainedSlipCounter++
                    if (sustainedSlipCounter >= SLIP_CONFIRMATION_SAMPLES) {
                        isMountSlipped = true
                        isCalibrated = false // Invalidate alignment to trigger auto-recalibration
                        sustainedSlipCounter = 0
                    }
                } else {
                    sustainedSlipCounter = (sustainedSlipCounter - 1).coerceAtLeast(0)
                }
            }
        }

        // Once calibrated, apply the fixed rotation to all sensors
        val alignedAccel = alignVector(rawImu.accelX, rawImu.accelY, rawImu.accelZ)
        val alignedGyro = alignVector(rawImu.gyroX, rawImu.gyroY, rawImu.gyroZ)
        val alignedMag = alignVector(rawImu.magX, rawImu.magY, rawImu.magZ)

        return ImuData(
            accelX = alignedAccel[0], accelY = alignedAccel[1], accelZ = alignedAccel[2],
            gyroX = alignedGyro[0], gyroY = alignedGyro[1], gyroZ = alignedGyro[2],
            magX = alignedMag[0], magY = alignedMag[1], magZ = alignedMag[2],
            timestamp = rawImu.timestamp
        )
    }

    private fun alignVector(x: Float, y: Float, z: Float): FloatArray {
        // Step 1: Rotate from Phone Frame to Earth Frame (East, North, Up)
        val earthX = rMatrix[0] * x + rMatrix[1] * y + rMatrix[2] * z
        val earthY = rMatrix[3] * x + rMatrix[4] * y + rMatrix[5] * z
        val earthZ = rMatrix[6] * x + rMatrix[7] * y + rMatrix[8] * z

        // Step 2: Rotate around Z to align Earth North with Vehicle Forward
        // gpsBearing is clockwise from North. We want to rotate the Earth frame by -gpsBearing
        // so that the new Y axis matches the car's forward direction.
        val cosB = cos(-gpsBearingRad)
        val sinB = sin(-gpsBearingRad)

        val carX = earthX * cosB - earthY * sinB
        val carY = earthX * sinB + earthY * cosB
        val carZ = earthZ // Up remains Up

        return floatArrayOf(carX, carY, carZ)
    }
}
