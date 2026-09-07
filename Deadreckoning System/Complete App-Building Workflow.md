# SIH26168 — Complete App-Building Workflow (Your Part)
## Android Native (Kotlin) — Intelligent Dead Reckoning Mobile App

---

## 1. Software to Install (Do This First)

| Tool | Purpose | Link/Notes |
|---|---|---|
| **Android Studio** (latest stable) | Main IDE for the entire app | Includes Android SDK, emulator, build tools |
| **JDK 17** | Required by modern Android Gradle builds | Usually bundled with Android Studio now |
| **Git** | Version control, syncing with teammates | Connect to your shared repo |
| **A physical Android phone** | **Critical** — sensors, GPS, and TFLite inference behave very differently on a real device vs. emulator. Emulator sensors are simulated/unreliable. | Use USB debugging (enable Developer Options → USB Debugging) |
| **Python 3.10+ (optional, on your machine)** | Only needed if you help convert/inspect Person 1's exported TFLite models | `pip install tensorflow` for inspecting model I/O shapes |
| **Postman/no tool needed** | N/A — this app is fully local, no backend server required | — |

**Skip the emulator for real testing** — GPS drop simulation, real accelerometer noise, and actual driving tests require a real phone in a real vehicle.

---

## 2. Core Libraries You'll Use

Add these to your app's `build.gradle (Module :app)`:

```gradle
dependencies {
    // Location services (GNSS access, fused location)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // TensorFlow Lite (on-device model inference)
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // OpenStreetMap rendering (offline-capable — matches PS's OSM requirement)
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Kotlin Coroutines (for async sensor pipelines)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Charting (for debug plots of your sensor data / drift comparisons)
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // CSV writing (data logging for training data collection)
    implementation("com.opencsv:opencsv:5.9")
}
```

**Why osmdroid over Google Maps SDK:** the PS explicitly calls for an offline OpenStreetMap database overlay for map-matching. Google Maps requires internet and a paid API key at scale; osmdroid can cache map tiles offline, which matches the "no internet in a tunnel" reality of this exact problem. Use Google Maps only if you want a faster prototype UI early on — but plan to migrate to osmdroid before the map-matching integration phase.

---

## 3. Required Permissions (add to `AndroidManifest.xml`)

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

Note: `ACCESS_FINE_LOCATION` and background location need **runtime permission requests** in code (Android 6.0+), not just manifest entries — handle this on first app launch.

---

## 4. Project Structure

```
app/src/main/java/com/yourteam/idr/
│
├── sensors/
│   ├── ImuSensorManager.kt        # accelerometer + gyro + magnetometer listener
│   ├── GnssManager.kt             # GPS/location listener via FusedLocationProviderClient
│   └── SensorDataPoint.kt         # data class: timestamp, ax,ay,az,gx,gy,gz,lat,lon,accuracy
│
├── logging/
│   ├── CsvLogger.kt               # writes SensorDataPoint stream to CSV files
│   └── LoggingSessionActivity.kt  # simple screen: start/stop recording, shows sample count
│
├── calibration/
│   └── AlignmentEngine.kt         # estimates phone pitch/roll/yaw vs. vehicle heading
│
├── inference/
│   ├── TfliteRunner.kt            # loads .tflite model, runs windowed inference
│   └── ModelInputBuilder.kt       # converts raw sensor window into model's expected tensor shape
│
├── fusion/
│   └── GnssDeficitHandler.kt      # state machine: GNSS-aided vs pure INS mode
│
├── map/
│   ├── OsmMapView.kt              # osmdroid map wrapper
│   └── VehicleMarker.kt           # smooth-moving icon overlay
│
├── ui/
│   ├── MainActivity.kt
│   ├── NavigationFragment.kt      # live navigation screen
│   └── DataCollectionFragment.kt  # the "record a drive" screen
│
└── MainApplication.kt
```

---

## 5. Build Phases (in order — don't skip ahead)

### Phase 0: Environment Setup (Day 1)
- Create new Android Studio project → Empty Activity → Kotlin → min SDK 26 (covers 95%+ of devices)
- Add all dependencies above, sync Gradle
- Add permissions, test that the app installs and launches on your physical phone

### Phase 1: Raw Sensor Access (Day 1-2) — **your first real milestone**
- Implement `ImuSensorManager.kt` using Android's `SensorManager`:
  ```kotlin
  class ImuSensorManager(context: Context) : SensorEventListener {
      private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
      private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
      private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
      private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

      fun start() {
          sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
          sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
          sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME)
      }

      override fun onSensorChanged(event: SensorEvent) {
          val timestamp = System.currentTimeMillis()
          when (event.sensor.type) {
              Sensor.TYPE_ACCELEROMETER -> { /* store ax, ay, az */ }
              Sensor.TYPE_GYROSCOPE -> { /* store gx, gy, gz */ }
          }
      }
  }
  ```
  **Important note on sampling rate:** `SENSOR_DELAY_GAME` gives you roughly 50Hz, faster than the PS's 10Hz requirement — you'll downsample later to match the training data rate, but it's easier to have more samples and subsample than to miss data.

- Implement `GnssManager.kt` using `FusedLocationProviderClient` to get lat/lon/accuracy/speed
- Build a bare-bones screen that just prints live sensor + GPS values on screen — proves the pipeline works before you build anything fancy on top

