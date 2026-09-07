# IDR Core — Portable Intelligent Dead Reckoning Engine

A pure Kotlin (JVM-compatible) library containing the core dead reckoning algorithms and GNSS deficit transition state machine for the **Intelligent Dead Reckoning (IDR) Navigation System** (SIH26168).

This module contains **zero Android dependencies** (no `Context`, `SensorManager`, `Activity`, or Android UI classes). It is designed to run anywhere:
* **Android Application (`:app`)**: Bridges internal phone sensors into `idr-core`.
* **Embedded Hardware (Raspberry Pi, NVIDIA Jetson, STM32 gateway, BeagleBone)**: Reads from external IMUs over USB Serial / UART / I2C / SPI.
* **Vehicular Telematics**: Integrates with CAN-bus / OBD-II or Bluetooth BLE external IMU pucks.
* **Offline Model Evaluation & Python pipelines**: Replays test drive CSV logs for batch verification.

---

## Architecture Overview

```
                        ┌─────────────────────────────────────────┐
                        │   External Sensor / Hardware Sources    │
                        ├──────────────────┬──────────────────────┤
                        │  Android Phone   │ External Hardware    │
                        │  Built-in IMU    │ (BLE, USB UART, CAN) │
                        └─────────┬────────┴──────────┬───────────┘
                                  │                   │
                                  ▼                   ▼
                        ┌──────────────────┐ ┌────────────────────┐
                        │ Android Sensors  │ │ Hardware Driver /  │
                        │  (SensorManager) │ │ Serial Streamer    │
                        └─────────┬────────┘ └────────┬───────────┘
                                  │                   │
                         (IdrImuSample, IdrGpsSample) │
                                  │                   │
                                  ▼                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                             :idr-core MODULE                                │
│                                                                             │
│  ┌─────────────────────────┐           ┌─────────────────────────────────┐  │
│  │   CoreDeadReckoner      │           │    CoreGnssDeficitHandler       │  │
│  │  ─────────────────────  │           │   ─────────────────────────     │  │
│  │  • 3D Gravitational ZUPT│           │   • State Machine:              │  │
│  │  • Window Consensus     │ ◄───────  │     GNSS_ACTIVE <─> INS_ONLY    │  │
│  │  • Forward Strapdown    │           │     <─> TRANSITIONING           │  │
│  │  • Yaw Rate Integration │           │   • Configurable Smooth Blend   │  │
│  │  • Flat-Earth Geodetics │           │   • Prolonged Drift Warnings    │  │
│  └─────────────────────────┘           └────────────────┬────────────────┘  │
│                                                         │                   │
└─────────────────────────────────────────────────────────┼───────────────────┘
                                                          ▼
                                             ┌─────────────────────────┐
                                             │   IdrPositionEstimate   │
                                             │ (lat, lon, speed, state)│
                                             └─────────────────────────┘
```

---

## 1. Feeding External IMU Hardware to IDR Core

To connect an **external IMU** (e.g., WitMotion, Bosch BNO085, Lord MicroStrain, SparkFun 9DoF via Bluetooth LE or USB-Serial), you only need an ingestion adapter that converts incoming bytes into the following two data classes:

```kotlin
// 1. Convert external IMU readings (e.g., from serial/BLE stream)
val imuSample = IdrImuSample(
    timestampMs = System.currentTimeMillis(),
    accelX = ax, accelY = ay, accelZ = az,  // in m/s²
    gyroX = gx, gyroY = gy, gyroZ = gz,     // in rad/s
    magX = mx, magY = my, magZ = mz         // in µT (optional, can be 0f)
)

// 2. Convert GNSS fix (from external GPS module or NMEA stream)
val gpsSample = IdrGpsSample(
    lat = latitude,
    lon = longitude,
    speedMps = speedKnots * 0.514444f,
    bearingDeg = courseOverGroundDeg,
    accuracyMeters = hdop * 5.0f,
    hasFix = (fixQuality > 0)
)

// 3. Update the engine
val handler = CoreGnssDeficitHandler()
val estimate: IdrPositionEstimate = handler.update(gpsSample, imuSample, magHeadingDeg)

println("Current Position: (${estimate.lat}, ${estimate.lon}), Mode: ${estimate.state}")
```

### What Changes When Moving to Real Hardware?

| Component | Android Phone (`:app`) | External Hardware (e.g. Raspberry Pi / Jetson) |
|---|---|---|
| **Sensor Input** | Android `SensorManager` | Serial port reader (`jSerialComm` / `/dev/ttyUSB0`) or BLE client |
| **GPS Input** | `FusedLocationProviderClient` | NMEA parser reading USB GPS dongle (e.g., u-blox NEO-M8N) |
| **Logger** | `Log.d` (Logcat) | `ConsoleIdrLogger` (stdout / systemd journal) |
| **Estimation Engine** | **`CoreGnssDeficitHandler` (Unchanged)** | **`CoreGnssDeficitHandler` (Unchanged)** |
| **Dead Reckoning Math**| **`CoreDeadReckoner` (Unchanged)** | **`CoreDeadReckoner` (Unchanged)** |

The mathematical core, zero-velocity detection, geodetic translation, and state-machine handoffs require **0 lines of modification**.

---

## 2. Replaying Sensor CSV Logs from Command Line

`idr-core` includes a standalone batch replay runner that accepts the standard 14-column CSV format recorded by `CsvLogger`:

### CSV Format Contract (14 Columns):
```text
timestamp_ms, accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z, mag_x, mag_y, mag_z, gps_lat, gps_lon, gps_accuracy, gps_speed
```

### Running via Gradle:
```bash
./gradlew :idr-core:run --args="path/to/drive_log.csv"
```

### Running via Standalone Java JAR:
```bash
./gradlew :idr-core:installDist
# Generates runnable binary in idr-core/build/install/idr-core/bin/idr-core
./idr-core/build/install/idr-core/bin/idr-core path/to/drive_log.csv
```

### Output Example:
```text
=======================================================
  IDR Core Standalone Position Estimation Engine       
  Hardware-Agnostic Inertial Dead Reckoning Runner     
=======================================================

Processing log file: drive_log_20260903_214500.csv (2450120 bytes)...

================ REPLAY COMPLETED ================
Total Epochs Processed : 4520
Execution Time         : 48 ms (94166.7 epochs/sec)
GNSS Active Epochs     : 3820
INS-Only (Deficit)     : 580
Transitioning Blends   : 120
Max Estimated Drift    : 8.45 m
Final Position         : (13.298142, 80.034511)
===================================================
```

---

## 3. Tunable Parameters Reference

All configuration constants in `CoreGnssDeficitHandler` are publicly accessible and can be set at runtime:

```kotlin
// Change blend duration to 2.5 seconds
CoreGnssDeficitHandler.TRANSITION_BLEND_DURATION_MS = 2500L

// Trigger INS if GPS accuracy drops below 15 meters
CoreGnssDeficitHandler.GPS_ACCURACY_THRESHOLD = 15f

// Extend prolonged INS warning threshold to 120 seconds
CoreGnssDeficitHandler.INS_DRIFT_WARNING_THRESHOLD_SEC = 120f
```
