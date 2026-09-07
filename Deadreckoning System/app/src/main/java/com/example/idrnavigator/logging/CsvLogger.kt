package com.example.idrnavigator.logging

import android.content.Context
import com.example.idrnavigator.sensors.GpsData
import com.example.idrnavigator.sensors.ImuData
import com.opencsv.CSVWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CsvLogger(private val context: Context) {
    private var csvWriter: CSVWriter? = null
    private var currentFile: File? = null
    var loggedRowCount = 0
        private set
    
    val savedFilePath: String?
        get() = currentFile?.absolutePath
        
    val savedFile: File?
        get() = currentFile

    fun start(): File? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "drive_log_$timestamp.csv"
        
        val directory = context.getExternalFilesDir(null)
        if (directory != null && !directory.exists()) {
            directory.mkdirs()
        }
        
        currentFile = File(directory, fileName)
        
        try {
            csvWriter = CSVWriter(FileWriter(currentFile))
            val header = arrayOf(
                "timestamp_ms", "accel_x", "accel_y", "accel_z",
                "gyro_x", "gyro_y", "gyro_z", "mag_x", "mag_y", "mag_z",
                "gps_lat", "gps_lon", "gps_accuracy", "gps_speed"
            )
            csvWriter?.writeNext(header)
            loggedRowCount = 0
            return currentFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun logRow(imuData: ImuData, gpsData: GpsData) {
        if (csvWriter == null) return
        
        val row = arrayOf(
            imuData.timestamp.toString(),
            imuData.accelX.toString(), imuData.accelY.toString(), imuData.accelZ.toString(),
            imuData.gyroX.toString(), imuData.gyroY.toString(), imuData.gyroZ.toString(),
            imuData.magX.toString(), imuData.magY.toString(), imuData.magZ.toString(),
            if (gpsData.hasFix) gpsData.lat.toString() else "",
            if (gpsData.hasFix) gpsData.lon.toString() else "",
            if (gpsData.hasFix) gpsData.accuracy.toString() else "",
            if (gpsData.hasFix) gpsData.speed.toString() else ""
        )
        csvWriter?.writeNext(row)
        loggedRowCount++

        // Flush every 50 rows to prevent data loss on crash
        if (loggedRowCount % 50 == 0) {
            try {
                csvWriter?.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        try {
            csvWriter?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        csvWriter = null
    }
}
