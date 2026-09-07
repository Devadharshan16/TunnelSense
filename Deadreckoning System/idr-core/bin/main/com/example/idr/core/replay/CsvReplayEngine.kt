package com.example.idr.core.replay

import com.example.idr.core.fusion.CoreGnssDeficitHandler
import com.example.idr.core.model.IdrGnssState
import com.example.idr.core.model.IdrGpsSample
import com.example.idr.core.model.IdrImuSample
import com.example.idr.core.model.IdrPositionEstimate
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class ReplaySummary(
    val totalRows: Int,
    val gnssActiveRows: Int,
    val insOnlyRows: Int,
    val transitioningRows: Int,
    val maxDriftMeters: Float,
    val finalLat: Double,
    val finalLon: Double,
    val gpsDistanceMeters: Double,
    val finalGpsErrorMeters: Double,
    val driftPercentOfGpsDistance: Double
)

class CsvReplayEngine(
    private val handler: CoreGnssDeficitHandler = CoreGnssDeficitHandler()
) {
    /**
     * Process a CSV file containing 14-column IMU+GNSS logs:
     * timestamp_ms, accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z, mag_x, mag_y, mag_z, gps_lat, gps_lon, gps_accuracy, gps_speed
     */
    fun processFile(file: File, onEstimate: ((IdrPositionEstimate) -> Unit)? = null): ReplaySummary {
        handler.reset()

        var total = 0
        var gnssCount = 0
        var insCount = 0
        var transCount = 0
        var maxDrift = 0f
        var lastEstimate: IdrPositionEstimate? = null
        var lastGpsLat: Double? = null
        var lastGpsLon: Double? = null
        var finalGpsLat: Double? = null
        var finalGpsLon: Double? = null
        var gpsDistanceMeters = 0.0

        BufferedReader(FileReader(file)).use { reader ->
            var line = reader.readLine() // Read header
            while (true) {
                line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val parts = line.split(",").map { it.trim() }
                if (parts.size < 10) continue

                val timestamp = parts[0].toLongOrNull() ?: continue
                val ax = parts[1].toFloatOrNull() ?: 0f
                val ay = parts[2].toFloatOrNull() ?: 0f
                val az = parts[3].toFloatOrNull() ?: 0f
                val gx = parts[4].toFloatOrNull() ?: 0f
                val gy = parts[5].toFloatOrNull() ?: 0f
                val gz = parts[6].toFloatOrNull() ?: 0f
                val mx = parts[7].toFloatOrNull() ?: 0f
                val my = parts[8].toFloatOrNull() ?: 0f
                val mz = parts[9].toFloatOrNull() ?: 0f

                val hasGps = parts.size >= 14 && parts[10].isNotEmpty() && parts[11].isNotEmpty()
                val lat = if (hasGps) parts[10].toDoubleOrNull() ?: 0.0 else 0.0
                val lon = if (hasGps) parts[11].toDoubleOrNull() ?: 0.0 else 0.0
                val accuracy = if (hasGps && parts.size > 12) parts[12].toFloatOrNull() ?: 999f else 999f
                val speed = if (hasGps && parts.size > 13) parts[13].toFloatOrNull() ?: 0f else 0f

                val imuSample = IdrImuSample(
                    timestampMs = timestamp,
                    accelX = ax, accelY = ay, accelZ = az,
                    gyroX = gx, gyroY = gy, gyroZ = gz,
                    magX = mx, magY = my, magZ = mz
                )

                val gpsSample = IdrGpsSample(
                    lat = lat,
                    lon = lon,
                    speedMps = speed,
                    bearingDeg = 0f,
                    accuracyMeters = accuracy,
                    hasFix = hasGps && lat != 0.0 && lon != 0.0,
                    satelliteCount = if (hasGps) 12 else 0
                )

                val rawDeg = Math.toDegrees(atan2(mx.toDouble(), my.toDouble()))
                val magHeading = ((rawDeg + 360) % 360).toFloat()

                val estimate = handler.update(gpsSample, imuSample, magHeading)
                lastEstimate = estimate
                if (gpsSample.hasFix) {
                    if (lastGpsLat != null && lastGpsLon != null) {
                        gpsDistanceMeters += distanceMeters(lastGpsLat!!, lastGpsLon!!, gpsSample.lat, gpsSample.lon)
                    }
                    lastGpsLat = gpsSample.lat
                    lastGpsLon = gpsSample.lon
                    finalGpsLat = gpsSample.lat
                    finalGpsLon = gpsSample.lon
                }
                total++

                when (estimate.state) {
                    IdrGnssState.GNSS_ACTIVE -> gnssCount++
                    IdrGnssState.INS_ONLY -> insCount++
                    IdrGnssState.TRANSITIONING -> transCount++
                }
                if (estimate.driftMeters > maxDrift) {
                    maxDrift = estimate.driftMeters
                }

                onEstimate?.invoke(estimate)
            }
        }

        val finalGpsErrorMeters = if (lastEstimate != null && finalGpsLat != null && finalGpsLon != null) {
            distanceMeters(lastEstimate.lat, lastEstimate.lon, finalGpsLat!!, finalGpsLon!!)
        } else {
            0.0
        }
        val driftPercent = if (gpsDistanceMeters > 0.0) {
            maxDrift.toDouble() / gpsDistanceMeters * 100.0
        } else {
            0.0
        }

        return ReplaySummary(
            totalRows = total,
            gnssActiveRows = gnssCount,
            insOnlyRows = insCount,
            transitioningRows = transCount,
            maxDriftMeters = maxDrift,
            finalLat = lastEstimate?.lat ?: 0.0,
            finalLon = lastEstimate?.lon ?: 0.0,
            gpsDistanceMeters = gpsDistanceMeters,
            finalGpsErrorMeters = finalGpsErrorMeters,
            driftPercentOfGpsDistance = driftPercent
        )
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMeters = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return 2.0 * earthRadiusMeters * atan2(sqrt(a), sqrt(1.0 - a))
    }
}
