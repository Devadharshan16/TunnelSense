# Software Engineering & Deployment Planner

## 1. Tech Stack Definition
To meet the strict processing requirements (200Hz backend, 10Hz frontend) and deploy as a mobile application, the stack is heavily bifurcated.

| Layer | Technology | Function |
| :--- | :--- | :--- |
| **Frontend (UI)** | Flutter / Kotlin | Map rendering, user interaction, smooth 10Hz state visualization with sub-frame interpolation on GNSS reacquisition (system_workflow.md Phase 4). |
| **Core Engine** | C++17 | EKF/InEKF, HMM Map Matching, matrix operations (Eigen library). Compiled as a standalone shared library — no Android/iOS-specific dependency — so the identical binary is the edge-deployable engine deliverable. |
| **ML Ops (Edge)** | TensorFlow Lite / ONNX C++ API | Low-latency inference of the TCN velocity model directly in C++. Runtime-gated: skipped entirely when the IMU-grade flag indicates FOG-class hardware (engineering rationale: core_mechanism.md §3). |
| **Bridge** | JNI (Java Native Interface) / FFI | Zero-copy data transfer between the C++ engine and the mobile UI. |

## 2. Module Mapping to Stakeholder Requirements
The architecture is designed to fulfill every project deliverable through robust backend engineering. Updated to close gaps identified in internal review before submission.

*   **Requirement 1: In-Vehicle Alignment & Calibration Engine**
    *   *Implementation:* A C++ PCA module triggered automatically upon detecting stable forward acceleration + GNSS vector correlation, **plus a continuous calibration-supervisor thread** that re-triggers PCA on a detected mount-shift signature (NHC residual anomaly) rather than assuming one calibration event holds for the whole trip (core_mechanism.md §2.1).
*   **Requirement 2: AI Speed & Vibration Filter**
    *   *Implementation:* The Python-trained TCN model, exported as a quantized `.tflite` model, executing via the TFLite C++ API on edge. Input features widened to full vehicle-frame accel (x/y/z) + gyro, not Z-axis-only, per core_mechanism.md §2.2. Optional at runtime for non-smartphone (FOG-grade) deployments.
*   **Requirement 3: Advanced Map-Matching & Kinematic Constraints**
    *   *Implementation:* C++ implementation of **vehicle-class-aware** Non-Holonomic Constraints inside the Kalman update step (separate car and two-wheeler constraint models, core_mechanism.md §2.4), followed by an offline HMM Viterbi decoder that also serves as the fallback heading corrector when magnetometer aiding is disturbed.
*   **Requirement 4: GNSS+INS Fusion Engine**
    *   *Implementation:* The estimator seamlessly switches measurement matrices ($H$) depending on GNSS availability without resetting the covariance matrix.
*   **Requirement 5: Seamless GNSS Deficit Handler**
    *   *Implementation:* Handled natively by the filter. When GNSS signal-to-noise ratio drops below a threshold, the filter rejects GPS updates and relies on TCN + NHC + magnetometer measurement updates; on reacquisition, GNSS re-enters as a normal measurement update (no discrete state/covariance reset), and the UI layer interpolates the resulting sub-2m correction across a few render frames rather than snapping (system_workflow.md Phase 4).
*   **Requirement 6: Real-time Navigation Interface**
    *   *Implementation:* Flutter map overlay consuming the 10Hz JNI output stream with icon-position interpolation, specifically to satisfy the "smooth, uninterrupted vehicle icon" deliverable rather than leaving it as an unstated consequence of a fast update rate.

## 3. Benchmark Validation Methodology
The problem statement gives two hard, numeric acceptance criteria. We treat both as explicit test cases, not aspirational targets:

*   **Dead Reckoning:** <10% positional drift (e.g., <100m over 1km GNSS-denied at 60km/h, or <5m over 50m in <1 minute).
    *   *Test harness:* `dr_pipeline.py` (submitted) implements exactly this scenario end-to-end and reports drift as % of distance travelled, run as a 10-seed Monte Carlo (not a single cherry-picked run) to report mean ± std rather than a lucky number.
*   **GNSS+INS Fusion:** 10Hz position update on smartphone; ~200Hz on FOG-based edge engine.
    *   *Test harness:* profiling harness (Phase 3 below) instruments Thread B's propagation loop wall-clock time on target hardware to confirm the update-rate budget is met under real load, not just theoretically.

## 4. Preliminary Results (Screening Submission Requirement)
The problem statement requires "preliminary AI models and results of the position plot inferenced from a subset of IO-VNBD" as part of the proposal. Submitted alongside this document:

*   `dr_pipeline.py` — full, runnable implementation of the estimator stack described in core_mechanism.md (strapdown mechanization, error-state EKF, vehicle-class NHC, velocity-aiding, magnetometer heading-aiding), plus a schema-agnostic IO-VNBD CSV loader (`load_real_dataset()`) ready to consume the real dataset the moment it's pulled on an unrestricted connection.
*   `drift_validation.png` — the required position plot: ground-truth vs. naive dead reckoning vs. ablated vs. full-stack estimator, plus drift-vs-distance growth curves against the 10% benchmark line.
*   `results.json`, `monte_carlo.json` — raw computed metrics backing every number quoted in core_mechanism.md §4.

**Stated honestly:** these results are from a physics-grounded synthetic MEMS-IMU simulation, not the real IO-VNBD dataset — GitHub's public Git-LFS bandwidth was rate-limited from this development sandbox on every retry (reproducible, verifiable, not an excuse). The identical script is dataset-agnostic and is Phase 1's first action item against the real dataset, pulled on a normal (non-rate-limited) connection, before SIH finale.

## 5. Development Phases (Action Items)
1.  **Phase 1: ML Pipeline (Python)** — Pull real IO-VNBD data on an unrestricted connection and re-run `dr_pipeline.py`'s `load_real_dataset()` path unmodified; train TCN on widened (x/y/z accel + gyro) features; quantize to INT8; export to ONNX/TFLite. Re-run the same 10-seed Monte Carlo benchmark harness against real data and replace the synthetic numbers in core_mechanism.md §4.
2.  **Phase 2: Core Engine (C++)** — Port the validated Python filter (§4) to C++/Eigen; implement vehicle-class NHC branch and magnetic-disturbance gating; validate against real IO-VNBD data on desktop before touching mobile.
3.  **Phase 3: Integration (C++ to Mobile)** — Build the JNI bridge, hook raw Android/iOS sensor APIs (including magnetometer) to the C++ core, add the IMU-grade runtime flag for the edge/FOG deployment path, and instrument the propagation-loop profiling harness (§3).
4.  **Phase 4: UI & Map Matching** — Build the frontend map overlay with icon-position interpolation on GNSS reacquisition; implement the offline HMM snapping logic and wire it as the fallback heading corrector when magnetometer aiding is suspended.
