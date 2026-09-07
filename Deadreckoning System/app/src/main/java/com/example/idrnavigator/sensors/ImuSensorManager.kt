package com.example.idrnavigator.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ImuData(
    val accelX: Float = 0f, val accelY: Float = 0f, val accelZ: Float = 0f,
    val gyroX: Float = 0f, val gyroY: Float = 0f, val gyroZ: Float = 0f,
    val magX: Float = 0f, val magY: Float = 0f, val magZ: Float = 0f,
    val timestamp: Long = 0L
)

class ImuSensorManager(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val _imuDataFlow = MutableStateFlow(ImuData())
    val imuDataFlow: StateFlow<ImuData> = _imuDataFlow.asStateFlow()

    private var currentAccel = FloatArray(3)
    private var currentGyro = FloatArray(3)
    private var currentMag = FloatArray(3)

    fun start() {
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        var updated = false
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, currentAccel, 0, 3)
                updated = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                System.arraycopy(event.values, 0, currentGyro, 0, 3)
                updated = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, currentMag, 0, 3)
                updated = true
            }
        }

        if (updated) {
            _imuDataFlow.value = ImuData(
                accelX = currentAccel[0], accelY = currentAccel[1], accelZ = currentAccel[2],
                gyroX = currentGyro[0], gyroY = currentGyro[1], gyroZ = currentGyro[2],
                magX = currentMag[0], magY = currentMag[1], magZ = currentMag[2],
                timestamp = System.currentTimeMillis()
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }
}
