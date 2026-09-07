# TunnelSense — Repository Audit & Remediation Plan: 5 Critical Dead-Reckoning Problems

## Objective

Identify and remediate the five critical issues affecting the reliability of the TunnelSense smartphone dead-reckoning pipeline:

1. Sensor Synchronization
2. Coordinate-Frame Transformation
3. Heading Estimation
4. Timing / Sampling Rate
5. Zero-Velocity Update (ZUPT)

---

# 1. Sensor Synchronization

## Overview of Failure

The accelerometer, gyroscope, and magnetometer do not necessarily produce measurements at exactly the same timestamp.

In the current Android ingestion design, the accelerometer event triggers the `ImuData` emission while the latest cached gyroscope and magnetometer values are attached to that accelerometer event. Therefore, the three measurements can represent slightly different physical moments.

## Cause

- Each sensor generates events independently.
- Sensor event timestamps can differ.
- The current implementation uses cached gyro and magnetometer values rather than explicitly matching all three readings to one timestamp.
- During rotation or acceleration, even a small time difference can correspond to a different vehicle state.

## Impact

Measurements from different moments may be fused as though they describe the same state.

```text
Timestamp mismatch
      ↓
Incorrect sensor fusion
      ↓
Incorrect motion/orientation estimate
      ↓
Position error
```

## The Fix

1. Timestamp every accelerometer, gyroscope, and magnetometer sample.
2. Maintain timestamped buffers for each sensor.
3. Align the three sensor streams to a common timestamp.
4. Use interpolation when an exact timestamp is not available.
5. Reject invalid or non-monotonic timestamps.

### Key Principle

> **Synchronization means that the sensor measurements being fused should describe the same physical moment.**

---

# 2. Coordinate-Frame Transformation

## Overview of Failure

Smartphone sensors report measurements in the **phone coordinate frame**, while vehicle dead reckoning requires a consistent **vehicle/body coordinate frame**.

If the phone is mounted at an angle, the phone X/Y/Z axes do not automatically represent the vehicle's forward/right/up directions.

## Cause

- The phone may be mounted with a fixed rotation relative to the vehicle.
- Different mounting orientations change the relationship between phone axes and vehicle axes.
- Incorrect axis conventions can also swap or invert motion components.
- Raw phone-frame data therefore cannot be assumed to be vehicle-frame data.

## Impact

A vehicle moving forward can appear to accelerate along an incorrect phone axis.

```text
Actual vehicle motion
        ↓
Phone-frame measurement
        ↓
Incorrect frame interpretation
        ↓
Wrong velocity / heading
        ↓
Position error
```

A frame error can therefore affect the entire downstream navigation solution.

## The Fix

1. Define the vehicle/body coordinate convention explicitly.
2. Determine the fixed rotation from phone frame to vehicle frame.
3. Apply the same rotation consistently to accelerometer, gyroscope, and magnetometer data.
4. Validate the transformation using controlled forward, lateral, and rotational movements.
5. Ensure the same axis convention is used in preprocessing, mechanization, EKF, and Android inference.

### Key Principle

> **Coordinate transformation converts phone-frame measurements into a consistent vehicle-frame representation.**

---

# 3. Heading Estimation

## Overview of Failure

Heading is the direction in which the vehicle is facing.

Dead reckoning depends on heading because measured acceleration must be projected into the navigation frame before velocity and position are integrated.

The gyroscope provides angular-rate information, but small gyro errors accumulate when integrated. The magnetometer can provide a long-term directional reference, but it can also be disturbed.

## Cause

- Gyroscope bias and noise accumulate through integration.
- Magnetometer measurements can contain disturbances.
- Incorrect phone/vehicle axis conventions can produce an incorrect heading equation.
- Tilt and magnetic-field effects can influence compass-based heading.
- Applying an incorrect magnetic correction can introduce additional heading error.

## Impact

A small heading error changes the direction of estimated movement.

```text
Correct heading → correct motion direction
Wrong heading   → wrong motion direction
                         ↓
                   position drift
```

Even when the speed estimate is correct, an incorrect heading can cause the estimated position to move away from the actual path.

## The Fix

1. Calibrate the gyroscope and estimate its bias.
2. Use gyroscope integration for short-term orientation changes.
3. Use a reliable magnetometer reference to limit long-term heading drift.
4. Detect abnormal magnetic measurements before applying strong corrections.
5. Validate the magnetometer axis convention and heading equation.
6. Fuse orientation/error information through the EKF where applicable.

### Key Principle

> **The gyroscope tracks how much the vehicle turns; the magnetometer provides a reference for direction when its measurement is reliable.**

---

# 4. Timing / Sampling Rate

## Overview of Failure

Dead reckoning is fundamentally time-dependent. The estimator must know the actual time interval (`Δt`) between measurements.

