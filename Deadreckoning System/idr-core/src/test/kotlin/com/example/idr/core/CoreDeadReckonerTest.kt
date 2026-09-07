package com.example.idr.core

import com.example.idr.core.estimator.CoreDeadReckoner
import com.example.idr.core.fusion.CoreGnssDeficitHandler
import com.example.idr.core.model.IdrGnssState
import com.example.idr.core.model.IdrGpsSample
import com.example.idr.core.model.IdrImuSample
import com.example.idr.core.model.IdrLatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreDeadReckonerTest {

    @Test
    fun testZuptStationaryDetection() {
        val reckoner = CoreDeadReckoner()
        // Create 10 samples of stationary device sitting on table (accelZ ~ 9.81, gyros ~ 0)
        val stationarySamples = (1..10).map { i ->
            IdrImuSample(
                timestampMs = i * 20L,
                accelX = 0.02f,
                accelY = -0.01f,
                accelZ = 9.80f,
                gyroX = 0.005f,
                gyroY = -0.002f,
                gyroZ = 0.001f
            )
        }

        val velocity = reckoner.estimateVelocity(stationarySamples)
        assertEquals(0f, velocity, 0.0001f)
    }

    @Test
    fun testGnssDeficitTransitionState() {
        val handler = CoreGnssDeficitHandler()

        // 1. Initial good GPS fixes
        val t0 = 1000L
        val goodGps = IdrGpsSample(
            lat = 13.0827,
            lon = 80.2707,
            speedMps = 10f,
            bearingDeg = 90f,
            accuracyMeters = 5f,
            hasFix = true
        )
        val imuMoving = IdrImuSample(
            timestampMs = t0,
            accelX = 0f,
            accelY = 0.5f,
            accelZ = 9.81f,
            gyroX = 0f,
            gyroY = 0f,
            gyroZ = 0f
        )

        var est = handler.update(goodGps, imuMoving, 90f)
        assertEquals(IdrGnssState.GNSS_ACTIVE, est.state)

        // 2. Simulate entering tunnel (GPS lost for 3000 ms)
        val tunnelTime = t0 + 3000L
        val lostGps = IdrGpsSample(
            lat = 0.0,
            lon = 0.0,
            accuracyMeters = 999f,
            hasFix = false
        )
        val imuInTunnel = IdrImuSample(
            timestampMs = tunnelTime,
            accelX = 0f,
            accelY = 0.2f,
            accelZ = 9.81f,
            gyroX = 0f,
            gyroY = 0f,
            gyroZ = 0f
        )

        est = handler.update(lostGps, imuInTunnel, 90f)
        assertEquals(IdrGnssState.INS_ONLY, est.state)
        assertTrue(est.lat > 13.0)
        assertTrue(est.lon > 80.0)
    }

    @Test
    fun testFlatEarthPositionIntegration() {
        val reckoner = CoreDeadReckoner()
        val start = IdrLatLon(13.0, 80.0)
        // Travel due North at 10 m/s for 10 seconds = 100 meters north
        val next = reckoner.estimatePosition(start, 10f, 0f, 10f)

        // Latitude should have increased, longitude should be unchanged
        assertTrue(next.lat > start.lat)
        assertEquals(start.lon, next.lon, 0.000001)
    }
}
