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

    private fun stationaryImu(timestampMs: Long) = IdrImuSample(
        timestampMs = timestampMs,
        accelX = 0f,
        accelY = 0f,
        accelZ = 9.81f,
        gyroX = 0f,
        gyroY = 0f,
        gyroZ = 0f
    )

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

    @Test
    fun testStationaryRotationZeroDisplacement() {
        val reckoner = CoreDeadReckoner()
        val start = IdrLatLon(13.0, 80.0)

        // Samples representing stationary in-hand rotation:
        // Net accel is ~9.81 m/s² (1g), but gyroZ is rotating at 1.0 rad/s (~57 deg/s)
        val rotationSamples = (1..10).map { i ->
            IdrImuSample(
                timestampMs = i * 20L,
                accelX = 0.05f,
                accelY = 0.02f,
                accelZ = 9.80f,
                gyroX = 0.01f,
                gyroY = 0.01f,
                gyroZ = 1.0f // Heavy rotation
            )
        }

        val velocity = reckoner.estimateVelocity(rotationSamples)
        assertEquals("Velocity must be clamped to 0 when rotating in place", 0f, velocity, 1e-4f)

        // Integrating position with 0 velocity should not change position
        val nextPos = reckoner.estimatePosition(start, velocity, 45f, 0.5f)
        assertEquals("Latitude must remain unchanged", start.lat, nextPos.lat, 1e-7)
        assertEquals("Longitude must remain unchanged", start.lon, nextPos.lon, 1e-7)
    }

    @Test
    fun testDriftResetOnTransitionToGnssActive() {
        val handler = CoreGnssDeficitHandler()
        val t0 = 1000L

        // 1. Enter INS_ONLY with loss of GPS
        val lostGps = IdrGpsSample(lat = 0.0, lon = 0.0, accuracyMeters = 999f, hasFix = false)
        val imu = IdrImuSample(timestampMs = t0 + 3000L, accelX = 0f, accelY = 1.0f, accelZ = 9.81f, gyroX = 0f, gyroY = 0f, gyroZ = 0f)
        var est = handler.update(lostGps, imu, 0f)

        // 2. Restore good GPS
        val goodGps = IdrGpsSample(lat = 13.0827, lon = 80.2707, speedMps = 5f, bearingDeg = 0f, accuracyMeters = 3f, hasFix = true)
        for (i in 1..CoreGnssDeficitHandler.CONSECUTIVE_GOOD_FIXES_REQUIRED) {
            est = handler.update(goodGps, imu, 0f)
        }

        // Fast forward past transition blend
        val postBlendTime = t0 + 3000L + CoreGnssDeficitHandler.TRANSITION_BLEND_DURATION_MS + 100L
        val imuPost = IdrImuSample(timestampMs = postBlendTime, accelX = 0f, accelY = 1.0f, accelZ = 9.81f, gyroX = 0f, gyroY = 0f, gyroZ = 0f)
        est = handler.update(goodGps, imuPost, 0f)

        assertEquals("State should be GNSS_ACTIVE", IdrGnssState.GNSS_ACTIVE, est.state)
        assertEquals("Drift must reset to 0 upon GNSS_ACTIVE recovery", 0f, est.driftMeters, 1e-4f)
    }

    @Test
    fun testTransitionFallbackContinuesFromBlendedPosition() {
        val handler = CoreGnssDeficitHandler()
        val goodGps = IdrGpsSample(
            lat = 13.0,
            lon = 80.0,
            speedMps = 5f,
            bearingDeg = 0f,
            accuracyMeters = 3f,
            hasFix = true
        )

        handler.update(goodGps, stationaryImu(1000L), 0f)
        handler.update(
            goodGps.copy(hasFix = false, lat = 0.0, lon = 0.0, accuracyMeters = 999f),
            stationaryImu(4000L),
            0f
        )

        val reacquiredGps = goodGps.copy(lat = 13.001, lon = 80.001)
        handler.update(reacquiredGps.copy(satelliteCount = 1), stationaryImu(4010L), 0f)
        handler.update(reacquiredGps.copy(satelliteCount = 2), stationaryImu(4020L), 0f)
        var estimate = handler.update(reacquiredGps, stationaryImu(4030L), 0f)
        assertEquals(IdrGnssState.TRANSITIONING, estimate.state)

        val blendTime = 4030L + CoreGnssDeficitHandler.TRANSITION_BLEND_DURATION_MS / 2
        estimate = handler.update(reacquiredGps, stationaryImu(blendTime), 0f)
        val blendedLat = estimate.lat

        handler.update(
            reacquiredGps.copy(hasFix = false, lat = 0.0, lon = 0.0, accuracyMeters = 999f),
            stationaryImu(blendTime + 10L),
            0f
        )
        estimate = handler.update(
            reacquiredGps.copy(hasFix = false, lat = 0.0, lon = 0.0, accuracyMeters = 999f),
            stationaryImu(blendTime + 20L),
            0f
        )

        assertEquals(IdrGnssState.INS_ONLY, estimate.state)
        assertEquals(blendedLat, estimate.lat, 0.000001)
    }

    @Test
    fun testHeadingUpdatesWhileGnssRemainsLocked() {
        val handler = CoreGnssDeficitHandler()
        val gps = IdrGpsSample(
            lat = 13.0,
            lon = 80.0,
            speedMps = 0f,
            bearingDeg = 0f,
            accuracyMeters = 3f,
            hasFix = true
        )

        var estimate = handler.update(gps, stationaryImu(1000L), 0f)
        assertEquals(0f, estimate.headingDeg, 0.01f)

        repeat(10) { index ->
            estimate = handler.update(
                gps,
                stationaryImu(1100L + index * 100L).copy(gyroZ = -Math.PI.toFloat() / 2f),
                0f
            )
        }

        assertEquals(IdrGnssState.GNSS_ACTIVE, estimate.state)
        assertTrue("Heading should follow IMU rotation while GNSS is active", estimate.headingDeg > 30f)
        assertTrue(estimate.headingDeg in 0f..359.999f)
    }
}
