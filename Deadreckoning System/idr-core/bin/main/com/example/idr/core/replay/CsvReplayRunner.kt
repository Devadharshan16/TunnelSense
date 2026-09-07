package com.example.idr.core.replay

import java.io.File

/**
 * Command-line entry point to run the pure IDR engine against external sensor CSV logs.
 *
 * Usage:
 *   java -jar idr-core.jar <path-to-sensor-log.csv>
 * Or via Gradle:
 *   ./gradlew :idr-core:run --args="path/to/log.csv"
 */
fun main(args: Array<String>) {
    println("=======================================================")
    println("  IDR Core Standalone Position Estimation Engine       ")
    println("  Hardware-Agnostic Inertial Dead Reckoning Runner     ")
    println("=======================================================\n")

    if (args.isEmpty()) {
        println("Usage: CsvReplayRunnerKt <path-to-csv-file>")
        println("Expected CSV columns (14):")
        println("  timestamp_ms, accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z, mag_x, mag_y, mag_z, gps_lat, gps_lon, gps_accuracy, gps_speed\n")
        return
    }

    val file = File(args[0])
    if (!file.exists()) {
        System.err.println("Error: File not found: ${file.absolutePath}")
        return
    }

    println("Processing log file: ${file.name} (${file.length()} bytes)...")
    val engine = CsvReplayEngine()
    val startTime = System.currentTimeMillis()

    val summary = engine.processFile(file)

    val elapsedMs = System.currentTimeMillis() - startTime
    println("\n================ REPLAY COMPLETED ================")
    println("Total Epochs Processed : ${summary.totalRows}")
    println("Execution Time         : ${elapsedMs} ms (${"%.1f".format(summary.totalRows.toFloat() / (elapsedMs / 1000f))} epochs/sec)")
    println("GNSS Active Epochs     : ${summary.gnssActiveRows}")
    println("INS-Only (Deficit)     : ${summary.insOnlyRows}")
    println("Transitioning Blends   : ${summary.transitioningRows}")
    println("Max Estimated Drift    : ${"%.2f".format(summary.maxDriftMeters)} m")
    println("GPS Distance Traveled  : ${"%.2f".format(summary.gpsDistanceMeters)} m")
    println("Classical Final Error  : ${"%.2f".format(summary.finalGpsErrorMeters)} m")
    println("Classical Drift        : ${"%.2f".format(summary.driftPercentOfGpsDistance)}% of GPS distance")
    println("AI TCN Final Error     : unavailable (AI estimator is Android/ONNX-only)")
    println("AI TCN Drift           : unavailable (AI estimator is Android/ONNX-only)")
    println("Final Position         : (${summary.finalLat}, ${summary.finalLon})")
    println("===================================================\n")
}
