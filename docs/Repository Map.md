# 🗺️ REPOSITORY MAP — Intelligent Dead Reckoning (ISRO 26168)

> **Purpose of this file**: This is the single-source-of-truth index for the entire repository.
> Any AI agent working on this project should read this file FIRST before exploring any folders.
> It eliminates the need to scan directories one-by-one, saving tokens and time.

> **Last Updated**: 2026-09-03

---

## 📌 PROJECT IDENTITY

| Field | Value |
|---|---|
| **Project Title** | AI-ML based Intelligent Dead Reckoning for GNSS-Denied Environments |
| **Problem Statement**| ISRO Problem Statement 26168 |
| **Repository Root** | `D:\Dead Reckoning Nav System\Dead-reckoning-system-for-seamless-navigation\` |
| **Current Phase** | Phase 2 (Core Engine) — Python EKF logic successfully ported to C++ (Eigen). Next: TFLite Integration. |
| **Goal** | Train a TCN to predict velocity from IMU data → Fuse with Error-State EKF + NHC → Maintain <10% drift over 1km without GNSS. |
| **Target Hardware** | Edge Devices (Smartphones / FOG-class Navigation Engines) |
| **Dataset** | IO-VNBD Dataset (High-frequency Smartphone IMU + Vehicle GPS Ground Truth) |
| **Core Architecture** | Strapdown Mechanization + Error-State Extended Kalman Filter (EKF) + Temporal Convolutional Network (TCN) + Hidden Markov Model (HMM) Map Snapping. |

---

## 🏗️ TOP-LEVEL DIRECTORY STRUCTURE

```text
D:\Dead Reckoning Nav System\Dead-reckoning-system-for-seamless-navigation\
│
├── .git/                          # Git version control
├── .gitignore                     # Ignores: venv, __pycache__, data/, results/*.npz
├── cpp_engine/                    # C++ Core Engine (EKF & TFLite port)
├── data/                          # Raw IO-VNBD datasets (GITIGNORED)
├── docs/                          # Documentation, logs, architecture plans, and THIS map
├── results/                       # Outputs, metrics, and plots from pipeline runs
│   └── processed_data/            # 72 generated ML-ready tensor files (.npz)
└── src/                           # Python source code (Preprocessing, ML, EKF)
```

---

## 📂 DETAILED DIRECTORY & FILE MAP

### 1. `docs/` — Documentation & Planning

> **Purpose**: Human-readable project documentation, design decisions, and reference materials.

| File | Type | Description |
|---|---|---|
| `Repository Map.md` | 📄 File | **THIS FILE** — The master index for the entire repo. |
| `core_mechanism.md` | 📄 File | Detailed mathematical explanation of the EKF, NHC, and TCN architecture. |
| `engineering_deplyment_plan.md`| 📄 File | Software engineering stack, phasing (Phases 1-4), and deployment strategy. |
| `system_workflow.md` | 📄 File | End-to-end execution steps (Phase 0 to Phase 4) for edge deployment. |
| `IDR_project_workflow.md` | 📄 File | High-level project workflow documentation. |
| `log.md` | 📄 File | Chronological step-by-step log of actions taken during development. |
| `model_experiments_log.md` | 📄 File | Scientific journal tracking AI tweaks, RMSE results, failure diagnoses, and iteration fixes. |
| `🧪 AI Model Experiment & Tuning Log.md` | 📄 File | Primary model tuning journal with 6 iterations, comparison tables, and stopping justification. |
| `Model Tuning Graphs/` | 📁 Folder | Contains speed_evaluation plots for each training iteration (Iteration 1-6). |

---

### 2. `cpp_engine/` — Core Engine (C++)

> **Purpose**: High-performance port of the EKF pipeline, designed to run directly on edge devices (Smartphones / FOG engines).

| File / Folder | Description |
|---|---|
| `CMakeLists.txt` | Build configuration; automatically downloads Eigen3 via FetchContent. |
| `build.py` | Python fallback script to compile the engine using MinGW `g++`. |
| `include/ekf.h` | 8-State Error-State EKF header file (Eigen matrix definitions). |
| `src/ekf.cpp` | C++ implementation of the prediction, NHC, Velocity, and GPS updates. |
| `src/main.cpp` | Entry point for testing the C++ filter. |

---

### 3. `src/` — Source Code (Pipeline & ML)

> **Purpose**: Contains all Python scripts for data processing, model training, and filter execution. Must be executed in sequential pipeline order.

| # | Script | Pipeline Stage | Description |
|---|---|---|---|
| 1 | `preprocess.py` | **Data Preparation** | Synchronizes Smartphone (S) and Vehicle (V) CSVs, applies Butterworth low-pass filter (10Hz cutoff), normalizes via Z-score, and slices into 1-second overlapping windows. Outputs `.npz` tensors. |
| 2 | `train_tcn.py` | **AI Training** | PyTorch script that implements Quantization-Aware Training (QAT) on a TCN. Outputs a highly compressed INT8 model (`tiny_tcn_qat_int8.pth`) designed to predict velocity and variance on edge hardware. |
| 3 | `evaluate_model.py` | **AI Evaluation** | Evaluates the INT8 model on unseen routes. Calculates RMSE, MAE, and outputs a visual line graph (`speed_evaluation.png`) comparing AI-predicted speed vs. true speed. |
| 4 | `residual_analysis.py` | **Statistical Diagnostics** | Generates Data Science diagnostic plots (Distribution, ACF, Heteroscedasticity, Time-Series Overlay) to mathematically prove the need for EKF smoothing. |
| 5 | `integrate_tcn_ekf.py` | **TCN → EKF Integration** | Connects the trained TCN velocity estimator to the Error-State EKF. Loads real IO-VNBD sessions, simulates 60s GPS outage, runs AI dead reckoning, and calculates position drift percentage (ISRO metric). Outputs `tcn_ekf_integration.png` and `tcn_ekf_results.json`. |
| 6 | `dr_pipeline.py` | **Validation & EKF** | Core dead-reckoning mathematical baseline. Implements Naive double integration vs. full Error-State EKF + NHC + velocity-aiding. Validates the architecture's ability to hit <10% drift on synthetic data. |
| 7 | `export_model.py` | **Model Export** | Reconstructs the trained INT8 model into a clean architecture and exports it as a universal `tiny_tcn.onnx` file for the Android/Flutter C++ deployment. |

---

### 4. `data/` — Datasets

> **Path**: `data/`
> **Git Status**: ⛔ GITIGNORED (too large)
> **Purpose**: Contains the unzipped IO-VNBD dataset.

| Folder / File Pattern | Description |
|---|---|
| `IO-VNBD/` | Root dataset folder from the IO-VNBD GitHub repository. |
| `S-*.csv` | Smartphone datasets: High-frequency IMU data (Accel X/Y/Z, Gyro X/Y/Z, Magnetometer). |
| `V-*.csv` | Vehicle datasets: Ground truth data (GPS Speed, Latitude, Longitude). |

---

### 5. `results/` — Pipeline Outputs

> **Path**: `results/`
> **Purpose**: Generated files from running the scripts in `src/`.

| File / Folder | Status | Description |
|---|---|---|
| `processed_data/` | ⛔ Ignored | Folder containing 72 `.npz` tensor files generated by `preprocess.py`. |
| `processed_data/global_scaler.pkl` | ⛔ Ignored | Dataset-wide StandardScaler fitted across all 72 files. Used for consistent inference-time normalization. |
| `saved_models/tiny_tcn_qat_int8.pth` | ✅ Tracked | INT8 Quantized TCN model (Iteration 6). 4-block architecture, 11 input channels, RMSE 28.80. |
| `saved_models/tiny_tcn.onnx` | ✅ Tracked | Universal Float32 ONNX graph. Extracted precisely from the INT8 model for edge deployment in C++/Flutter. |
| `residual_plots/` | ✅ Tracked | Directory containing 4 diagnostic plots (ACF, KDE, etc.) from `residual_analysis.py`. |
| `speed_evaluation.png` | ✅ Tracked | Line graph overlaying True Speed vs AI-Predicted Speed from unseen testing data. |
| `tcn_ekf_integration.png` | ✅ Tracked | Trajectory comparison plot: GPS ground truth vs AI dead reckoning during simulated GPS outage. |
| `tcn_ekf_results.json` | ✅ Tracked | Position drift percentage and error metrics from the TCN+EKF integration test. |
| `drift_validation.png` | ✅ Tracked | Visual plot comparing ground-truth trajectory vs baseline drift vs EKF drift (synthetic). |
| `monte_carlo.json` | ✅ Tracked | Statistical summary (mean, std, min, max) of drift across 10 random seeds (synthetic). |
| `results.json` | ✅ Tracked | Exact final error (m) and drift (%) for a single deterministic seed run (synthetic). |
| `traces.npz` | ⛔ Ignored | Large numpy array containing exact (X, Y) coordinates of the filtered trajectory. |

---

## 🔗 PIPELINE FLOW DIAGRAM

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                    PHASE 1: ML PIPELINE & PREPROCESSING                 │
│                                                                         │
│  ┌────────────┐    ┌─────────────┐    ┌─────────────┐    ┌────────────┐ │
│  │  S-*.csv   │    │ preprocess  │    │  train_tcn  │    │ Quantize & │ │
│  │  V-*.csv   │───▶│     .py     │───▶│     .py     │───▶│ Export     │ │
│  └────────────┘    └─────────────┘    └─────────────┘    └────────────┘ │
│    (data/)          (synchronize,      (Build & train      (.tflite/    │
│                     filter, slice)      predict v_x)        .onnx)      │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                    PHASE 2: EKF FILTER EXECUTION                        │
│                                                                         │
│  ┌────────────┐    ┌─────────────┐    ┌─────────────┐    ┌────────────┐ │
│  │ dr_pipeline│    │ Error-State │    │ Non-Holonom │    │ results/   │ │
│  │     .py    │───▶│    EKF      │───▶│ Constraints │───▶│ metrics    │ │
│  └────────────┘    └─────────────┘    └─────────────┘    └────────────┘ │
│                    (Propagate 200Hz)   (Update step)       (<10% drift) │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 📋 KEY TECHNICAL PARAMETERS

| Parameter | Value |
|---|---|
| IMU Sampling Frequency | ~100-200 Hz (Smartphone Dependent) |
| GNSS Sampling Frequency | 1 Hz |
| Low-Pass Filter | 4th-Order Butterworth (10Hz Cutoff) |
| Window Size (TCN Input) | 1.0 seconds |
| Stride (TCN Input) | 0.5 seconds |
| Normalization | Z-Score (Mean=0, Var=1) |
| EKF State Vector (8-state)| `[x, y, heading, vx_b, vy_b, b_ax, b_ay, b_g]` |
| ISRO Benchmark Target | < 10% drift over 1km GNSS-denied environment (< 100m) |

---

## 🔍 QUICK LOOKUP — "Where is...?"

| I Need... | Go To |
|---|---|
| Raw Smartphone IMU CSVs | `data/IO-VNBD/Synchronised V abd S datasets/...` |
| The math explaining the filter | `docs/core_mechanism.md` |
| The preprocessing logic | `src/preprocess.py` |
| The AI model training code | `src/train_tcn.py` |
| The TCN+EKF integration test | `src/integrate_tcn_ekf.py` |
| The EKF baseline testing script | `src/dr_pipeline.py` |
| The model tuning history & decisions | `docs/🧪 AI Model Experiment & Tuning Log.md` |
| The drift validation results (synthetic) | `results/results.json` |
| The drift validation results (real data) | `results/tcn_ekf_results.json` |
| The next engineering steps | `docs/engineering_deplyment_plan.md` |

---

## ⚠️ IMPORTANT NOTES FOR AI AGENTS

1. **Do NOT explore `data/` unnecessarily** — It contains very large `.csv` and `.zip` files that will consume your context window.
2. **Always read `docs/` files first** — `core_mechanism.md` contains the explicit mathematical and architectural constraints for this project.
3. **Execution context** — Scripts in `src/` should be executed from the `src/` directory to ensure relative paths resolve correctly.
