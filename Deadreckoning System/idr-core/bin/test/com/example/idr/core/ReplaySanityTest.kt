package com.example.idr.core

import com.example.idr.core.replay.CsvReplayEngine
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplaySanityTest {
    @Test
    fun replayOutputsAreFiniteAndHeadingCardinalsAreConsistent() {
        val csv = File.createTempFile("idr-sanity-", ".csv")
        try {
            csv.writeText(buildString {
                appendLine("timestamp_ms,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z,mag_x,mag_y,mag_z,gps_lat,gps_lon,gps_accuracy,gps_speed")
                for (index in 0..12) {
                    val timestamp = index * 100L
                    val lat = 13.0 + index * 0.00001
                    appendLine("$timestamp,0,0,9.81,0,0,0,0,1,0,$lat,80.0,3,10")
                }
            })

            val estimates = mutableListOf<com.example.idr.core.model.IdrPositionEstimate>()
            val summary = CsvReplayEngine().processFile(csv) { estimates += it }

            assertTrue("Replay should contain estimates", estimates.isNotEmpty())
            assertTrue("GPS distance should be positive", summary.gpsDistanceMeters > 0.0)
            for (estimate in estimates) {
                assertTrue(estimate.lat.isFinite())
                assertTrue(estimate.lon.isFinite())
                assertTrue(estimate.speedMps.isFinite())
                assertTrue(estimate.headingDeg.isFinite())
                assertTrue(estimate.headingDeg >= 0f && estimate.headingDeg < 360f)
            }
            assertEquals("N", cardinalFor(0f))
            assertEquals("NE", cardinalFor(45f))
            assertEquals("E", cardinalFor(90f))
            assertEquals("SE", cardinalFor(135f))
            assertEquals("S", cardinalFor(180f))
            assertEquals("SW", cardinalFor(225f))
            assertEquals("W", cardinalFor(270f))
            assertEquals("NW", cardinalFor(315f))
            assertTrue("Final error should be finite", summary.finalGpsErrorMeters.isFinite())
            assertTrue("Drift percentage should be finite", summary.driftPercentOfGpsDistance.isFinite())
            assertTrue("GPS-reported speed is within the replay sanity tolerance", abs(10f - estimates.last().speedMps) <= 1.5f)
        } finally {
            csv.delete()
        }
    }

    private fun cardinalFor(degrees: Float): String = when {
        degrees < 23f -> "N"
        degrees < 68f -> "NE"
        degrees < 113f -> "E"
        degrees < 158f -> "SE"
        degrees < 203f -> "S"
        degrees < 248f -> "SW"
        degrees < 293f -> "W"
        degrees < 338f -> "NW"
        else -> "N"
    }
}
