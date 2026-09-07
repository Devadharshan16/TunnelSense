# Repository Audit & Remediation Plan: InEKF / TCN Dead Reckoning Engine

**Auditor:** Senior MLE (Quant/Systems Architecture)
**Current Repository Grade:** 2/10 (Critical Failure / Not Production Ready)

## 1. Fatal Errors & Data Leakage (The "Kill" List)

### Overview of Failures
The current training pipeline is mathematically invalid due to severe data leakage and non-causal operations. 
*   **Global Normalization Leakage:** The `StandardScaler` is fitted over the entire dataset before the train/test split. The test distribution is leaking into the training weights, rendering the reported RMSE statistically fraudulent.
*   **Non-Causal Operations:** The pipeline uses `filtfilt` for noise reduction and `np.gradient()` for jerk calculation. Both of these functions look ahead into the future to calculate the current timestep. An edge device running a real-time 200Hz loop cannot look into the future.
*   **Timestamp Misalignment:** IMU and GNSS streams are independently zeroed and interpolated without clock-offset estimations or monotonicity checks. 

### The Fix (Immediate Execution)
1.  **Enforce Strict Splitting:** Refactor `preprocess.py`. Perform a hard split by route/session *before* any normalization. Fit the `StandardScaler` strictly on the training partition and freeze the parameters (`scaler.json`) for inference.
2.  **Causal Signal Processing:** Strip `filtfilt` and `np.gradient()` entirely. Replace the filter with a causal IIR/FIR filter that maintains an explicit state matrix. Replace jerk calculations with a strict backward-difference computation ($a_t - a_{t-1}$).
3.  **Rigorous Synchronization:** Implement a cross-correlation function to estimate and correct clock drift between the IMU and GNSS timestamps before interpolation.

---

## 2. Preprocessing Rigor

### Overview of Failures
The pipeline completely ignores the physical realities of the smartphone hardware.
*   **Missing Frame Transformation:** The model currently trains on raw phone-frame axes. Because the phone can be mounted at any angle, the network is trying to learn arbitrary coordinate geometry instead of vehicle dynamics. 
*   **Bias Ignorance:** There is no explicit accelerometer/gyroscope calibration, gravity vector removal, or per-session bias initialization. Standardizing the data does not remove hardware bias.
*   **Frequency Mismatch:** The Python pipeline dynamically scales to sampling rates, but the Android execution environment explicitly downsamples to 10Hz. We are building a 200Hz engine; a 10Hz interface is unacceptable for an InEKF.

### The Fix (Immediate Execution)
1.  **Implement PCA Auto-Alignment:** Before passing data to the model, implement a Principal Component Analysis (PCA) script on the first 10 seconds of active GNSS driving data to generate a $3 \times 3$ Rotation Matrix ($R_{phone}^{car}$). Multiply all incoming raw IMU data by this matrix to enforce a strict vehicle-frame representation.
2.  **Gravity and Bias Extraction:** Subtract the gravity vector ($9.81 m/s^2$) aligned with the Z-axis post-rotation. Initialize sensor bias arrays using the first 5 seconds of stationary data.
3.  **Enforce 200Hz Contract:** Rewrite the Android data ingestion layer to buffer raw IMU arrays at 200Hz and pass them to the native C++ engine without subsampling.

---

## 3. Model Architecture & Latency

### Overview of Failures
While selecting a Temporal Convolutional Network (TCN) is architecturally sound for sequence data, the execution is bloated and lacks probabilistic outputs.
*   **Bloat:** The model contains 355,329 trainable parameters and requires 3.25 million MACs per window. This is too heavy for a strict 5ms latency budget on a mobile CPU.
*   **Deterministic Output:** The TCN outputs a single scalar velocity. It does not output variance. The native C++ EKF is using a hard-coded variance matrix. A Kalman filter cannot trust an AI without a dynamic uncertainty metric.

### The Fix (Immediate Execution)
1.  **Heteroscedastic Loss:** Modify the TCN's final fully connected layer to output two values: $\mu$ (velocity prediction) and $\sigma^2$ (variance). Change the loss function from standard MSE to Gaussian Negative Log-Likelihood (NLL). 
2.  **Pruning & Quantization:** Strip the convolution channels down (e.g., 32 -> 64 -> 128). We must implement INT8 Quantization-Aware Training (QAT) to compress the weights before exporting to ONNX/TFLite.

