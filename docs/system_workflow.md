# System Workflow & Data Pipeline

## 1. High-Level Architecture
The system operates as a continuous closed-loop pipeline executing on edge hardware. It bridges raw hardware sensor interrupts to a high-level UI mapping engine.

## 2. Pipeline Execution Steps

### Phase 0: Session Setup (once per trip)
1.  **Vehicle-class prior:** User selects car/truck vs two-wheeler at app setup (or the app defaults to "car" and self-corrects — see step 2 below). This selects which Non-Holonomic Constraint model Thread B applies (core_mechanism.md §2.4).
2.  **Initial alignment:** PCA-based $R_{phone}^{car}$ estimation runs during the first sustained straight-line, GNSS-available driving window.

### Phase 1: Ingestion & Preprocessing
1.  **Sensor Polling:** IMU (Accelerometer, Gyroscope, Magnetometer) data is polled at 200Hz. GNSS data is polled at 1Hz (when available).
2.  **Spatial Transformation:** Raw IMU data is multiplied by the dynamically calibrated $R_{phone}^{car}$ matrix to align with the vehicle frame.
3.  **Low-Pass Filtering:** A baseline Butterworth filter removes extreme anomaly spikes before kinematic integration.
4.  **Calibration Supervisor (continuous, not one-shot):** Runs in parallel to Phase 1–2, at low duty cycle. Monitors the NHC residual stream from Thread B for the sustained-nonzero-lateral-velocity signature of a shifted mount (core_mechanism.md §2.1). On trigger, schedules a PCA re-fit against the next qualifying straight-line window and hot-swaps $R_{phone}^{car}$ only if the new fit's confidence exceeds the current one.

### Phase 2: Dual-Threaded Processing
The system splits into a Machine Learning thread and a Kinematic thread to maintain strict latency budgets.

*   **Thread A (ML Inference):**
    *   Buffers 1 second of transformed IMU data (accel x/y/z + gyro yaw, not vertical-axis-only — see core_mechanism.md §2.2).
    *   Executes a forward pass of the quantized TCN model via ONNX/TensorFlow Lite.
    *   Outputs $v_x$ (forward velocity) **and its predicted variance** to the filter thread, so Thread B can weight the measurement by the network's own confidence.
    *   **Sensor-grade gate:** if the active IMU is flagged FOG-grade (edge-engine, non-smartphone deployment), this thread's output is optional/disableable — see core_mechanism.md §3. Thread B does not hard-depend on Thread A.
*   **Thread B (State Estimator Core):**
    *   Executes the 200Hz propagation step.
    *   Ingests GNSS data (if available) for absolute position updates.
    *   Ingests Thread A's $v_x$ (when enabled) and the class-appropriate NHC (core_mechanism.md §2.4) for relative measurement updates during GNSS blackout.
    *   Ingests magnetometer heading at ~20Hz as an absolute heading measurement — gated by a magnetic-disturbance detector (total-field-magnitude check against expected ~25–65 µT local range) that suspends this update when the phone is in a magnetically disturbed environment (rebar/steel structures, common in tunnels/underground parking).

### Phase 3: Map Matching (Hidden Markov Model)
Even with optimized state estimation, micro-drifts accumulate — and this stage is the system's second, independent line of defense specifically for the case where Thread B's magnetometer aiding is suspended (§Phase 2, Thread B) inside a magnetically disturbed GNSS-denied tunnel/parking structure.
1.  **State Emission:** Thread B outputs raw $(x, y)$ coordinates.
2.  **Viterbi Decoding:** An HMM evaluates the raw coordinates against an offline OpenStreetMap grid.
3.  **Constraint Snapping:** The algorithm calculates the highest-probability road segment based on current trajectory and snaps the coordinate to the physical road topology.

### Phase 4: Output Delivery & Seamless UI Handoff
*   The sanitized, map-matched coordinates are passed over the JNI (Java Native Interface) bridge to the front-end application at 10Hz.
*   **GNSS reacquisition handling (explicit, since this is a named deliverable — "Seamless GNSS Deficit Handler"):** when GNSS reacquires after a blackout, the raw GNSS fix is *not* pushed straight to the map — Thread B treats it as a normal absolute-position measurement update into the same filter state (no discrete reset, no covariance reset), so the corrected position converges smoothly over the following ~1–2 seconds rather than jumping. The UI layer additionally interpolates the on-screen vehicle icon across any residual sub-2m correction step over 3–5 render frames, rather than snapping, so the displayed motion stays visually continuous even though the underlying estimate updates discretely at 10Hz. This is the concrete mechanism behind "a smooth, uninterrupted vehicle icon."
