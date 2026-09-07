# Intelligent Dead Reckoning (IDR) System & GNSS Fusion Workflow

## Overview
This workflow details the phases for building a lightweight, edge-deployable AI/ML software engine and mobile application for seamless navigation during GNSS outages using smartphone IMUs, map matching, and GNSS+INS fusion.

## Phase 1: Data Preparation & Environmental Setup
1. **Dataset Acquisition:**
   - Download the IO-VNBD dataset (Inertial and Odometry benchmark dataset for ground vehicle positioning).
   - Augment with OpenStreetMap (OSM) data for offline map-matching regions.
2. **Data Preprocessing (Cloud/Desktop):**
   - Parse IMU data (accelerometer, gyroscope, magnetometer arrays).
   - Synchronize IMU readings with GNSS ground truth timestamps.
   - Clean data: apply initial low-pass filters to remove high-frequency noise from engine vibrations.

## Phase 2: AI/ML Model Training (Cloud/Desktop)
1. **AI Speed & Vibration Filter Training:**
   - **Goal:** Estimate forward velocity and acceleration profiles solely from noisy smartphone IMU inputs.
   - **Model:** Train a 1D-CNN or LSTM network.
   - **Input:** Windowed IMU streams.
   - **Output:** Forward velocity (pseudo-speedometer).
2. **GNSS+INS Fusion & Drift Mitigation Model:**
   - **Goal:** Create an AI-based sensor fusion algorithm to replace or augment standard Unscented Kalman Filters (UKF).
   - **Mechanism:** Neural networks to predict INS error states or dynamically adjust the covariance matrix in the Kalman Filter based on vehicle kinematics.
3. **Map-Matching Filter Setup:**
   - Develop a Hidden Markov Model (HMM) or Particle Filter using Non-Holonomic Constraints (NHC) mapped to the offline road vectors (OSM) to snap drifting trajectories back to valid road grids.

## Phase 3: Edge Optimization & Pipeline Integration
1. **Model Quantization:**
   - Convert trained PyTorch/TensorFlow models to edge-friendly formats (e.g., TFLite, ONNX, or CoreML) for local inference on mobile devices and edge hardware.
2. **In-Vehicle Alignment Engine:**
   - Implement an auto-calibrating algorithm that determines pitch, roll, and yaw offset of the smartphone relative to the vehicle's driving direction by averaging gravity vectors and observing initial acceleration/turn patterns.
3. **Seamless GNSS Deficit Handler:**
   - Build a real-time monitor that checks GNSS Dilution of Precision (DOP) and signal dropouts.
   - Mechanism to switch from loosely-coupled GNSS-INS to purely dead reckoning (AI+INS+Map Matching) in <10ms.

## Phase 4: Mobile Application & Edge Engine Development
1. **App Architecture (Smartphone):**
   - **Frontend:** Real-time smooth navigation UI showing the vehicle icon with map data.
   - **Backend/Background Process:** 10Hz position update rate polling the optimized TFLite models against raw device IMU buffers (Android `SensorManager` / iOS `CoreMotion`).
2. **Edge Engine Software (Optional FOG sensors):**
   - Develop identical C++/Python runtime capable of processing 200Hz+ Fiber Optic Gyroscope (FOG) IMU data for non-smartphone vehicle systems.

## Phase 5: Testing & Performance Benchmarking
1. **Dead Reckoning Benchmark Validation:**
   - Target 1: < 5m drift over 50m GNSS-denied environment in < 1 minute.
   - Target 2: < 100m drift over a 1km GNSS-denied environment at 60kmph (tunnels/undergrounds).
2. **Iterative Refinement:**
   - Test against the IO-VNBD subsets.
   - Refine the AI Speed model to better reject pothole shocks and idle engine chassis vibrations.
