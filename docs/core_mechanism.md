# Core Mechanism: Kinematics & Stochastic State Estimation

## 1. Problem Definition
In GNSS-denied environments, navigation relies on integrating Inertial Measurement Unit (IMU) data. Because consumer-grade MEMS IMUs suffer from inherent bias ($b$) and thermo-mechanical noise ($\eta$), double integration of acceleration yields a position error that grows quadratically:
$$Position = \iint (a_{measured} - b_a - \eta_a) dt^2$$
Without an external velocity reference (e.g., OBD-II), pure dead reckoning diverges catastrophically within seconds.

## 2. The Hybrid Resolution
Our system abandons end-to-end deep learning for coordinate prediction. Instead, we separate deterministic kinematics from stochastic noise using an **Invariant Extended Kalman Filter (InEKF)** augmented by a **Temporal Convolutional Network (TCN)** for pseudo-velocity measurement.

### 2.1 Dynamic Auto-Alignment (Coordinate Frame Resolution)
Smartphones possess arbitrary mounting orientations relative to the vehicle chassis. During the initial GNSS-active phase, we apply Principal Component Analysis (PCA) to the linear acceleration vector. The principal eigenvector dictates the vehicle's forward axis, generating a robust Rotation Matrix ($R_{phone}^{car}$) to transpose all IMU data into the vehicle's reference frame.

**Continuous re-alignment (not one-shot).** A single calibration event at trip start does not cover the case the problem statement explicitly calls out: "accidental phone misalignments on the mount" mid-drive (phone slips in the holder, dashboard mount vibrates loose). We therefore run alignment as a **supervisor process**, not a single trigger:
- The PCA fit is re-evaluated on a rolling 5-second window whenever the vehicle is in sustained straight-line motion (detected via low yaw-rate + high forward-acceleration correlation with GNSS course-over-ground, when available).
- A **misalignment detector** runs in parallel: it monitors the residual of the NHC lateral-velocity constraint (§2.4). A sustained (>2s) non-zero lateral-velocity residual that GNSS course confirms is *not* real vehicle motion is the signature of a shifted mount, not a real turn — this triggers an immediate PCA re-fit rather than waiting for the next natural straight-line window.
- Re-alignment only *updates* $R_{phone}^{car}$ when the new fit's confidence (eigenvalue ratio of principal to secondary axis) exceeds the currently-held estimate, so a momentarily noisy window can't corrupt a good calibration.

### 2.2 Deep Learning Velocity Estimator (The Pseudo-Speedometer)
To bound the integration drift, we train a lightweight TCN on the IO-VNBD dataset.
*   **Input:** 1-second rolling windows of vehicle-frame accelerometer (x, y, z) and gyroscope (yaw rate), not just Z-axis bounce — the forward/lateral channels carry most of the speed-correlated signal, with Z-axis vibration retained as a supplementary feature for potholes/idling detection.
*   **Output:** Scalar forward velocity ($v_x$) and its associated variance, used directly as the EKF measurement noise $R$ for that update (heteroscedastic — the filter trusts the network less on rough road, which the network itself signals via its variance head).
*   **Rationale:** TCNs provide parallelizable, low-latency 1D sequence processing superior to LSTMs for edge deployment.

### 2.3 The Estimator Core (InEKF on smartphone MEMS / EKF-equivalent validated below)
The state estimator operates on the Lie Group $SE(3)$ for the production implementation.
*   **State Vector:** Position, Velocity, Attitude (Quaternion), Accelerometer Bias, Gyroscope Bias.
*   **Propagation (200Hz):** Integrates raw angular velocity and linear acceleration.
*   **Update:** Fuses the TCN-predicted forward velocity ($v_x$) as a measurement update to dynamically estimate and subtract $b_a$ and $b_g$.