---

## 4. The Grade & Verdict

**Final Score: 2/10**

**Reasoning:** The architecture is directionally correct (TCN + EKF), but the implementation is fundamentally broken. Data leakage invalidates all current performance metrics. The lack of frame transformation and causal filtering means this model will instantly fail if deployed in a real vehicle.

## 5. Production Roadmap (Missing & Defective Components)

**Status of Existing Scaffolding:** 
We have a baseline to work with. PyTorch QAT (Quantization-Aware Training) scripts exist, an ONNX export script is present, and the C++ Eigen EKF is structurally initialized. However, the glue binding these systems is completely broken.

To bridge this Python research prototype to a deterministic C++ edge engine, the following six pipelines must be built or rebuilt immediately:

### 1. Leakage-Free Preprocessing Pipeline
*   **Action:** Build an explicit route/session manifest to guarantee strict data segregation.
*   **Action:** Enforce train-only scaler fitting.
*   **Action:** Implement strictly causal filtering and causal backward-difference jerk calculations.
*   **Action:** Execute cross-correlation synchronization for timestamp offset estimation.
*   **Action:** Implement the PCA-based vehicle-frame calibration.

### 2. Valid Model Contract (The C++/Python Bridge)
*   **Action:** Standardize the frequency contract. The system must explicitly lock to either a 200Hz or 10Hz inference cycle. The current mismatch will crash the EKF.
*   **Action:** Ensure Python (training) and Android (inference) preprocessing are bitwise-equivalent. 
*   **Action:** Embed model metadata directly into the export artifact (sample rate, window length, stride, feature order, scaler weights, and filter state).

### 3. Uncertainty & Probabilistic Output
*   **Action:** Restructure the model head to output two nodes: Velocity ($\mu$) and Log Variance ($\log\sigma^2$).
*   **Action:** Train using Stable Gaussian Negative Log-Likelihood (NLL).
*   **Action:** Route the dynamic predicted variance directly into the C++ EKF's measurement noise covariance matrix ($R$), rather than using the current hard-coded constant.

### 4. True INT8 Quantization
*   **Action:** Fix the ONNX export script. Currently, the PyTorch INT8 artifact is being dequantized back into a float model during export. The deployed `.onnx` file is not a genuine INT8 artifact, destroying our latency budget.
*   **Action:** Implement ONNX Runtime static INT8 quantization or a full-integer TensorFlow Lite conversion using a representative calibration dataset.

### 5. C++ Inference Wrapper
*   **Action:** Gut the current `main.cpp` (which only contains TODOs). 
*   **Action:** Build the ONNX Runtime or TFLite C++ integration.
*   **Action:** Implement a thread-safe, reusable inference session that handles tensor preprocessing and scaler loading in native C++.
*   **Action:** Write a C++/Python numerical parity test to prove the C++ output exactly matches the Python output to 5 decimal places.

### 6. Artifact Packaging (Crash Prevention)
*   **Action:** Package the missing dependencies. The Android runner currently requires `tiny_tcn.onnx.data` and `scaler_params.json`, but only the core `.onnx` file is present. Without the scaler params, the system will divide by zero during normalization and crash on initialization.

---

### Final Senior Verdict
The repository currently contains a promising research prototype, not a deterministic production state-estimation engine. 

**The first release blocker is the removal of preprocessing leakage and future-data dependencies.** Until that is resolved, every reported model metric (including the claimed 4.8% drift) is statistically invalid.



# Implementation Workflow: InEKF / TCN Dead Reckoning Engine

**Objective:** Build a deterministic, leakage-free 200Hz state-estimation engine using the IO-VNBD dataset, bridging a Python TCN model to a bare-metal C++ InEKF edge environment.

---

## Phase 1: Data Sanity & Leakage-Free Preprocessing
*Do not touch the model architecture until the data pipeline is physically and temporally sound.*

### Step 1.1: Strict Session Segregation
1. Parse the IO-VNBD dataset and split the files explicitly by route and driver session (e.g., Session A for training, Session B for validation).
2. Instantiate the `StandardScaler`. Fit it **only** on the training session data.
3. Save the fitted scaler parameters immediately to `scaler_params.json`.

