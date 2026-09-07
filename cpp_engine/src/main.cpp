#include <iostream>
#include <string>
#include <vector>
#include <cmath>
#include "ekf.h"
#include "preprocessor.h"
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
        const wchar_t* model_path = L"../../results/saved_models/tiny_tcn_fp32.onnx";
#else
        const char* model_path = "tiny_tcn_fp32.onnx";
#endif

        // 4. Instantiate the Inference Session
        Ort::Session session(env, model_path, session_options);
        
        std::cout << "✅ Successfully loaded INT8 ONNX Model into Inference Session!" << std::endl;
        
        // 5. Verify I/O Nodes
        std::cout << "Number of Input Nodes: " << session.GetInputCount() << std::endl;
        std::cout << "Number of Output Nodes: " << session.GetOutputCount() << std::endl;

        // --- PREPROCESSING ---
        std::cout << "\nInitializing Preprocessor..." << std::endl;
        TensorPreprocessor preprocessor("../../results/processed_data/scaler_params.json");
        
        // Mock a raw IMU buffer (200 samples x 6 channels) representing 1.0 second of driving.
        // In Android, you will bridge this vector from your CoreGnssDeficitHandler.kt sliding window.
        std::vector<std::vector<double>> raw_imu(200, std::vector<double>(6, 0.5)); 
        
        // Preprocess: filter, engineer physics, normalize, and extract PCA
        auto processed_tensor = preprocessor.process_window(raw_imu); // Shape: [200][12]

        // --- TENSOR FORMATTING ---
        // PyTorch/ONNX expects shape: [Batch=1, Channels=12, Sequence=200]
        // We must flatten the 2D vector in Channel-Major order and cast to Float32.
        std::vector<float> input_tensor_values(1 * 12 * 200);
        for (int c = 0; c < 12; ++c) {
            for (int s = 0; s < 200; ++s) {
                input_tensor_values[c * 200 + s] = static_cast<float>(processed_tensor[s][c]);
            }
        }

        // --- INFERENCE ---
        Ort::MemoryInfo memory_info = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        std::vector<int64_t> input_shape = {1, 12, 200};
        
        Ort::Value input_tensor = Ort::Value::CreateTensor<float>(
            memory_info, input_tensor_values.data(), input_tensor_values.size(), input_shape.data(), input_shape.size());

        const char* input_names[] = {"input"};
        const char* output_names[] = {"mu", "log_var"};

        std::cout << "Running TCN Inference..." << std::endl;
        std::vector<Ort::Value> output_tensors = session.Run(
            Ort::RunOptions{nullptr}, input_names, &input_tensor, 1, output_names, 2);

        // --- EXTRACT ALEATORIC OUTPUTS ---
        float* mu_ptr = output_tensors[0].GetTensorMutableData<float>();
        float* log_var_ptr = output_tensors[1].GetTensorMutableData<float>();

        float predicted_speed = mu_ptr[0];
        float log_variance = log_var_ptr[0];
        float actual_variance = std::exp(log_variance); // Convert log space back to physical variance

        std::cout << "\n🏆 --- INFERENCE RESULTS --- 🏆" << std::endl;
        std::cout << "Predicted Speed (mu): \t" << predicted_speed << " km/h" << std::endl;
        std::cout << "Aleatoric Variance (\u03C3\u00B2):\t" << actual_variance << std::endl;

        // DYNAMIC SENSOR FUSION: 
        // Feed the AI's speed and its real-time confidence (variance) directly into the Kalman Filter!
        // The EKF uses 'actual_variance' as its Measurement Noise Covariance (R).
        ekf.updateVelocity(predicted_speed, actual_variance);
        
        std::cout << "\n✅ EKF State Updated! Current Filter Position: (" << ekf.getX() << ", " << ekf.getY() << ")" << std::endl;

    } catch (const Ort::Exception& e) {
        std::cerr << "❌ ONNX Runtime Error: " << e.what() << std::endl;
        return -1;
    } catch (const std::exception& e) {
        std::cerr << "❌ C++ Error: " << e.what() << std::endl;
        return -1;
    }

    return 0;
}