### Phase 2: Data Logging Mode (Day 2-3) — **this is your priority deliverable for Sep 10**
- Implement `CsvLogger.kt`: buffer sensor readings and location fixes, write to a timestamped CSV file in app storage
- CSV format (**lock this with Person 1 before writing code** — this is the data format contract from before):
  ```
  timestamp_ms, accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z, mag_x, mag_y, mag_z, gps_lat, gps_lon, gps_accuracy, gps_speed
  ```
- Build `DataCollectionFragment.kt`: a simple screen with Start/Stop recording buttons, a live counter of samples logged, and a "Save & Export" button (share via email/drive/USB)
- **Test this on a real drive immediately** — even a 10-minute drive around the block gives you real data to hand to Person 1

### Phase 3: Calibration/Alignment Engine (Day 4-5, if time allows before Sep 10 — otherwise post-deadline)
- Use the magnetometer + accelerometer to estimate the phone's orientation relative to gravity and magnetic north
- Compare the direction of acceleration during actual forward driving (detected via GPS heading) against the phone's raw axes to compute an offset rotation matrix
- Apply this rotation to all future raw IMU readings so "forward" is consistent regardless of mount angle
- This is a genuinely hard sub-problem — a **simplified first version** is fine: just estimate a single fixed offset when the drive starts (assume the phone doesn't move mid-drive), rather than continuous re-calibration

### Phase 4: Map Integration (Post Sep 10 — for the fuller build)
- Implement `OsmMapView.kt` using osmdroid, load offline map tiles for your test-drive area
- Draw the live GPS track as a polyline for visual debugging
- Add a vehicle icon overlay that moves smoothly (interpolate between position updates rather than jumping)

### Phase 5: Model Inference Integration (Post Sep 10)
- Once Person 1 hands you a `.tflite` file, implement `TfliteRunner.kt`:
  ```kotlin
  class TfliteRunner(context: Context, modelPath: String) {
      private val interpreter: Interpreter = Interpreter(loadModelFile(context, modelPath))

      fun runInference(inputWindow: FloatArray): FloatArray {
          val output = Array(1) { FloatArray(OUTPUT_SIZE) }
          interpreter.run(arrayOf(inputWindow), output)
          return output[0]
      }
  }
  ```
- **Critical:** confirm the exact input window size, normalization, and output format with Person 1 — a mismatch here silently breaks everything without throwing an obvious error
- Feed live windowed sensor data into this model instead of (or alongside) raw dead reckoning, and plot the result against GPS ground truth for debugging

### Phase 6: GNSS Deficit Handler (Post Sep 10)
- Implement a simple state machine in `GnssDeficitHandler.kt`:
  ```
  States: GNSS_ACTIVE, INS_ONLY, TRANSITIONING
  Trigger to INS_ONLY: GPS accuracy value exceeds threshold OR no fix for >2 seconds
  Trigger back to GNSS_ACTIVE: GPS fix reacquired with good accuracy for 2+ consecutive readings
  ```
- On transition, don't just switch data sources abruptly — blend the last known GNSS position with the first INS estimate briefly to avoid a visible "jump" on the map

### Phase 7: Real-time Navigation UI (Post Sep 10)
- Combine everything into `NavigationFragment.kt`: live map, smoothly moving vehicle icon, current mode indicator (GNSS vs INS), and optionally speed/drift stats on screen for demo purposes

### Phase 8: Edge-Deployable Version (Post Sep 10, likely handled with Person 2/3 jointly)
- The PS wants the algorithms to also work with **external IMU data**, not just the phone — this typically means packaging the fusion/inference logic as a standalone Python or C++ module that can run on other hardware, separate from the phone app itself. This is lower priority for you specifically unless your team assigns it to you later.

---

## 6. Testing Checklist (do this continuously, not just at the end)

- [ ] Sensors register and produce continuous data without gaps
- [ ] CSV files open cleanly in Excel/pandas with no malformed rows
- [ ] GPS accuracy value is actually being logged correctly (test outdoors vs. indoors to see it change)
- [ ] App doesn't crash when GPS signal is lost mid-recording
- [ ] Battery/performance: sensor logging at high rate shouldn't drain battery unreasonably fast during a long test drive
- [ ] Real drive test: do at least one recording with a deliberate "GPS off" stretch (toggle airplane mode briefly, or drive into an actual underground area) to get GNSS-denied data for Person 1

---

## 7. What to Prioritize Before Sep 10 Specifically

Given the proposal-round deadline, your **must-do list** is just:
1. Phase 0 (setup) ✅
2. Phase 1 (raw sensor access) ✅
3. Phase 2 (data logging mode) ✅ — **this produces the real driving data that strengthens your team's proposal**
4. A simple static UI mockup/screenshot of what the navigation screen will eventually look like (for PPT slides) — doesn't need Phase 4-7 actually built

Phases 3, 4, 5, 6, 7, 8 are your **post-submission roadmap** — build these after Sep 10, working toward the actual finale deliverable.

---

## 8. Quick Reference: Key Android APIs You'll Touch

| API | Use |
|---|---|
| `SensorManager` / `SensorEventListener` | Accelerometer, gyroscope, magnetometer |
| `FusedLocationProviderClient` | GPS location, accuracy, speed |
| `Interpreter` (TFLite) | Running the trained model on-device |
| `osmdroid.MapView` | Offline map rendering |
| `File` / `FileWriter` (or OpenCSV) | Writing sensor logs to CSV |
| `ActivityCompat.requestPermissions` | Runtime location permission handling |