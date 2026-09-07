#pragma once
#include <vector>
#include <string>

class TensorPreprocessor {
private:
    std::vector<double> mean_;
    std::vector<double> scale_;

    // Butterworth filter constants (4th order, 10Hz cutoff at 200Hz fs) extracted from Python SciPy
    const std::vector<double> b = {0.0004165992044066, 0.0016663968176264, 0.0024995952264396, 0.0016663968176264, 0.0004165992044066};
    const std::vector<double> a = {1.0, -3.1806385488747, 3.8611943489942, -2.1121553551110, 0.4382651422620};
    const std::vector<double> zi_base = {0.9995834007958, -2.1827215448972, 1.6759732088713, -0.4378485430577};

    void apply_filter(std::vector<double>& channel);

public:
    TensorPreprocessor(const std::string& scaler_json_path);
    
    // Processes a 200x6 raw IMU window into a 200x12 ML-ready tensor
    std::vector<std::vector<double>> process_window(const std::vector<std::vector<double>>& raw_window);
};