### Step 1.2: Causal Signal Processing
1. Remove all instances of `filtfilt` and `np.gradient()` from the Python pipeline.
2. Implement an explicitly causal IIR low-pass filter (e.g., using `scipy.signal.lfilter` with maintained state).
3. Compute jerk using a strict causal backward difference: `jerk[t] = (accel[t] - accel[t-1]) / dt`.

### Step 1.3: Vehicle-Frame Alignment (PCA)
1. Write a script to isolate the first 10 seconds of each route where GNSS velocity is strictly forward and accelerating.
2. Run Principal Component Analysis (PCA) on the accelerometer data for that window to extract the dominant variance vector.
3. Generate a $3 \times 3$ Rotation Matrix ($R_{phone}^{car}$) and multiply all raw IMU data by this matrix before feeding it into the model windowing function.

---

## Phase 2: Model Architecture & Probabilistic Output
*The model must predict velocity and its own uncertainty for the Kalman filter to trust it.*

### Step 2.1: Implement Heteroscedastic Output
1. Modify the final fully connected layer of the Temporal Convolutional Network (TCN) to output two distinct nodes: $\mu$ (predicted velocity) and $\log(\sigma^2)$ (predicted log variance).
2. Replace the Mean Squared Error (MSE) loss function with a Gaussian Negative Log-Likelihood (NLL) loss function.

### Step 2.2: Establish the 200Hz Contract
1. Enforce a strict 200Hz sampling rate across all training windows. 
2. Ensure the window size (e.g., 200 samples for a 1-second rolling window) matches the exact buffer size the Android hardware will provide to the C++ engine.

### Step 2.3: Train and Validate
1. Train the modified TCN.
2. Validate that the predicted variance spikes correctly during periods of high sensor noise (e.g., potholes or sudden braking in the IO-VNBD dataset).

---

## Phase 3: Quantization & Valid Edge Export
*Export the model strictly for edge latency, avoiding the float dequantization bug.*

### Step 3.1: INT8 Quantization
1. Utilize PyTorch Quantization-Aware Training (QAT) to compress the model weights to INT8.
2. Use a representative calibration dataset (a subset of the training data) to calibrate the activation ranges.

### Step 3.2: ONNX Export
1. Export the model to ONNX. 
2. Explicitly verify the output graph using Netron or an ONNX graph checker to ensure the weights remain in INT8 format and are not silently cast back to FP32 by the exporter.
3. Save the valid artifact as `tiny_tcn_int8.onnx`.

---

## Phase 4: C++ Inference Wrapper & EKF Integration
*Bridge the Python artifact to the bare-metal C++ execution environment.*

### Step 4.1: C++ ONNX Runtime Integration
1. In `main.cpp`, instantiate an ONNX Runtime C++ Inference Session.
2. Write a C++ tensor preprocessor that mirrors the Python pipeline identically: it must load `scaler_params.json`, apply the causal IIR filter, and normalize the raw 200Hz IMU buffers.

### Step 4.2: EKF Measurement Update Pipeline
1. Feed the normalized tensor into the ONNX model to extract $\mu$ and $\log(\sigma^2)$.
2. Convert $\log(\sigma^2)$ to variance ($\sigma^2$). 
3. Dynamically inject this variance into the Measurement Noise Covariance Matrix ($R$) of the InEKF.
4. Execute the EKF update step using $\mu$ as the pseudo-velocity measurement.

---

## Phase 5: Android Packaging & JNI Bridge
*Connect the C++ engine to the front-end user interface.*

### Step 5.1: Artifact Packaging
1. Ensure `tiny_tcn_int8.onnx` and `scaler_params.json` are packaged correctly in the Android `assets` folder.
2. Write the initialization logic to load both files into memory during the app's startup sequence.

### Step 5.2: JNI Delivery
1. Set up the Java Native Interface (JNI) bridge to pass raw IMU data from the Android OS down to the C++ engine at 200Hz.
2. Pass the map-matched, filtered $(x, y)$ coordinates from the C++ engine back up to the Android UI at 10Hz to render the vehicle icon smoothly on the map.