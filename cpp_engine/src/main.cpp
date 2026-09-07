#include <iostream>
#include <string>
#include <vector>
#include "ekf.h"
#include <onnxruntime_cxx_api.h>

int main() {
    std::cout << "Starting TunnelSense IDR Core Engine (C++)..." << std::endl;
    
    // Initialize the Error-State Kalman Filter
    ErrorStateEKF ekf;
    std::cout << "EKF Initialized. Initial Position: (" << ekf.getX() << ", " << ekf.getY() << ")\n" << std::endl;

    // --- ONNX RUNTIME INITIALIZATION ---
    std::cout << "Initializing ONNX Runtime..." << std::endl;
    try {
        // 1. Create the ONNX Environment
        Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "TunnelSense_TCN");
        
        // 2. Configure Session Options for Edge Inference
        Ort::SessionOptions session_options;
        session_options.SetIntraOpNumThreads(1); // Single thread is usually best for mobile/edge CPUs
        session_options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

        // 3. Define Model Path (Handles Windows wchar_t requirement)
#ifdef _WIN32
        const wchar_t* model_path = L"../../results/saved_models/tiny_tcn_int8.onnx";
#else
        const char* model_path = "../../results/saved_models/tiny_tcn_int8.onnx";
#endif

        // 4. Instantiate the Inference Session
        Ort::Session session(env, model_path, session_options);
        
        std::cout << "✅ Successfully loaded INT8 ONNX Model into Inference Session!" << std::endl;
        
        // 5. Verify I/O Nodes
        std::cout << "Number of Input Nodes: " << session.GetInputCount() << std::endl;
        std::cout << "Number of Output Nodes: " << session.GetOutputCount() << std::endl;

    } catch (const Ort::Exception& e) {
        std::cerr << "❌ ONNX Runtime Error: " << e.what() << std::endl;
        return -1;
    }

    // TODO: Load IMU sliding window buffers
    // TODO: Run inference loop and feed speed (mu) and variance (log_var) into EKF

    return 0;
}
