# TunnelSense – INS Drift Issues & Finalized Solutions

## Objective

The main goal is to reduce the large position drift observed when TunnelSense switches from GNSS to INS-only mode.

Current observed drift: **~47 m**  
Target: **~5 m maximum position error**

The four finalized issues are:

1. Heading Error
2. EKF Usage / Integration Error
3. ZUPT Problem
4. TCN Speed Prediction Error

---

# 1. Heading Error

## Problem

The current Android INS mainly obtains heading by integrating the gyroscope Z-axis and applying a small magnetometer correction.

The problem is that even a small gyroscope bias can accumulate over time:

```text
Gyro bias
   ↓
Heading error
   ↓
Vehicle is propagated in the wrong direction
   ↓
Position gradually moves away from the real path
   ↓
Large drift
```

The magnetometer can also become unreliable because of magnetic interference from the phone, vehicle, wiring, speakers, or nearby metal.

## Solution

- Calibrate the gyroscope and magnetometer.
- Check whether magnetometer measurements are reliable before using them.
- Detect abnormal magnetic-field magnitude and sudden magnetic changes.
- Compare gyro-based heading with magnetometer heading to detect disagreement.
- Do not remove the magnetometer completely. Use it as an optional correction when reliable.
- Estimate and correct gyroscope bias through the state-estimation system.

## Expected benefit

More stable heading → more accurate movement direction → lower accumulated position drift.

---

# 2. EKF Usage / Integration Error

## Problem

The project contains an **8-state EKF**, but the current Android runtime does not use all eight states to generate the final position.

The 8 states are:

```text
x       → X position
y       → Y position
ψ       → Heading
vx      → Forward velocity
vy      → Lateral velocity
bax     → Accelerometer X bias
bay     → Accelerometer Y bias
bg      → Gyroscope bias
```

Currently, the Android pipeline mainly uses the EKF for forward-velocity filtering. The final map position is propagated separately using velocity and heading.

So the current architecture is approximately:

```text
TCN
 ↓
Forward velocity
 ↓
Velocity filtering
 ↓
Separate heading + position calculation
 ↓
Map
```

Instead, the intended full-state approach is:

```text
IMU + TCN + GNSS + other measurements
                  ↓
             Full 8-state EKF
                  ↓
       Position + heading + velocity
                  ↓
                 Map
```

Because the full state is not currently driving the position solution, the filter is not fully exploiting its ability to estimate sensor biases, heading, velocity, and position together.

## Solution

Integrate the full 8-state EKF into the actual Android INS position pipeline.

The filter should estimate and update:

- X and Y position
- Heading
- Forward velocity
- Lateral velocity
- Accelerometer biases
- Gyroscope bias

The TCN prediction should act as a velocity measurement/aiding signal rather than directly determining the final position.

## Expected benefit

The EKF can combine the available measurements and estimate sensor biases, especially accelerometer and gyroscope bias.

This should reduce accumulated position and heading errors.

**Important:** Using all 8 states should improve drift, but it does not guarantee a 5 m result. The actual improvement must be measured experimentally.

---

# 3. ZUPT Problem

## What is ZUPT?

**ZUPT = Zero-Velocity Update.**

When the vehicle is actually stopped:

```text
Actual velocity = 0 m/s
```

ZUPT tells the system:

> The vehicle is stationary, so its velocity should be zero.

This prevents small velocity errors from continuously accumulating.

## Current Problem

The current stationary detector mainly checks whether acceleration magnitude is close to gravitational acceleration:

```text
|acceleration| ≈ 9.81 m/s²
```

The problem is:

**Zero acceleration does not mean zero velocity.**

For example:

```text
Vehicle stopped:
velocity = 0
acceleration ≈ 0
```

but also:

```text
Vehicle moving at constant speed:
velocity = 8 m/s
acceleration ≈ 0
```

So acceleration alone may incorrectly classify a moving vehicle as stationary.

This creates a false ZUPT:

```text
Vehicle is moving
      ↓
Detector says "STOP"
      ↓
ZUPT sets velocity to 0
      ↓
Large velocity error
      ↓
Position error
```

## Solution

Do not remove ZUPT. Improve the stationary detector.

Use multiple signals:

### 1. Accelerometer

Check whether acceleration magnitude is close to gravity.

### 2. Gyroscope

Check whether angular motion is very small:

```text
gyro ≈ 0
```

### 3. TCN velocity

Check whether predicted forward velocity is close to zero:

