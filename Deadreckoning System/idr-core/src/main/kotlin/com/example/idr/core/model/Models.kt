package com.example.idr.core.model

data class IdrLatLon(
    val lat: Double,
    val lon: Double
)

data class IdrImuSample(
    val timestampMs: Long,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val magX: Float = 0f,
    val magY: Float = 0f,
    val magZ: Float = 0f
)

data class IdrGpsSample(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val speedMps: Float = 0f,
    val bearingDeg: Float = 0f,
    val accuracyMeters: Float = 0f,
    val hasFix: Boolean = false,
    val satelliteCount: Int = 0
)

enum class IdrGnssState {
    GNSS_ACTIVE,
    TRANSITIONING,
    INS_ONLY
}

data class IdrPositionEstimate(
    val lat: Double,
    val lon: Double,
    val speedMps: Float,
    val headingDeg: Float,
    val state: IdrGnssState,
    val driftMeters: Float,
    val gpsAccuracy: Float,
    val satelliteCount: Int,
    val hasFix: Boolean,
    val insOnlyDurationSec: Float = 0f
)
