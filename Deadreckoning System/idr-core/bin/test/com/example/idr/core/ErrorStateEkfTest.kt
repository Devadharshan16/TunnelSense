package com.example.idr.core

import com.example.idr.core.estimator.ErrorStateEkf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ErrorStateEkfTest {

    @Test
    fun testEkfInitialization() {
        val ekf = ErrorStateEkf()
        ekf.setOrigin(12.9716, 77.5946, 0f)

        assertTrue("Origin should be set", ekf.hasOrigin)
        assertEquals("Initial forward velocity should be 0", 0f, ekf.forwardVelocityMps, 1e-4f)
        assertEquals("Initial heading should be 0", 0f, ekf.headingDeg, 1e-4f)

        val latLon = ekf.getEstimatedLatLon()
        assertEquals(12.9716, latLon.lat, 1e-6)
        assertEquals(77.5946, latLon.lon, 1e-6)
    }

    @Test
    fun testPredictAndForwardVelocityAiding() {
        val ekf = ErrorStateEkf()
        ekf.setOrigin(12.9716, 77.5946, 0f) // Facing North

        // Predict 15 steps at dt = 0.1s with forward acceleration and velocity aiding
        for (i in 0 until 15) {
            ekf.predict(axBody = 1.2f, ayBody = 0f, gzBody = 0f, dt = 0.1f)
            // Velocity aiding with tight confidence (e.g. 1.0f) or TCN update
            ekf.updateVelocity(12.0f, variance = 1.0f)
            // Non-holonomic constraint: lateral velocity is 0
            ekf.updateNhc()
        }

        assertTrue("Velocity should be aided towards target speed", ekf.forwardVelocityMps > 8.0f)
        assertTrue("Lateral velocity should be tightly clamped near 0", abs(ekf.state[4]) < 0.5f)

        // Since facing North, Y (North) position should increase, X (East) position should stay near 0
        assertTrue("North position should advance", ekf.state[1] > 2.0f)
        assertTrue("East position should remain near 0", abs(ekf.state[0]) < 1.0f)

        val latLon = ekf.getEstimatedLatLon()
        assertTrue("Latitude should have increased", latLon.lat > 12.9716)
    }

    @Test
    fun testZuptClamping() {
        val ekf = ErrorStateEkf()
        ekf.setOrigin(12.9716, 77.5946, 0f)

        // Set high velocity
        ekf.state[3] = 20.0f
        ekf.state[4] = 2.0f

        // Apply ZUPT
        ekf.updateZupt()

        assertTrue("Forward velocity should drop to near 0 under ZUPT", abs(ekf.state[3]) < 0.1f)
        assertTrue("Lateral velocity should drop to near 0 under ZUPT", abs(ekf.state[4]) < 0.1f)
    }

    @Test
    fun testGpsUpdateCorrection() {
        val ekf = ErrorStateEkf()
        ekf.setOrigin(12.9716, 77.5946, 0f)

        // Simulate position uncertainty following a GPS outage
        ekf.P[0][0] = 500.0f
        ekf.P[1][1] = 500.0f

        // Simulate GPS fix arriving 100 meters North with 3m accuracy
        val (targetEast, targetNorth) = ekf.latLonToLocal(12.9725, 77.5946)
        ekf.updateGpsPosition(12.9725, 77.5946, accuracyMeters = 3.0f)

        // With high prior uncertainty, state position should closely snap to the accurate GPS reading
        assertEquals("North position should converge to GPS fix", targetNorth.toFloat(), ekf.state[1], 3.0f)
        assertEquals("East position should converge to GPS fix", targetEast.toFloat(), ekf.state[0], 3.0f)
    }

    @Test
    fun testStationaryInPlaceRotationClamping() {
        val ekf = ErrorStateEkf()
        ekf.setOrigin(12.9716, 77.5946, 0f) // Initial heading North

        // Simulate rotating phone in hand by 90 degrees over 2 seconds:
        // gyroZ = 0.785 rad/s (45 deg/s), while forward/lateral accel are 0, and ZUPT clamped
        ekf.updateZupt()

        for (i in 0 until 20) {
            ekf.predict(axBody = 0f, ayBody = 0f, gzBody = 0.785398f, dt = 0.1f)
            ekf.updateZupt()
        }

        // Heading should have updated significantly (approx 90 degrees)
        assertEquals("Heading should rotate", 90f, ekf.headingDeg, 5.0f)

        // Velocity should remain strictly 0
        assertEquals("Forward velocity must be clamped to 0", 0f, ekf.forwardVelocityMps, 1e-4f)

        // Position coordinates MUST NOT move
        assertEquals("East position must remain 0", 0f, ekf.state[0], 1e-4f)
        assertEquals("North position must remain 0", 0f, ekf.state[1], 1e-4f)

        val latLon = ekf.getEstimatedLatLon()
        assertEquals("Latitude must not drift", 12.9716, latLon.lat, 1e-6)
        assertEquals("Longitude must not drift", 77.5946, latLon.lon, 1e-6)
    }
}