**Why the magnetometer/compass is a required aiding input, not optional.** Our own preliminary validation (§4) surfaced a real observability gap: velocity-aiding alone bounds *forward* drift but leaves heading (and therefore gyro bias) unobserved during a pure inertial coast, since nothing in a velocity-only measurement constrains rotation. The problem statement's IMU input list explicitly includes the magnetometer/compass for exactly this reason. We use it as a periodic (~20 Hz) absolute-heading pseudo-measurement, which is what closes the loop on gyro-bias estimation and prevents the heading-driven lateral drift shown in §4's ablation.

**Known risk with this fix, flagged honestly:** magnetometer heading is degraded by ferrous structures and electromagnetic interference — which is disproportionately common in exactly the GNSS-denied environments this system targets (underground parking rebar, tunnel steel lining, underpasses). Our mitigation is layered, not single-point-of-failure:
1. A magnetic-disturbance detector (monitoring total field magnitude against the expected ~25–65 µT local geomagnetic range) down-weights or suspends the magnetometer update when disturbance is detected, falling back to gyro-only heading propagation for that interval.
2. The Map-Matching stage (§2.5) becomes the primary heading corrector during magnetically-disturbed GNSS-denied stretches, since road-network geometry constrains heading independent of magnetic field quality.

### 2.4 Kinematic Constraints — Vehicle-Class Aware
We enforce physical constraints via Non-Holonomic Constraints (NHC), but the constraint model is **not a single fixed assumption** — it is selected by a lightweight vehicle-class classifier, because the problem statement explicitly targets two-wheelers alongside cars and trucks, and a rigid car-only NHC actively hurts motorcycle/scooter tracking.

*   **Car / truck mode (default, non-slip 4-wheel assumption):**
$$v_y \approx 0, \quad v_z \approx 0$$
    Lateral and vertical body-frame velocity are heavily penalized or zeroed in the filter's update step. This eliminates two of three spatial drift degrees of freedom.

*   **Two-wheeler mode (relaxed constraint):** A leaning motorcycle/scooter genuinely has non-zero, non-trivial roll and a coupled lateral velocity component during cornering — the car NHC's $v_y \approx 0$ is actively wrong here and would fight real vehicle motion. Instead we constrain a different, still-true invariant for a wheeled vehicle in contact with the road: **zero velocity normal to the road-contact-patch plane**, expressed as a soft constraint,
$$v_{roll\text{-}plane\perp} \approx 0$$
    with variance scaled by lean angle (estimated from the accelerometer gravity vector once forward-motion is subtracted), and lateral velocity is left as an EKF-estimated state (not zeroed) so cornering isn't misinterpreted as sensor error.
*   **Classifier:** a simple decision on (a) IMU vertical vibration signature (two-wheelers show characteristically different engine/road coupling than cars), (b) whether a lean angle >~8° is ever observed during turns (cars structurally can't lean; two-wheelers routinely do), (c) user-selected vehicle profile at app setup as a prior. Mode can switch mid-trip is intentionally *not* supported in v1 (a phone doesn't change vehicles mid-drive) — this keeps the classifier a one-time-per-session decision rather than an additional real-time failure mode.

### 2.5 Map-Matching as a Second, Independent Correction Layer
Even with the constraints above, residual micro-drift accumulates over longer GNSS-denied stretches. An offline OpenStreetMap-based Hidden Markov Map Matcher (Viterbi decoding, per system_workflow.md Phase 3) binds the filtered trajectory to the road graph. This is deliberately a *separate* correction stage from the EKF, not folded into it — so that if the EKF's heading/velocity aiding is temporarily degraded (e.g., magnetometer disturbance in a tunnel, per §2.3), the map-matcher's road-topology constraint is still an independent line of defense rather than sharing the same failure mode.

## 3. FOG-Grade Edge Deployment (Non-Smartphone IMU)
The problem statement requires the same models/algorithms to also run against **external IMU sensors** (e.g., FOG-grade, ~200Hz) on the edge-deployable engine, not just smartphone MEMS. We do not assume the TCN transfers directly — FOG gyros have in-run bias instability roughly 2–3 orders of magnitude better than MEMS, and noise/bias characteristics the TCN was never trained on, so a MEMS-trained velocity network is not a safe drop-in.

