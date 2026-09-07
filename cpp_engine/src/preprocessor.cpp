#include "preprocessor.h"
#include "json.hpp" // nlohmann/json
#include <Eigen/Dense>
#include <fstream>
#include <iostream>
#include <cmath>
#include <stdexcept>

using json = nlohmann::json;

TensorPreprocessor::TensorPreprocessor(const std::string& scaler_json_path) {
    std::ifstream file(scaler_json_path);
    if (!file.is_open()) {
        throw std::runtime_error("Could not open scaler JSON file: " + scaler_json_path);
    }
    
    json j;
    file >> j;
    
    for (double v : j["mean"]) mean_.push_back(v);
    for (double v : j["scale"]) scale_.push_back(v);
    
    if (mean_.size() != 11 || scale_.size() != 11) {
        throw std::runtime_error("Invalid scaler dimensions in JSON. Expected 11 channels.");
    }
}

void TensorPreprocessor::apply_filter(std::vector<double>& channel) {
    if (channel.empty()) return;
    
    size_t n = channel.size();
    std::vector<double> y(n, 0.0);
    
    // Initial state scaling identical to lfilter_zi(b, a) * data[0]
    std::vector<double> z(4, 0.0);
    for (int i = 0; i < 4; ++i) {
        z[i] = zi_base[i] * channel[0];
    }

    // Causal IIR Filter Pass (identical to scipy.signal.lfilter)
    for (size_t i = 0; i < n; ++i) {
        double x_val = channel[i];
        y[i] = b[0] * x_val + z[0];
        z[0] = b[1] * x_val + z[1] - a[1] * y[i];
        z[1] = b[2] * x_val + z[2] - a[2] * y[i];
        z[2] = b[3] * x_val + z[3] - a[3] * y[i];
        z[3] = b[4] * x_val        - a[4] * y[i];
    }
    
    channel = y;
}

std::vector<std::vector<double>> TensorPreprocessor::process_window(const std::vector<std::vector<double>>& raw_window) {
    size_t window_size = raw_window.size();
    if (window_size == 0) return {};

    // 1. Extract and strictly filter the 6 base channels
    std::vector<std::vector<double>> channels(6, std::vector<double>(window_size));
    for (size_t t = 0; t < window_size; ++t) {
        for (int c = 0; c < 6; ++c) {
            channels[c][t] = raw_window[t][c];
        }
    }
    
    for (int c = 0; c < 6; ++c) {
        apply_filter(channels[c]);
    }

    // 2. Engineer the 5 derived physics channels (acc_mag, gyro_mag, jerk_x, jerk_y, jerk_z)
    std::vector<std::vector<double>> engineered(5, std::vector<double>(window_size));
    double dt = 1.0 / 200.0;
    
    for (size_t t = 0; t < window_size; ++t) {
        engineered[0][t] = std::sqrt(std::pow(channels[0][t], 2) + std::pow(channels[1][t], 2) + std::pow(channels[2][t], 2)); // acc_mag
        engineered[1][t] = std::sqrt(std::pow(channels[3][t], 2) + std::pow(channels[4][t], 2) + std::pow(channels[5][t], 2)); // gyro_mag
        
        if (t == 0) {
            engineered[2][t] = 0.0;
            engineered[3][t] = 0.0;
            engineered[4][t] = 0.0;
        } else {
            // Strict causal backward difference for jerk
            engineered[2][t] = (channels[0][t] - channels[0][t-1]) / dt; 
            engineered[3][t] = (channels[1][t] - channels[1][t-1]) / dt; 
            engineered[4][t] = (channels[2][t] - channels[2][t-1]) / dt; 
        }
    }
    
    // Combine to 11 unified channels
    std::vector<std::vector<double>> all_channels = channels;
    for (int c = 0; c < 5; ++c) all_channels.push_back(engineered[c]);
    
    // 3. Normalize via StandardScaler JSON params
    for (int c = 0; c < 11; ++c) {
        for (size_t t = 0; t < window_size; ++t) {
            all_channels[c][t] = (all_channels[c][t] - mean_[c]) / scale_[c];
        }
    }
    
    // 4. Orientation Independence: Compute PCA on normalized accelerometer data
    Eigen::MatrixXd accel_mat(window_size, 3);
    for (size_t t = 0; t < window_size; ++t) {
        accel_mat(t, 0) = all_channels[0][t];
        accel_mat(t, 1) = all_channels[1][t];
        accel_mat(t, 2) = all_channels[2][t];
    }
    
    Eigen::VectorXd mean_accel = accel_mat.colwise().mean();
    Eigen::MatrixXd centered = accel_mat.rowwise() - mean_accel.transpose();
    Eigen::JacobiSVD<Eigen::MatrixXd> svd(centered, Eigen::ComputeThinV);
    Eigen::VectorXd pca_proj = centered * svd.matrixV().col(0);
    
    // 5. Construct Final 12-Channel TCN Tensor Input (Sequence, Channels)
    std::vector<std::vector<double>> output(window_size, std::vector<double>(12));
    for (size_t t = 0; t < window_size; ++t) {
        for (int c = 0; c < 11; ++c) {
            output[t][c] = all_channels[c][t];
        }
        output[t][11] = pca_proj(t);
    }
    
    return output;
}
