# ⚙️ C++ Core Engine Documentation

## 1. Overview and Purpose
The **C++ Core Engine** is the production-grade Phase 2 deployment of the Intelligent Dead Reckoning (IDR) system. 

While Python (PyTorch/Pandas) was perfect for training the AI and mathematically validating the Extended Kalman Filter (EKF), Python is too slow and heavy to run on a smartphone or a Fiber Optic Gyro (FOG) navigation box. To hit ISRO's requirement of a **200Hz backend processing rate** and a **10Hz frontend UI rate**, the core math must be executed in native C++.

This engine is compiled as an independent, standalone executable. Eventually, it will be compiled into a Shared Library (`.so` / `.dll`) and hooked into the Flutter/Kotlin mobile app via the Java Native Interface (JNI).

---

## 2. Directory Structure (`cpp_engine/`)

```text
cpp_engine/
│
├── CMakeLists.txt      # Professional C++ build configuration
├── build.py            # Custom Python fallback script for quick Windows compilation
│
├── include/
│   └── ekf.h           # Header definitions for the Error-State EKF class
│
└── src/
    ├── ekf.cpp         # Mathematical implementation using Eigen matrices
    └── main.cpp        # Entry point for the executable
```

---

## 3. File Breakdown & Technical Details

### `include/ekf.h`
The header file defines the `ErrorStateEKF` C++ class. It utilizes the open-source **Eigen3** library to handle matrix algebra exactly like `numpy` did in Python. 

The filter tracks an **8-Dimensional State Vector**:
1. `x` (East Position)
2. `y` (North Position)
3. `heading` (Yaw)
4. `vx_b` (Forward Velocity)
5. `vy_b` (Lateral Velocity)
6. `b_ax` (Accel X Bias)
7. `b_ay` (Accel Y Bias)
8. `b_g` (Gyro Z Bias)

It also manages the $8 \times 8$ Covariance matrix ($P$) and Process Noise matrix ($Q$).

### `src/ekf.cpp`
This is the heavy lifter. It is a direct 1-to-1 C++ port of the mathematical pipeline written in `dr_pipeline.py`. 
* **`predict(ax, ay, gyro_z, dt)`**: Updates the nominal state using simple Euler integration and propagates the covariance matrix ($P = FPF^T + Q$) using the Jacobian state transition matrix ($F$).
* **`updateVelocity(v_tcn, R_vel)`**: Fuses the AI's speed prediction into the filter.
* **`updateNHC(R_nhc)`**: Applies Non-Holonomic Constraints (forces the filter to recognize that a car cannot slide sideways, setting lateral velocity $vy_b \approx 0$).
* **`updateGPS(gps_x, gps_y, R_gps)`**: Snaps the trajectory back to ground truth when GNSS signals are available.

### `src/main.cpp`
Currently serves as the testing sandbox. It initializes the `ErrorStateEKF` object and prints the initial state to the terminal. In the next phase, this file will:
1. Load the quantized INT8 AI model using the **TensorFlow Lite C++ API**.
2. Run the `TCN` inference loop.
3. Expose C-style functions for the Android JNI bridge to call.

### `CMakeLists.txt` & `build.py`
* **CMake**: Configured with `FetchContent` so that it automatically downloads the header-only Eigen3 library from GitLab. This removes the need for developers to manually install C++ dependencies.
* **build.py**: A custom fallback script. Windows users without CMake installed can simply run `python build.py`, which will manually download the Eigen zip, extract it, and invoke the MinGW `g++` compiler directly.

---

## 4. How to Compile and Run

You can compile this engine completely natively on Windows without needing any massive IDEs like Visual Studio.

**Method 1: The Quick Python Script (Recommended for MinGW users)**
```powershell
cd cpp_engine
python build.py
```
*(This script will download Eigen3, invoke `g++`, and immediately run the executable.)*

**Method 2: Standard CMake**
If you have CMake installed, you can generate a professional build environment:
```powershell
cd cpp_engine
mkdir build
cd build
cmake -G "MinGW Makefiles" ..
mingw32-make
.\idr_engine.exe
```

---

## 5. Next Engineering Steps for this Engine
1. **Integrate TensorFlow Lite C++**: We need to load `tiny_tcn_qat_int8.pth` (exported as `.tflite`) into `main.cpp` so the C++ engine can calculate its own AI velocity without relying on Python.
2. **Build the JNI Bridge**: Write C-wrappers so an Android Kotlin app can push live phone IMU sensor data into the `ekf.predict()` function.