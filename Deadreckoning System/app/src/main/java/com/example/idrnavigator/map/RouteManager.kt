package com.example.idrnavigator.map

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.util.GeoPoint

// Route requests are kept separate from the map view so taps and search share one path.
data class RouteState(
    val points: List<GeoPoint> = emptyList(),
    val distanceMeters: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class RouteManager(context: Context, private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "RouteManager"
        private const val OSRM_USER_AGENT = "IDRNavigator/1.0"
    }

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(RouteState())
    val state: StateFlow<RouteState> = _state.asStateFlow()
    private var requestJob: Job? = null

    fun requestRoute(start: GeoPoint, destination: GeoPoint) {
        requestJob?.cancel()
        _state.value = RouteState(isLoading = true)
        requestJob = scope.launch(Dispatchers.IO) {
            try {
                val roadManager = OSRMRoadManager(appContext, OSRM_USER_AGENT)
                val waypoints = arrayListOf(start, destination)
                val road = roadManager.getRoad(waypoints)
                val nextState = if (road.mStatus == Road.STATUS_OK) {
                    RouteState(
                        points = road.mRouteHigh?.toList() ?: emptyList(),
                        distanceMeters = road.mLength * 1000.0,
                        durationSeconds = road.mDuration
                    )
                } else {
                    RouteState(errorMessage = "Route unavailable offline")
                }
                withContext(Dispatchers.Main.immediate) { _state.value = nextState }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Route request failed", error)
                withContext(Dispatchers.Main.immediate) {
                    _state.value = RouteState(errorMessage = "Route unavailable offline")
                }
            }
        }
    }

    fun clear() {
        requestJob?.cancel()
        _state.value = RouteState()
    }
}