```text
TCN velocity ≈ 0
```

Only trigger ZUPT when the signals collectively indicate that the vehicle is actually stationary.

```text
Acceleration ≈ gravity
        +
Gyro ≈ 0
        +
TCN velocity ≈ 0
        ↓
Stationary confirmed
        ↓
ZUPT
        ↓
Velocity = 0
```

Also require the condition to remain true for a short period rather than triggering from one noisy sample.

## Expected benefit

- Fewer false ZUPT detections.
- Better detection of genuine stops.
- Reduced velocity drift while stationary.
- Less accumulated position error after repeated stops.

---

# 4. TCN Speed Prediction Error

## Problem

The TCN predicts forward velocity from IMU data.

The current predictions are not accurate enough. Large speed prediction errors directly create position errors:

```text
Wrong TCN speed
      ↓
Wrong travelled distance
      ↓
Wrong position
      ↓
INS drift
```

Example:

```text
Actual speed = 30 km/h
TCN predicts = 20 km/h
```

or:

```text
Actual speed = 0 km/h
TCN predicts = 5 km/h
```

Both cause incorrect position propagation.

## Solution

Improve the TCN and avoid blindly trusting every prediction.

### Fix the training pipeline

- Use leakage-free train/test splitting.
- Fit normalization only on training data.
- Use causal preprocessing.
- Correct timestamp synchronization.
- Make training and Android inference preprocessing consistent.

### Add prediction uncertainty

Instead of outputting only:

```text
velocity
```

make the TCN output:

```text
velocity + uncertainty
```

For example:

```text
TCN
 ↓
Predicted velocity (μ)
Predicted variance (σ²)
```

Reliable prediction → trust it more.

Uncertain prediction → trust it less.

## Expected benefit

More accurate velocity estimates → more accurate travelled distance → lower INS drift.

---

# How the Four Problems Are Connected

These issues interact with each other:

```text
TCN speed error
      ↓
Wrong distance
      ↓
Heading error → Wrong direction
      ↓
Position drift
      ↑
Incomplete EKF
      ↑
Poor bias estimation
      ↑
ZUPT problem
      ↓
Incorrect velocity correction
```

So the 47 m drift is not caused by one isolated component.

---

# Final Solution Strategy

## Step 1 — Improve TCN velocity prediction

Make sure the AI provides useful forward-velocity estimates.

## Step 2 — Improve ZUPT detection

Make stationary detection reliable so a moving vehicle is not incorrectly assigned zero velocity.

## Step 3 — Improve heading estimation

Calibrate sensors and reliability-check magnetometer measurements while correcting gyroscope bias.

## Step 4 — Integrate the full 8-state EKF

Use the EKF to jointly estimate:

```text
Position
Heading
Velocity
Sensor biases
```

instead of using it mainly as a forward-speed filter.

---

# Evaluation Metrics

Do not judge the improved system using only one number.

For each GNSS-denied segment, measure:

| Metric | Meaning |
|---|---|
| **Final Position Error** | Position error at the end of the GNSS-denied period |
| **Maximum Position Error** | Worst position error reached during the outage |
| **Mean Position Error** | Average position error during the outage |
| **RMSE** | Overall magnitude of position error |
| **Drift %** | Position error relative to travelled distance |

The **~5 m target refers to position drift/error**, not generic EKF state error.

### State Error

Error in an individual estimated state:

```text
x error
y error
heading error
velocity error
bias error
```

### Position Error

Distance between estimated and actual position:

```text
sqrt((x_est - x_true)² + (y_est - y_true)²)
```

### Final Position Error

Position error specifically at the end of the GNSS-denied period.

### Mean Position Error

Average position error throughout the GNSS-denied period.

These are different metrics.

---

# Final Takeaway

The four main issues are:

1. **Heading error** → wrong direction causes position drift.
2. **Incomplete EKF usage** → the available 8-state estimator is not fully controlling the Android position solution.
3. **ZUPT detection problem** → false or missed stationary detection can create velocity errors.
4. **TCN speed prediction error** → wrong velocity produces wrong travelled distance.

The target solution is:

```text
Better TCN
    +
Reliable ZUPT
    +
Stable Heading
    +
Full 8-State EKF
    ↓
More accurate INS position
    ↓
Lower GNSS-denied drift
    ↓
Target: ~5 m maximum position error
```

**The ~5 m value is a target, not a guaranteed result. It must be validated experimentally after the changes.**
