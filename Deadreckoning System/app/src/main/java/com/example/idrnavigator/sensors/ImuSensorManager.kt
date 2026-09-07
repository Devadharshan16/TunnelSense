package com.example.idrnavigator.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

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

    private var sensorThread: android.os.HandlerThread? = null
    private var sensorHandler: android.os.Handler? = null

    // Synchronization Buffers
    private val accelBuffer = ArrayDeque<Pair<Long, FloatArray>>()
    private var lastGyro: Pair<Long, FloatArray>? = null
    private var currentGyro: Pair<Long, FloatArray>? = null
    private var lastMag: Pair<Long, FloatArray>? = null
    private var currentMag: Pair<Long, FloatArray>? = null
    
    // Timing
    private var lastOutputTsNs: Long = 0L

    @Synchronized
    fun start() {
        if (sensorThread == null) {
            sensorThread = android.os.HandlerThread(
                "ImuSensorThread",
                android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE
            ).apply { start() }
            sensorHandler = android.os.Handler(sensorThread!!.looper)
        }
        val handler = sensorHandler
        // Using SENSOR_DELAY_FASTEST to provide 200Hz+ resolution for the TCN vibration analysis
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
        magSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
    }

    @Synchronized
    fun stop() {
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
        accelBuffer.clear()
        lastGyro = null
        currentGyro = null
        lastMag = null
        currentMag = null
        lastOutputTsNs = 0L
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        val vals = FloatArray(3)
        System.arraycopy(event.values, 0, vals, 0, 3)
        val ts = event.timestamp

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelBuffer.addLast(Pair(ts, vals))
                processBufferedAccel()
            }
            Sensor.TYPE_GYROSCOPE -> {
                lastGyro = currentGyro
                currentGyro = Pair(ts, vals)
                processBufferedAccel()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lastMag = currentMag
                currentMag = Pair(ts, vals)
                processBufferedAccel()
            }
        }
    }
    
    private fun processBufferedAccel() {
        val cg = currentGyro ?: return
        val lg = lastGyro ?: return
        
        while (accelBuffer.isNotEmpty()) {
            val accel = accelBuffer.peekFirst()!!
            val aTime = accel.first
            
            // If the accelerometer reading is newer than our latest gyroscope reading,
            // we must wait for the next gyroscope reading to accurately interpolate.
            if (aTime > cg.first) {
                break
            }
            
            accelBuffer.removeFirst()
            
            // 1. Time-Synchronized Interpolation
            val gyroFrac = if (cg.first > lg.first) (aTime - lg.first).toFloat() / (cg.first - lg.first).toFloat() else 1f
            val interpGyro = FloatArray(3)
            for(i in 0..2) interpGyro[i] = lg.second[i] + gyroFrac * (cg.second[i] - lg.second[i])
            
            val interpMag = FloatArray(3)
            val cm = currentMag
            val lm = lastMag
            if (cm != null && lm != null) {
                val magFrac = if (cm.first > lm.first) (aTime - lm.first).toFloat() / (cm.first - lm.first).toFloat() else 1f
                for(i in 0..2) interpMag[i] = lm.second[i] + magFrac * (cm.second[i] - lm.second[i])
            } else if (cm != null) {
                System.arraycopy(cm.second, 0, interpMag, 0, 3)
            }
            
            // 2. Exact dt Timing Integration
            val dt = if (lastOutputTsNs > 0L) (aTime - lastOutputTsNs) / 1_000_000_000f else 0.005f
            lastOutputTsNs = aTime
            val safeDt = dt.coerceIn(0.001f, 0.1f) // Guard against massive OS freezes
            
            // 3. Coordinate Frame Alignment (Phone -> Vehicle)
            // Assuming phone is mounted flat on center console (Screen Up, Top points Forward):
            // Vehicle Forward = Phone Y
            // Vehicle Lateral (Right) = Phone X
            // Vehicle Up = Phone Z
            val vehAccelForward = accel.second[1]
            val vehAccelLateral = accel.second[0]
            val vehGyroYaw = interpGyro[2]
            
            val tsMs = aTime / 1_000_000L
            _imuDataFlow.value = ImuData(
                accelX = accel.second[0], accelY = accel.second[1], accelZ = accel.second[2],
                gyroX = interpGyro[0], gyroY = interpGyro[1], gyroZ = interpGyro[2],
                magX = interpMag[0], magY = interpMag[1], magZ = interpMag[2],
                timestamp = tsMs
            )
            
            // Stream aligned, synchronized data down to the C++ EKF
            try {
                com.example.idrnavigator.inference.NativeEngine.pushImuSample(
                    vehAccelForward, vehAccelLateral, vehGyroYaw, safeDt
                )
            } catch (t: Throwable) {}
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
