package com.example.idrnavigator.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.location.GnssStatus
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GpsData(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val accuracy: Float = 0f,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val hasBearing: Boolean = false,
    val timestamp: Long = 0L,
    val hasFix: Boolean = false,
    val satelliteCount: Int = 0
)

class GnssManager(private val context: Context) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _gpsDataFlow = MutableStateFlow(GpsData())
    val gpsDataFlow: StateFlow<GpsData> = _gpsDataFlow.asStateFlow()

    private var currentSatCount = 0

    private val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
        .setMinUpdateIntervalMillis(500L)
        .build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            _gpsDataFlow.value = GpsData(
                lat = location.latitude,
                lon = location.longitude,
                accuracy = location.accuracy,
                speed = location.speed,
                bearing = location.bearing,
                hasBearing = location.hasBearing(),
                timestamp = location.time,
                hasFix = true,
                satelliteCount = currentSatCount
            )
        }
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var usedCount = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) usedCount++
            }
            currentSatCount = usedCount
            // Update the current flow value with the new sat count
            val current = _gpsDataFlow.value
            if (current.hasFix) {
                _gpsDataFlow.value = current.copy(satelliteCount = usedCount)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null && !_gpsDataFlow.value.hasFix) {
                    _gpsDataFlow.value = GpsData(
                        lat = location.latitude,
                        lon = location.longitude,
                        accuracy = location.accuracy,
                        speed = location.speed,
                        bearing = location.bearing,
                        hasBearing = location.hasBearing(),
                        timestamp = location.time,
                        hasFix = true,
                        satelliteCount = currentSatCount
                    )
                }
            }
        } catch (_: Exception) {
            // Fallback to active updates
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
        try {
            locationManager.registerGnssStatusCallback(gnssStatusCallback, android.os.Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            // Ignored if device lacks GNSS support
        }
    }

    fun stop() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
    }
}
