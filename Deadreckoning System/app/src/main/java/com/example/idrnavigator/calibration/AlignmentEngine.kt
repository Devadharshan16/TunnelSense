package com.example.idrnavigator.calibration

import android.hardware.SensorManager
import android.util.Log
import com.example.idrnavigator.sensors.GpsData
import com.example.idrnavigator.sensors.ImuData
import com.example.idr.core.estimator.CoreDeadReckoner
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AlignmentEngine {
    var isCalibrated = false
        private set

    /** True if phone mount slip was detected and re-calibration was triggered */
    var isMountSlipped = false
        private set

    // Gravity vector low-pass filter — only updated during dynamically "calm" periods
    private val alpha = 0.8f
    private val gravity = FloatArray(3)
    private var hasGravity = false

    // Tracks how many calm samples have been fed into the gravity filter since
    // the last reset/recalibration. Calibration is gated on this reaching
    // MIN_CALM_GRAVITY_SAMPLES so the filter has converged on a clean gravity
    // direction, not one contaminated by braking/accelerating/cornering forces.
    private var calmGravitySamples = 0

    // Locked gravity snapshot at the time of calibration
    private val lockedGravity = FloatArray(3)

    // Slip detection parameters
    private var sustainedSlipCounter = 0

    companion object {
        private const val TAG = "AlignmentEngine"

        // Angle threshold ~15 degrees: cos(15 deg) ≈ 0.966
        private const val SLIP_COS_THRESHOLD = 0.966f
        // ~1.5 seconds of sustained deviation at 50Hz
        private const val SLIP_CONFIRMATION_SAMPLES = 75

        // --- Gravity capture gating ---
        // Raw accel magnitude must be within GRAVITY ± this tolerance to count as "calm"
        private const val GRAVITY_ACCEL_TOLERANCE = 0.8f
        // Raw gyro magnitude (rad/s) must be below this to count as "calm"
        // 0.15 rad/s ≈ 8.6 deg/s — allows gentle cruising turns, rejects hard cornering
        private const val GRAVITY_GYRO_MAX = 0.15f
        // Minimum number of calm samples the gravity filter must have absorbed
        // before calibration is allowed to lock. At ~50Hz sensor rate, 40 samples ≈ 0.8s.
        private const val MIN_CALM_GRAVITY_SAMPLES = 40
        // Sanity bounds for earthUp after calibration — if outside this range,
        // the gravity snapshot was likely contaminated
        private const val SANITY_EARTH_UP_MIN = 9.0f
        private const val SANITY_EARTH_UP_MAX = 10.6f
    }

    // Fixed calibration snapshot
    private val rMatrix = FloatArray(9)
    private var gpsBearingRad = 0f

    fun reset() {
        isCalibrated = false
        hasGravity = false
        isMountSlipped = false
        sustainedSlipCounter = 0
        calmGravitySamples = 0
    }

    fun triggerRecalibrate() {
        isCalibrated = false
        isMountSlipped = false
        sustainedSlipCounter = 0
        calmGravitySamples = 0
    }

    fun processAndAlign(rawImu: ImuData, gps: GpsData): ImuData {
        // --- Gravity filter: only update during dynamically calm periods ---
        val rawAccelMag = sqrt(
            rawImu.accelX * rawImu.accelX +
            rawImu.accelY * rawImu.accelY +
            rawImu.accelZ * rawImu.accelZ
        )
        val rawGyroMag = sqrt(
            rawImu.gyroX * rawImu.gyroX +
            rawImu.gyroY * rawImu.gyroY +
            rawImu.gyroZ * rawImu.gyroZ
        )

        val isCalm = abs(rawAccelMag - CoreDeadReckoner.GRAVITY) < GRAVITY_ACCEL_TOLERANCE
                  && rawGyroMag < GRAVITY_GYRO_MAX

        if (!hasGravity) {
            // Seed with the very first sample regardless — we need *something* for the filter
            gravity[0] = rawImu.accelX
            gravity[1] = rawImu.accelY
            gravity[2] = rawImu.accelZ
            hasGravity = true
            if (isCalm) calmGravitySamples = 1
        } else if (isCalm) {
            // Only feed the low-pass filter during calm periods so it converges
            // on the true gravity direction, not one contaminated by dynamic forces
            gravity[0] = alpha * gravity[0] + (1 - alpha) * rawImu.accelX
            gravity[1] = alpha * gravity[1] + (1 - alpha) * rawImu.accelY
            gravity[2] = alpha * gravity[2] + (1 - alpha) * rawImu.accelZ
            calmGravitySamples++
        }
        // During non-calm periods: do NOT update gravity[]. The last calm estimate persists.

        if (!isCalibrated) {
            // Calibration requires THREE simultaneous conditions:
            //   1. Vehicle is moving (speed > 4 m/s) with a valid GPS bearing — for heading
            //   2. GPS has a fix and bearing
            //   3. Gravity filter has converged from enough calm samples — for tilt
            if (gps.hasFix && gps.hasBearing && gps.speed > 4f
                && calmGravitySamples >= MIN_CALM_GRAVITY_SAMPLES) {

                val mag = floatArrayOf(rawImu.magX, rawImu.magY, rawImu.magZ)
                val success = SensorManager.getRotationMatrix(rMatrix, null, gravity, mag)
                if (success) {
                    // Sanity check: apply rMatrix to the gravity vector and verify
                    // earthUp captures the full magnitude (~9.81). If it doesn't,
                    // the gravity snapshot was contaminated and calibration is rejected.
                    val earthUp = rMatrix[6] * gravity[0] + rMatrix[7] * gravity[1] + rMatrix[8] * gravity[2]
                    val earthEast = rMatrix[0] * gravity[0] + rMatrix[1] * gravity[1] + rMatrix[2] * gravity[2]
                    val earthNorth = rMatrix[3] * gravity[0] + rMatrix[4] * gravity[1] + rMatrix[5] * gravity[2]

                    if (earthUp < SANITY_EARTH_UP_MIN || earthUp > SANITY_EARTH_UP_MAX) {
                        // Gravity snapshot was contaminated — reject and wait for a cleaner one
                        Log.w(TAG, "Calibration REJECTED: earthUp=${"%.2f".format(earthUp)} " +
                              "(expected ~9.81), earthEast=${"%.2f".format(earthEast)}, " +
                              "earthNorth=${"%.2f".format(earthNorth)}, " +
                              "calmSamples=$calmGravitySamples")
                        // Don't lock — will retry on next qualifying sample
                    } else {
                        gpsBearingRad = Math.toRadians(gps.bearing.toDouble()).toFloat()
                        lockedGravity[0] = gravity[0]
                        lockedGravity[1] = gravity[1]
                        lockedGravity[2] = gravity[2]
                        isCalibrated = true
                        isMountSlipped = false
                        sustainedSlipCounter = 0
                        Log.d(TAG, "Calibration LOCKED: earthUp=${"%.3f".format(earthUp)}, " +
                              "earthEast=${"%.3f".format(earthEast)}, " +
                              "earthNorth=${"%.3f".format(earthNorth)}, " +
                              "bearing=${"%.1f".format(gps.bearing)}°, " +
                              "calmSamples=$calmGravitySamples")
                    }
                }
            }

            // If not calibrated, return raw data
            if (!isCalibrated) return rawImu
        } else {
            // Check for phone mount slip: compute dot product of normalized gravity vectors
            // Note: slip detection still uses the continuously-updated (calm-only) gravity
            val dot = gravity[0] * lockedGravity[0] + gravity[1] * lockedGravity[1] + gravity[2] * lockedGravity[2]
            val magCurrent = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
            val magLocked = sqrt(lockedGravity[0] * lockedGravity[0] + lockedGravity[1] * lockedGravity[1] + lockedGravity[2] * lockedGravity[2])

            if (magCurrent > 1e-3f && magLocked > 1e-3f) {
                val cosTheta = (dot / (magCurrent * magLocked)).coerceIn(-1.0f, 1.0f)
                if (cosTheta < SLIP_COS_THRESHOLD) {
                    sustainedSlipCounter++
                    if (sustainedSlipCounter >= SLIP_CONFIRMATION_SAMPLES) {
                        isMountSlipped = true
                        isCalibrated = false // Invalidate alignment to trigger auto-recalibration
                        sustainedSlipCounter = 0
                        calmGravitySamples = 0 // Force re-accumulation of calm gravity
                        Log.w(TAG, "Mount SLIP detected — recalibration triggered, calmGravitySamples reset")
                    }
                } else {
                    sustainedSlipCounter = (sustainedSlipCounter - 1).coerceAtLeast(0)
                }
            }
        }

        // Once calibrated, apply the fixed rotation to all sensors
        val alignedAccel = alignVector(rawImu.accelX, rawImu.accelY, rawImu.accelZ)
        // Remove gravity from the Up axis (index 2) of accelerometer ONLY.
        // Gyro and mag do not contain a gravity component — do NOT touch them.
        // After this, a stationary device reads ~0 m/s² on all three axes.
        alignedAccel[2] = alignedAccel[2] - CoreDeadReckoner.GRAVITY
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
        val earthEast = rMatrix[0] * x + rMatrix[1] * y + rMatrix[2] * z
        val earthNorth = rMatrix[3] * x + rMatrix[4] * y + rMatrix[5] * z
        val earthUp = rMatrix[6] * x + rMatrix[7] * y + rMatrix[8] * z

        // Step 2: Rotate around Up axis by GPS Bearing (clockwise from North)
        // Car Forward (Y) = East * sin(bearing) + North * cos(bearing)
        // Car Right (X)   = East * cos(bearing) - North * sin(bearing)
        val sinB = sin(gpsBearingRad)
        val cosB = cos(gpsBearingRad)

        val carRight = earthEast * cosB - earthNorth * sinB
        val carForward = earthEast * sinB + earthNorth * cosB
        val carUp = earthUp

        return floatArrayOf(carRight, carForward, carUp)
    }
}
