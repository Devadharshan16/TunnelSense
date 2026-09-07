package com.example.idrnavigator.inference

import com.example.idrnavigator.sensors.ImuData
import kotlin.math.sqrt

/**
 * Builds sliding window feature tensors for the TinyTCN ONNX velocity model.
 *
 * Model Requirements:
 * - Window Length: 10 timesteps (1.0 second at 10 Hz)
 * - Input Tensor Shape: [1, 11, 10] (Channel-first: Batch=1, Channels=11, Timesteps=10)
 * - 11 Features in exact order:
 *     0: acc_x
 *     1: acc_y
 *     2: acc_z
 *     3: gyro_x
 *     4: gyro_y
 *     5: gyro_z
 *     6: acc_mag  = sqrt(ax^2 + ay^2 + az^2)
 *     7: gyro_mag = sqrt(gx^2 + gy^2 + gz^2)
 *     8: jerk_x   = ax[t] - ax[t-1]
 *     9: jerk_y   = ay[t] - ay[t-1]
 *    10: jerk_z   = az[t] - az[t-1]
 */
class BiquadLowPassFilter(
    cutoffHz: Float = 10.0f,
    sampleRateHz: Float = 200.0f,
    q: Float = 0.7071f
) {
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var w1 = 0f; private var w2 = 0f

    init {
        val omega = 2.0 * Math.PI * cutoffHz / sampleRateHz
        val alpha = Math.sin(omega) / (2.0 * q)
        val cosOmega = Math.cos(omega)

        val a0 = 1.0 + alpha
        b0 = ((1.0 - cosOmega) / (2.0 * a0)).toFloat()
        b1 = ((1.0 - cosOmega) / a0).toFloat()
        b2 = b0
        a1 = ((-2.0 * cosOmega) / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun filter(sample: Float): Float {
        val w0 = sample - a1 * w1 - a2 * w2
        val y = b0 * w0 + b1 * w1 + b2 * w2
        w2 = w1
        w1 = w0
        return y
    }

    fun reset() {
        w1 = 0f
        w2 = 0f
    }
}

class ModelInputBuilder(
    val windowLength: Int = 200,
    private val targetSampleIntervalMs: Long = 5L // 200 Hz
) {
    private val capacity = windowLength + 1
    private val ringAx = FloatArray(capacity)
    private val ringAy = FloatArray(capacity)
    private val ringAz = FloatArray(capacity)
    private val ringGx = FloatArray(capacity)
    private val ringGy = FloatArray(capacity)
    private val ringGz = FloatArray(capacity)

    private val filterAx = BiquadLowPassFilter()
    private val filterAy = BiquadLowPassFilter()
    private val filterAz = BiquadLowPassFilter()
    private val filterGx = BiquadLowPassFilter()
    private val filterGy = BiquadLowPassFilter()
    private val filterGz = BiquadLowPassFilter()

    private var sampleCount = 0
    private var writeIndex = 0
    private var lastAcceptedTimestamp: Long = 0L

    // Shape: [1, 12, 200] (2400 floats)
    private val normalizedFlatTensor = FloatArray(12 * windowLength)
    private val rawTensor = Array(12) { FloatArray(windowLength) }

    fun reset() {
        sampleCount = 0
        writeIndex = 0
        lastAcceptedTimestamp = 0L
        filterAx.reset(); filterAy.reset(); filterAz.reset()
        filterGx.reset(); filterGy.reset(); filterGz.reset()
    }

    fun addSample(imu: ImuData): Boolean {
        if (sampleCount == 0 || (imu.timestamp - lastAcceptedTimestamp) >= targetSampleIntervalMs) {
            ringAx[writeIndex] = filterAx.filter(imu.accelX)
            ringAy[writeIndex] = filterAy.filter(imu.accelY)
            ringAz[writeIndex] = filterAz.filter(imu.accelZ)
            ringGx[writeIndex] = filterGx.filter(imu.gyroX)
            ringGy[writeIndex] = filterGy.filter(imu.gyroY)
            ringGz[writeIndex] = filterGz.filter(imu.gyroZ)

            writeIndex = (writeIndex + 1) % capacity
            if (sampleCount < capacity) {
                sampleCount++
            }
            lastAcceptedTimestamp = imu.timestamp
        }
        return isReady()
    }

    fun isReady(): Boolean = sampleCount >= windowLength

    fun buildRawTensor(): Array<FloatArray>? {
        if (!isReady()) return null

        val hasPrevious = sampleCount > windowLength
        val startIndex = if (hasPrevious) (writeIndex + 1) % capacity else 0
        val prevIndex = if (hasPrevious) writeIndex else 0

        var prevAx = ringAx[prevIndex]
        var prevAy = ringAy[prevIndex]
        var prevAz = ringAz[prevIndex]
        
        val dtSeconds = (targetSampleIntervalMs / 1000.0f).coerceAtLeast(0.001f)

        for (t in 0 until windowLength) {
            val idx = (startIndex + t) % capacity
            val ax = ringAx[idx]
            val ay = ringAy[idx]
            val az = ringAz[idx]
            val gx = ringGx[idx]
            val gy = ringGy[idx]
            val gz = ringGz[idx]

            val accMag = sqrt(ax * ax + ay * ay + az * az)
            val gyroMag = sqrt(gx * gx + gy * gy + gz * gz)

            val jerkX = (ax - prevAx) / dtSeconds
            val jerkY = (ay - prevAy) / dtSeconds
            val jerkZ = (az - prevAz) / dtSeconds

            prevAx = ax; prevAy = ay; prevAz = az

            rawTensor[0][t] = ax
            rawTensor[1][t] = ay
            rawTensor[2][t] = az
            rawTensor[3][t] = gx
            rawTensor[4][t] = gy
            rawTensor[5][t] = gz
            rawTensor[6][t] = accMag
            rawTensor[7][t] = gyroMag
            rawTensor[8][t] = jerkX
            rawTensor[9][t] = jerkY
            rawTensor[10][t] = jerkZ
        }

        return rawTensor
    }

    fun buildNormalizedFlatTensor(mean: FloatArray, scale: FloatArray): FloatArray? {
        val raw = buildRawTensor() ?: return null

        for (c in 0 until 11) {
            val m = mean[c]
            val s = if (scale[c] == 0f) 1.0f else scale[c]
            val channelOffset = c * windowLength
            val rawChannel = raw[c]
            for (t in 0 until windowLength) {
                val normalized = (rawChannel[t] - m) / s
                normalizedFlatTensor[channelOffset + t] = normalized.coerceIn(-15.0f, 15.0f)
            }
        }
        
        // Compute PCA (Channel 11 - 12th channel) based on normalized accel data (Channels 0, 1, 2)
        var meanX = 0f; var meanY = 0f; var meanZ = 0f
        val offset0 = 0; val offset1 = windowLength; val offset2 = 2 * windowLength
        for (t in 0 until windowLength) {
            meanX += normalizedFlatTensor[offset0 + t]
            meanY += normalizedFlatTensor[offset1 + t]
            meanZ += normalizedFlatTensor[offset2 + t]
        }
        meanX /= windowLength
        meanY /= windowLength
        meanZ /= windowLength

        var c00 = 0f; var c01 = 0f; var c02 = 0f
        var c11 = 0f; var c12 = 0f; var c22 = 0f
        for (t in 0 until windowLength) {
            val dx = normalizedFlatTensor[offset0 + t] - meanX
            val dy = normalizedFlatTensor[offset1 + t] - meanY
            val dz = normalizedFlatTensor[offset2 + t] - meanZ
            c00 += dx * dx; c01 += dx * dy; c02 += dx * dz
            c11 += dy * dy; c12 += dy * dz; c22 += dz * dz
        }

        var vx = 1.0f; var vy = 1.0f; var vz = 1.0f
        for (iter in 0 until 10) {
            val nx = c00 * vx + c01 * vy + c02 * vz
            val ny = c01 * vx + c11 * vy + c12 * vz
            val nz = c02 * vx + c12 * vy + c22 * vz
            val norm = sqrt(nx * nx + ny * ny + nz * nz)
            if (norm > 1e-6f) {
                vx = nx / norm; vy = ny / norm; vz = nz / norm
            }
        }

        val channelOffset11 = 11 * windowLength
        for (t in 0 until windowLength) {
            val dx = normalizedFlatTensor[offset0 + t] - meanX
            val dy = normalizedFlatTensor[offset1 + t] - meanY
            val dz = normalizedFlatTensor[offset2 + t] - meanZ
            normalizedFlatTensor[channelOffset11 + t] = dx * vx + dy * vy + dz * vz
        }

        return normalizedFlatTensor
    }
}