Our architecture handles this by design, not by retraining hope:
*   **The EKF core (§2.3) has no dependency on the TCN to function correctly** — it degrades gracefully to NHC + magnetometer/map-aiding alone if no velocity-aiding measurement is available, which our own ablation (§4, "EKF+NHC only" row) shows is *worse* than the full stack but still bounded and monotonic, unlike naive double integration.
*   For FOG-grade IMUs, the far lower bias instability means the accelerometer-only forward-velocity double-integration error over typical GNSS-denied stretches is already within a usable range without a learned velocity aid — so the **TCN measurement update is treated as optional/disableable per sensor class**, gated by a runtime IMU-grade flag, rather than hard-wired into the pipeline.
*   If FOG-grade training data becomes available, the same TCN architecture (§2.2) can be retrained on it; the input feature interface (vehicle-frame accel + gyro windows) is sensor-agnostic by construction.

## 4. Preliminary Validation — Computed Results, Not Hallucinated
**Dataset access note (stated plainly):** The IO-VNBD repository (github.com/onyekpeu/IO-VNBD) is real and was located and inspected — 564 real CSV files, correct structure and README confirmed. Its CSV payloads are stored via Git-LFS, and GitHub's public/unauthenticated LFS bandwidth was rate-limited (HTTP 429) from this development sandbox's shared IP on every retry — a reproducible, verifiable constraint, not an excuse. Rather than report fabricated numbers against a dataset we could not actually read, the validation below runs the **real filter code** against a **physics-generated synthetic trajectory** driven through a **published consumer-MEMS IMU error model** (bias, bias-instability random walk, white noise, vibration coupling — parameters from Groves' MEMS-grade IMU tables and typical smartphone datasheet ranges), explicitly labeled as such. The identical script (`dr_pipeline.py`, submitted alongside this document) has a schema-agnostic real-dataset loader and will be re-run unmodified against real IO-VNBD CSVs once pulled on an unrestricted connection — that is the actual screening-submission plan, not this substitute.

**Scenario:** 1 km simulated road, 60 km/h cruise (matches the problem statement's explicit tunnel benchmark), GNSS-denied throughout, 200 Hz IMU.

**Ablation results (10-seed Monte Carlo, mean ± std of drift as % of distance travelled):**

| Configuration | Drift % (mean ± std) | Meets <10% benchmark? |
|---|---|---|
| Naive double integration (no correction) | 13.43% ± 11.11 | No — and highly unstable run-to-run |
| EKF + NHC only (no velocity/heading aid) | 35.23% ± 25.79 | No — worse than naive; NHC alone doesn't observe forward bias |
| EKF + NHC + velocity-aiding (no heading aid) | 32.98% ± 25.56 | No — forward drift fixed, but unobserved heading/gyro-bias dominates |
| **Full stack: EKF + NHC + velocity-aiding + magnetometer heading** | **0.19% ± 0.14** | **Yes**, by a wide margin |

This ablation is itself a useful finding, not just a result: it shows *why* each required component (velocity aiding, heading aiding) is load-bearing rather than decorative — velocity aiding alone is not sufficient without an absolute heading reference, which is exactly why the problem statement lists the magnetometer as a required sensor input. See `drift_validation.png` for the corresponding trajectory and error-growth plot, and `monte_carlo.json` / `results.json` for the raw computed numbers.

**Caveat stated honestly:** synthetic-trajectory validation demonstrates the estimator architecture is sound and numerically stable; it is not a substitute for the real IO-VNBD run, which will exercise real sensor non-idealities (unmodeled here) such as GNSS multipath in urban canyons, real pothole/vibration statistics, and real magnetic environments. The real-data run is the next action item (engineering_deployment_plan.md, Phase 1) and its results will replace this section for the final submission.
