package com.example.idr.core.estimator

import com.example.idr.core.model.IdrLatLon
import kotlin.math.cos
import kotlin.math.sin

/**
 * 8-State Error-State Extended Kalman Filter for Dead Reckoning with:
 *  - Non-Holonomic Constraints (NHC): Lateral velocity vy_b ≈ 0
 *  - Forward Velocity Aiding: TinyTCN speed observation vx_b ≈ v_tcn
 *  - Zero-Velocity Updates (ZUPT): vx_b ≈ 0, vy_b ≈ 0 when stopped
 *  - GPS position aiding: (x, y) updates when GNSS is available
 *
 * State vector:
 *   x[0] = x (local East position, meters)
 *   x[1] = y (local North position, meters)
 *   x[2] = psi (heading, radians; 0 = North, pi/2 = East)
 *   x[3] = vx_b (forward body velocity, m/s)
 *   x[4] = vy_b (lateral body velocity, m/s)
 *   x[5] = b_ax (forward accelerometer bias, m/s²)
 *   x[6] = b_ay (lateral accelerometer bias, m/s²)
 *   x[7] = b_g (yaw rate gyro bias, rad/s)
 */
class ErrorStateEkf(
    var originLat: Double = 0.0,
    var originLon: Double = 0.0
) {
    companion object {
        const val STATE_DIM = 8

        // Process noise parameters
        const val ACC_BIAS_STD = 0.05f
        const val ACC_NOISE_STD = 0.06f
        const val ACC_BIAS_RW = 0.0008f
        const val GYRO_BIAS_STD = 0.01047f // rad/s (~0.6 deg/s)
        const val GYRO_NOISE_STD = 0.00087f // rad/s (~0.05 deg/s)
        const val GYRO_BIAS_RW = 0.00017f // rad/s (~0.01 deg/s)

        // Measurement noise variances
        const val R_NHC = 0.02f * 0.02f
        const val R_ZUPT = 0.01f * 0.01f
        const val DEFAULT_R_VEL = 8.0f * 8.0f // TCN noise variance (~8 m/s)
        const val DEFAULT_R_GPS = 5.0f * 5.0f

        /** Velocities below this threshold (~0.54 km/h) are treated as stationary noise */
        const val VELOCITY_DEADBAND_MPS = 0.15f

        const val METERS_PER_DEGREE_LAT = 111320.0
    }

    val state = FloatArray(STATE_DIM)
    val P = Array(STATE_DIM) { FloatArray(STATE_DIM) }
    private val Q = Array(STATE_DIM) { FloatArray(STATE_DIM) }

    // Pre-allocated scratch buffers to achieve zero allocations during 100Hz predict/update cycles
    private val bufferFP = Array(STATE_DIM) { FloatArray(STATE_DIM) }
    private val bufferNewP = Array(STATE_DIM) { FloatArray(STATE_DIM) }
    private val bufferKGps = Array(STATE_DIM) { FloatArray(2) }
    private val bufferKScalar = FloatArray(STATE_DIM)

    var hasOrigin: Boolean = false
        private set

    init {
        reset()
    }

    fun reset() {
        for (i in 0 until STATE_DIM) {
            state[i] = 0f
            for (j in 0 until STATE_DIM) {
                P[i][j] = 0f
                Q[i][j] = 0f
            }
        }

        // Initial covariance
        P[0][0] = 1.0f
        P[1][1] = 1.0f
        P[2][2] = 0.0012f // (2 deg)^2 in rad
        P[3][3] = 0.5f
        P[4][4] = 0.5f
        P[5][5] = ACC_BIAS_STD * ACC_BIAS_STD
        P[6][6] = ACC_BIAS_STD * ACC_BIAS_STD
        P[7][7] = GYRO_BIAS_STD * GYRO_BIAS_STD

        hasOrigin = false
    }

    fun setOrigin(lat: Double, lon: Double, initialHeadingRad: Float = 0f) {
        originLat = lat
        originLon = lon
        hasOrigin = true
        state[0] = 0f
        state[1] = 0f
        state[2] = initialHeadingRad
    }

    /**
     * Prediction step: integrate IMU body forward accel (ax), lateral accel (ay), and yaw rate (gz).
     */
    fun predict(axBody: Float, ayBody: Float, gzBody: Float, dt: Float) {
        if (dt <= 0f) return

        val heading = state[2]
        val vx = state[3]
        val vy = state[4]
        val bax = state[5]
        val bay = state[6]
        val bg = state[7]

        val gyroMeas = gzBody - bg
        val axMeas = axBody - bax
        val ayMeas = ayBody - bay

        // 1. State propagation
        val sinH = sin(heading)
        val cosH = cos(heading)

        val effVx = if (kotlin.math.abs(vx) < VELOCITY_DEADBAND_MPS) 0f else vx
        val effVy = if (kotlin.math.abs(vy) < VELOCITY_DEADBAND_MPS) 0f else vy

        // North-East frame: x = East, y = North
        state[0] += (effVx * sinH + effVy * cosH) * dt // dx (East)
        state[1] += (effVx * cosH - effVy * sinH) * dt // dy (North)
        state[2] += gyroMeas * dt // heading
        state[3] += axMeas * dt // forward velocity
        state[4] += ayMeas * dt // lateral velocity

        if (state[3] < 0f || kotlin.math.abs(state[3]) < VELOCITY_DEADBAND_MPS) state[3] = 0f
        if (kotlin.math.abs(state[4]) < VELOCITY_DEADBAND_MPS) state[4] = 0f

        // Keep heading in [-pi, pi]
        state[2] = normalizeAngleRad(state[2])

        // 2. Process noise Q
        val gyroNoise = GYRO_NOISE_STD * dt
        val accNoise = ACC_NOISE_STD * dt
        val accRw = ACC_BIAS_RW * kotlin.math.sqrt(dt)
        val gyroRw = GYRO_BIAS_RW * kotlin.math.sqrt(dt)

        Q[2][2] = gyroNoise * gyroNoise
        Q[3][3] = accNoise * accNoise
        Q[4][4] = accNoise * accNoise
        Q[5][5] = accRw * accRw
        Q[6][6] = accRw * accRw
        Q[7][7] = gyroRw * gyroRw

        // 3. Jacobian F = I + dF
        // F[0, 2] = (vx * cosH - vy * sinH) * dt
        // F[0, 3] = sinH * dt
        // F[0, 4] = cosH * dt
        // F[1, 2] = -(vx * sinH + vy * cosH) * dt
        // F[1, 3] = cosH * dt
        // F[1, 4] = -sinH * dt
        // F[2, 7] = -dt
        // F[3, 5] = -dt
        // F[4, 6] = -dt

        val F02 = (vx * cosH - vy * sinH) * dt
        val F03 = sinH * dt
        val F04 = cosH * dt

        val F12 = -(vx * sinH + vy * cosH) * dt
        val F13 = cosH * dt
        val F14 = -sinH * dt

        // Compute P_new = F * P * F^T + Q in-place using pre-allocated bufferFP
        for (i in 0 until STATE_DIM) {
            for (j in 0 until STATE_DIM) {
                var sum = P[i][j]
                if (i == 0) {
                    sum += F02 * P[2][j] + F03 * P[3][j] + F04 * P[4][j]
                } else if (i == 1) {
                    sum += F12 * P[2][j] + F13 * P[3][j] + F14 * P[4][j]
                } else if (i == 2) {
                    sum += -dt * P[7][j]
                } else if (i == 3) {
                    sum += -dt * P[5][j]
                } else if (i == 4) {
                    sum += -dt * P[6][j]
                }
                bufferFP[i][j] = sum
            }
        }

        for (i in 0 until STATE_DIM) {
            for (j in 0 until STATE_DIM) {
                var sum = bufferFP[i][j]
                if (j == 0) {
                    sum += bufferFP[i][2] * F02 + bufferFP[i][3] * F03 + bufferFP[i][4] * F04
                } else if (j == 1) {
                    sum += bufferFP[i][2] * F12 + bufferFP[i][3] * F13 + bufferFP[i][4] * F14
                } else if (j == 2) {
                    sum += bufferFP[i][7] * -dt
                } else if (j == 3) {
                    sum += bufferFP[i][5] * -dt
                } else if (j == 4) {
                    sum += bufferFP[i][6] * -dt
                }
                P[i][j] = sum + Q[i][j]
            }
        }
        sanitizeState()
    }

    /**
     * Non-Holonomic Constraint (NHC) measurement update:
     * Lateral velocity vy_b = 0 with small noise variance.
     */
    fun updateNhc(rNhc: Float = R_NHC) {
        updateScalar(measuredIndex = 4, targetValue = 0f, variance = rNhc)
        sanitizeState()
    }

    /**
     * Velocity aiding update: forward velocity vx_b = v_meas (m/s) from TCN or odometer.
     */
    fun updateVelocity(measuredForwardMps: Float, variance: Float = DEFAULT_R_VEL) {
        val safeVel = if (measuredForwardMps.isNaN() || measuredForwardMps.isInfinite()) 0f else measuredForwardMps
        updateScalar(measuredIndex = 3, targetValue = safeVel, variance = variance)
        sanitizeState()
    }

    /**
     * Zero-Velocity Update (ZUPT): clamp both forward and lateral velocity to zero.
     */
    fun updateZupt() {
        updateScalar(measuredIndex = 3, targetValue = 0f, variance = R_ZUPT)
        updateScalar(measuredIndex = 4, targetValue = 0f, variance = R_ZUPT)
        // Hard-clamp velocity states so residual integration is strictly zeroed
        state[3] = 0f
        state[4] = 0f
        P[3][3] = minOf(P[3][3], R_ZUPT)
        P[4][4] = minOf(P[4][4], R_ZUPT)
        sanitizeState()
    }

    /**
     * GPS position update in local coordinates (meters from origin).
     */
    fun updateGpsPosition(lat: Double, lon: Double, accuracyMeters: Float) {
        if (!hasOrigin) {
            setOrigin(lat, lon, state[2])
            return
        }

        val (gpsX, gpsY) = latLonToLocal(lat, lon)
        val rGps = (accuracyMeters.coerceAtLeast(1.0f) * accuracyMeters.coerceAtLeast(1.0f))

        // H = [1 0 0 0 0 0 0 0; 0 1 0 0 0 0 0 0]
        // S = [P00 + rGps, P01; P10, P11 + rGps]
        val s00 = P[0][0] + rGps
        val s01 = P[0][1]
        val s10 = P[1][0]
        val s11 = P[1][1] + rGps

        val det = s00 * s11 - s01 * s10
        if (kotlin.math.abs(det) < 1e-9f) return

        val invDet = 1.0f / det
        val invS00 = s11 * invDet
        val invS01 = -s01 * invDet
        val invS10 = -s10 * invDet
        val invS11 = s00 * invDet

        val y0 = gpsX.toFloat() - state[0]
        val y1 = gpsY.toFloat() - state[1]

        // Kalman gain K = P * H^T * inv(S), where P * H^T is columns 0 and 1 of P
        for (i in 0 until STATE_DIM) {
            val pI0 = P[i][0]
            val pI1 = P[i][1]
            bufferKGps[i][0] = pI0 * invS00 + pI1 * invS10
            bufferKGps[i][1] = pI0 * invS01 + pI1 * invS11
        }

        // State update
        for (i in 0 until STATE_DIM) {
            state[i] += bufferKGps[i][0] * y0 + bufferKGps[i][1] * y1
        }
        state[2] = normalizeAngleRad(state[2])

        // Covariance update: P = (I - K*H) * P using pre-allocated bufferNewP
        for (i in 0 until STATE_DIM) {
            for (j in 0 until STATE_DIM) {
                var sum = P[i][j]
                sum -= bufferKGps[i][0] * P[0][j] + bufferKGps[i][1] * P[1][j]
                bufferNewP[i][j] = sum
            }
        }
        for (i in 0 until STATE_DIM) {
            for (j in 0 until STATE_DIM) {
                P[i][j] = bufferNewP[i][j]
            }
        }
        sanitizeState()
    }

    /**
     * Exact scalar Kalman update for 1D measurements.
     */
    private fun updateScalar(measuredIndex: Int, targetValue: Float, variance: Float) {
        val y = targetValue - state[measuredIndex]
        val S = P[measuredIndex][measuredIndex] + variance
        if (S <= 1e-9f) return

        val invS = 1.0f / S
        for (i in 0 until STATE_DIM) {
            bufferKScalar[i] = P[i][measuredIndex] * invS
        }

        // Update state
        for (i in 0 until STATE_DIM) {
            state[i] += bufferKScalar[i] * y
        }
        if (measuredIndex == 2) {
            state[2] = normalizeAngleRad(state[2])
        }

        // Update covariance: P = (I - K * H) * P
        for (i in 0 until STATE_DIM) {
            val ki = bufferKScalar[i]
            for (j in 0 until STATE_DIM) {
                P[i][j] -= ki * P[measuredIndex][j]
            }
        }
    }

    /**
     * Checks all state elements and covariance diagonals for NaN or Infinite values.
     * If corruption is detected, resets corrupted values to safe defaults to prevent runaway.
     */
    fun sanitizeState() {
        var corrupted = false
        for (i in 0 until STATE_DIM) {
            if (state[i].isNaN() || state[i].isInfinite()) {
                corrupted = true
                state[i] = 0f
            }
        }
        for (i in 0 until STATE_DIM) {
            for (j in 0 until STATE_DIM) {
                if (P[i][j].isNaN() || P[i][j].isInfinite()) {
                    corrupted = true
                    P[i][j] = if (i == j) 1.0f else 0f
                }
            }
        }
        if (corrupted) {
            // Reset velocity and biases on numerical corruption
            state[3] = 0f
            state[4] = 0f
            state[5] = 0f
            state[6] = 0f
            state[7] = 0f
            // Re-seed covariance
            P[0][0] = 1.0f
            P[1][1] = 1.0f
            P[2][2] = 0.0012f
            P[3][3] = 0.5f
            P[4][4] = 0.5f
            P[5][5] = ACC_BIAS_STD * ACC_BIAS_STD
            P[6][6] = ACC_BIAS_STD * ACC_BIAS_STD
            P[7][7] = GYRO_BIAS_STD * GYRO_BIAS_STD
        }
    }

    /**
     * Convert local (East, North) meters to geodetic WGS84 LatLon.
     */
    fun getEstimatedLatLon(): IdrLatLon {
        if (!hasOrigin) return IdrLatLon(0.0, 0.0)
        val eastMeters = state[0].toDouble()
        val northMeters = state[1].toDouble()

        if (eastMeters.isNaN() || eastMeters.isInfinite() || northMeters.isNaN() || northMeters.isInfinite()) {
            return IdrLatLon(originLat, originLon)
        }

        val deltaLat = northMeters / METERS_PER_DEGREE_LAT
        val cosLat = cos(Math.toRadians(originLat)).coerceAtLeast(0.01)
        val deltaLon = eastMeters / (METERS_PER_DEGREE_LAT * cosLat)

        val outLat = originLat + deltaLat
        val outLon = originLon + deltaLon

        return if (outLat.isNaN() || outLon.isNaN() || outLat.isInfinite() || outLon.isInfinite()) {
            IdrLatLon(originLat, originLon)
        } else {
            IdrLatLon(outLat, outLon)
        }
    }

    /**
     * Convert WGS84 LatLon to local (East, North) meters relative to origin.
     */
    fun latLonToLocal(lat: Double, lon: Double): Pair<Double, Double> {
        val deltaLat = lat - originLat
        val deltaLon = lon - originLon

        val northMeters = deltaLat * METERS_PER_DEGREE_LAT
        val eastMeters = deltaLon * (METERS_PER_DEGREE_LAT * cos(Math.toRadians(originLat)))
        return Pair(eastMeters, northMeters)
    }

    val forwardVelocityMps: Float
        get() {
            val v = state[3]
            if (v.isNaN() || v.isInfinite() || v < VELOCITY_DEADBAND_MPS) return 0f
            return v
        }

    val headingDeg: Float
        get() {
            val rad = state[2]
            if (rad.isNaN() || rad.isInfinite()) return 0f
            return (Math.toDegrees(rad.toDouble()).toFloat() % 360f + 360f) % 360f
        }

    private fun normalizeAngleRad(angle: Float): Float {
        var a = angle
        if (a.isNaN() || a.isInfinite()) return 0f
        while (a > Math.PI.toFloat()) a -= (2.0 * Math.PI).toFloat()
        while (a < -Math.PI.toFloat()) a += (2.0 * Math.PI).toFloat()
        return a
    }
}
