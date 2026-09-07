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
    private val targetSampleIntervalMs: Long = 100L // 10 Hz target sampling rate
) {
    // Rolling buffer of 10Hz-downsampled IMU readings (keeps windowLength + 1 for jerk calculation)
    private val buffer = mutableListOf<ImuData>()
    private var lastAcceptedTimestamp: Long = 0L

    fun reset() {
        buffer.clear()
        lastAcceptedTimestamp = 0L
    }

    /**
     * Ingest an IMU sample (typically arriving at 50Hz).
     * Subsamples to ~10Hz (100ms interval) to match model training frequency.
     * Returns true if buffer has reached full window capacity.
     */
    fun addSample(imu: ImuData): Boolean {
        if (buffer.isEmpty() || (imu.timestamp - lastAcceptedTimestamp) >= targetSampleIntervalMs) {
            buffer.add(imu)
            lastAcceptedTimestamp = imu.timestamp

            // Keep at most windowLength + 1 samples (extra 1 sample at head for jerk difference)
            if (buffer.size > windowLength + 1) {
                buffer.removeAt(0)
            }
        }
        return isReady()
    }

    fun isReady(): Boolean = buffer.size >= windowLength

    /**
     * Build the raw [1, 11, 10] feature matrix.
     * Dimensions: [channel (11)][timestep (10)]
     */
    fun buildRawTensor(): Array<FloatArray>? {
        if (!isReady()) return null

        val window = if (buffer.size > windowLength) {
            buffer.takeLast(windowLength)
        } else {
            buffer.toList()
        }

        val previousSample = if (buffer.size > windowLength) buffer[buffer.size - windowLength - 1] else window.first()

        // 11 channels x 10 timesteps
        val tensor = Array(11) { FloatArray(windowLength) }

        var prevAx = previousSample.accelX
        var prevAy = previousSample.accelY
        var prevAz = previousSample.accelZ

        for (t in 0 until windowLength) {
            val s = window[t]
            val ax = s.accelX
            val ay = s.accelY
            val az = s.accelZ
            val gx = s.gyroX
            val gy = s.gyroY
            val gz = s.gyroZ

            val accMag = sqrt(ax * ax + ay * ay + az * az)
            val gyroMag = sqrt(gx * gx + gy * gy + gz * gz)

            val jerkX = ax - prevAx
            val jerkY = ay - prevAy
            val jerkZ = az - prevAz

            prevAx = ax
            prevAy = ay
            prevAz = az

            tensor[0][t] = ax
            tensor[1][t] = ay
            tensor[2][t] = az
            tensor[3][t] = gx
            tensor[4][t] = gy
            tensor[5][t] = gz
            tensor[6][t] = accMag
            tensor[7][t] = gyroMag
            tensor[8][t] = jerkX
            tensor[9][t] = jerkY
            tensor[10][t] = jerkZ
        }

        return tensor
    }

    /**
     * Build a flat [1 * 11 * 10] float array with channel-first ordering:
     * index = c * windowLength + t
     * Scaler normalization is applied: (x - mean[c]) / scale[c]
     */
    fun buildNormalizedFlatTensor(mean: FloatArray, scale: FloatArray): FloatArray? {
        val raw = buildRawTensor() ?: return null
        val flat = FloatArray(11 * windowLength)

        for (c in 0 until 11) {
            val m = mean[c]
            val s = scale[c]
            val channelOffset = c * windowLength
            for (t in 0 until windowLength) {
                flat[channelOffset + t] = (raw[c][t] - m) / s
            }
        }
        return flat
    }
}
