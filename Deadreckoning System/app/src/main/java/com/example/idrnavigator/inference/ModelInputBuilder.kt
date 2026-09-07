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
class ModelInputBuilder(
    val windowLength: Int = 10,
    private val targetSampleIntervalMs: Long = 100L // 10 Hz
) {
    private val capacity = windowLength + 1
    private val ringAx = FloatArray(capacity)
    private val ringAy = FloatArray(capacity)
    private val ringAz = FloatArray(capacity)
    private val ringGx = FloatArray(capacity)
    private val ringGy = FloatArray(capacity)
    private val ringGz = FloatArray(capacity)

    private var sampleCount = 0
    private var writeIndex = 0
    private var lastAcceptedTimestamp: Long = 0L

    // Shape: [1, 11, 10] (110 floats)
    private val normalizedFlatTensor = FloatArray(11 * windowLength)
    private val rawTensor = Array(11) { FloatArray(windowLength) }

    fun reset() {
        sampleCount = 0
        writeIndex = 0
        lastAcceptedTimestamp = 0L
    }

    fun addSample(imu: ImuData): Boolean {
        // Only accept sample if enough time has passed (downsampling to target rate)
        if (sampleCount == 0 || (imu.timestamp - lastAcceptedTimestamp) >= targetSampleIntervalMs * 1_000_000L) {
            ringAx[writeIndex] = imu.accelX
            ringAy[writeIndex] = imu.accelY
            ringAz[writeIndex] = imu.accelZ
            ringGx[writeIndex] = imu.gyroX
            ringGy[writeIndex] = imu.gyroY
            ringGz[writeIndex] = imu.gyroZ

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

            // Calculate numerical derivative (Jerk)
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
                // Coerce to [-15, 15] to prevent anomalous spike pollution
                normalizedFlatTensor[channelOffset + t] = normalized.coerceIn(-15.0f, 15.0f)
            }
        }
        
        return normalizedFlatTensor
    }
}