A nominal sensor rate does not guarantee that every Android sensor event arrives at a perfectly fixed interval.

For example:

```text
50 Hz nominal rate
= 50 samples/second
= 20 ms per sample
```

But real timestamps may look like:

```text
0 ms → 19 ms → 41 ms → 60 ms → 82 ms
```

The intervals are therefore not perfectly constant.

## Cause

- Android sensor scheduling introduces timing variation.
- Processing and operating-system scheduling can delay individual events.
- Sensor events can be dropped or delayed.
- A mismatch between the assumed sampling interval and the actual event timestamps produces incorrect integration.

## Impact

Dead reckoning uses relationships such as:

```text
Δv = a × Δt
Δθ = ω × Δt
```

If `Δt` is wrong, the calculated change in velocity or orientation is wrong.

Repeated over many samples:

```text
Incorrect Δt
    ↓
Integration error
    ↓
Velocity / heading error
    ↓
Position drift
```

## The Fix

1. Use the sensor event timestamp as the source of timing information.
2. Calculate `Δt` from consecutive timestamps.
3. Do not blindly assume a fixed 20 ms, 50 Hz, or other nominal interval.
4. Log and monitor the actual sampling intervals.
5. Detect unusually large timing gaps.
6. Define one explicit sampling-rate contract for training and inference.

### Key Principle

> **Timing means using when the measurement actually happened, not simply when it was expected to happen.**

---

# 5. Zero-Velocity Update (ZUPT)

## Overview of Failure

ZUPT uses a known physical condition:

> **When the vehicle is truly stationary, its velocity must be 0 m/s.**

The problem is determining whether the vehicle is actually stationary.

The current core stationary logic relies mainly on acceleration magnitude being close to the gravity magnitude. That condition alone is not sufficient to prove zero velocity.

## Cause

- A moving vehicle can travel at approximately constant speed while its linear acceleration is close to zero.
- The accelerometer still measures the gravity component, so acceleration magnitude can remain close to `9.81 m/s²`.
- Therefore, `|a| ≈ 9.81 m/s²` does not automatically mean the vehicle is stationary.
- A false stationary detection can trigger an incorrect ZUPT correction.

## Impact

A moving vehicle may be incorrectly treated as stopped.

```text
False stationary detection
          ↓
Incorrect ZUPT
          ↓
Velocity forced toward 0
          ↓
Navigation error
```

ZUPT is intended to reduce drift, but applying it at the wrong time can create a larger error.

## The Fix

1. Combine accelerometer and gyroscope information.
2. Evaluate measurements over a time window rather than from one sample.
3. Require the stationary condition to remain consistent for a defined duration.
4. Reject a stationary decision when strong motion or rotation is present.
5. Apply ZUPT only when stationary confidence is sufficiently high.
6. Use the zero-velocity condition as an EKF measurement update.

### Key Principle

> **Acceleration close to gravity does not prove that velocity is zero. ZUPT should be applied only when there is strong evidence that the vehicle is actually stationary.**

---

# Combined Remediation Stack

The five problems should be addressed as one state-estimation pipeline:

```text
Raw IMU Sensors
      ↓
Timestamping & Synchronization
      ↓
Calibration / Bias Correction
      ↓
Phone → Vehicle Frame Transformation
      ↓
Heading / Orientation Estimation
      ↓
Actual Timestamp-based Δt
      ↓
Strapdown Mechanization
      ↓
EKF Error-State Correction
      ↓
Reliable ZUPT
      ↓
NHC Vehicle Constraints
      ↓
GNSS Correction When Available
      ↓
Estimated Position
```

---

# Summary Table

| Problem | Primary Cause | Main Risk | Remediation |
|---|---|---|---|
| **Synchronization** | Sensors provide measurements at different timestamps | Incorrect sensor fusion | Timestamp buffers + alignment/interpolation |
| **Coordinate Frame** | Phone axes do not automatically match vehicle axes | Wrong motion direction | Phone-to-vehicle rotation matrix + validation |
| **Heading** | Gyro drift, magnetic disturbance, axis errors | Wrong movement direction | Gyro + reliable magnetometer + EKF correction |
| **Timing** | Irregular event intervals / sampling mismatch | Incorrect integration | Actual timestamp-based `Δt` |
| **ZUPT** | False stationary detection | Incorrect velocity reset | Accel + gyro + temporal consistency |

---

# Final Technical Statement

> **Reliable GNSS-denied dead reckoning requires more than integrating raw IMU data. The system must synchronize measurements, transform them into the correct vehicle frame, estimate heading reliably, use the actual measurement timing, and apply zero-velocity constraints only when the vehicle is confidently stationary. These corrections work together to limit accumulated inertial drift and improve position reliability.**
