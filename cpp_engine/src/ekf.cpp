#include "ekf.h"
#include <cmath>

ErrorStateEKF::ErrorStateEKF() {
    nominal_state = Eigen::VectorXd::Zero(8);
    
    P = Eigen::MatrixXd::Identity(8, 8) * 0.1;
    P(0,0) = 10.0; P(1,1) = 10.0; // High initial GPS uncertainty
    P(2,2) = 3.1415926535897932;  // High initial heading uncertainty
    
    Q = Eigen::MatrixXd::Zero(8, 8);
    Q(3,3) = 0.5; Q(4,4) = 0.5;   // Velocity process noise
    Q(5,5) = 0.01; Q(6,6) = 0.01; // Accelerometer bias noise
    Q(7,7) = 0.001;               // Gyroscope bias noise
}

void ErrorStateEKF::predict(double ax, double ay, double gyro_z, double dt) {
    // 1. Extract states
    double heading = nominal_state(2);
    double vx = nominal_state(3);
    double vy = nominal_state(4);
    double b_ax = nominal_state(5);
    double b_ay = nominal_state(6);
    double b_g = nominal_state(7);

    // 2. Correct IMU measurements with tracked biases
    double ax_corr = ax - b_ax;
    double ay_corr = ay - b_ay;
    double gyro_corr = gyro_z - b_g;

    // 3. Predict new heading
    double new_heading = heading + gyro_corr * dt;
    
    // 4. Transform body-frame velocity to navigation-frame (Earth)
    double cos_h = std::cos(heading);
    double sin_h = std::sin(heading);
    
    double v_nav_x = vx * cos_h - vy * sin_h;
    double v_nav_y = vx * sin_h + vy * cos_h;
    
    // 5. Update Nominal State (Euler Integration)
    nominal_state(0) += v_nav_x * dt;
    nominal_state(1) += v_nav_y * dt;
    nominal_state(2) = new_heading;
    nominal_state(3) += ax_corr * dt;
    nominal_state(4) += ay_corr * dt;

    // 6. State Transition Matrix (F) for Error-State propagation
    Eigen::MatrixXd F = Eigen::MatrixXd::Identity(8, 8);
    F(0, 3) = cos_h * dt;
    F(0, 4) = -sin_h * dt;
    F(0, 2) = (-vx * sin_h - vy * cos_h) * dt;
    
    F(1, 3) = sin_h * dt;
    F(1, 4) = cos_h * dt;
    F(1, 2) = (vx * cos_h - vy * sin_h) * dt;
    
    F(2, 7) = -dt; // Heading error depends on gyro bias
    F(3, 5) = -dt; // Vel_x error depends on accel_x bias
    F(4, 6) = -dt; // Vel_y error depends on accel_y bias

    // 7. Covariance Update
    P = F * P * F.transpose() + Q;
}

void ErrorStateEKF::injectErrorState(const Eigen::VectorXd& error_state) {
    nominal_state += error_state;
    // Normalize heading between -pi and pi
    nominal_state(2) = std::atan2(std::sin(nominal_state(2)), std::cos(nominal_state(2)));
}

void ErrorStateEKF::updateVelocity(double v_tcn, double R_vel) {
    // The AI predicts Forward Velocity (vx_b)
    Eigen::MatrixXd H = Eigen::MatrixXd::Zero(1, 8);
    H(0, 3) = 1.0; 
    
    double predicted_v = nominal_state(3);
    double innovation = v_tcn - predicted_v;
    
    Eigen::MatrixXd R = Eigen::MatrixXd::Identity(1,1) * R_vel;
    Eigen::MatrixXd S = H * P * H.transpose() + R;
    Eigen::MatrixXd K = P * H.transpose() * S.inverse();
    
    Eigen::VectorXd error_state = K * innovation;
    injectErrorState(error_state);
    
    Eigen::MatrixXd I = Eigen::MatrixXd::Identity(8, 8);
    P = (I - K * H) * P;
}

void ErrorStateEKF::updateNHC(double R_nhc) {
    // Non-Holonomic Constraint: Cars don't slide sideways (vy_b ~ 0)
    Eigen::MatrixXd H = Eigen::MatrixXd::Zero(1, 8);
    H(0, 4) = 1.0;
    
    double predicted_vy = nominal_state(4);
    double innovation = 0.0 - predicted_vy;
    
    Eigen::MatrixXd R = Eigen::MatrixXd::Identity(1,1) * R_nhc;
    Eigen::MatrixXd S = H * P * H.transpose() + R;
    Eigen::MatrixXd K = P * H.transpose() * S.inverse();
    
    Eigen::VectorXd error_state = K * innovation;
    injectErrorState(error_state);
    
    Eigen::MatrixXd I = Eigen::MatrixXd::Identity(8, 8);
    P = (I - K * H) * P;
}

void ErrorStateEKF::updateGPS(double gps_x, double gps_y, double R_gps) {
    Eigen::MatrixXd H = Eigen::MatrixXd::Zero(2, 8);
    H(0, 0) = 1.0;
    H(1, 1) = 1.0;
    
    Eigen::VectorXd innovation(2);
    innovation(0) = gps_x - nominal_state(0);
    innovation(1) = gps_y - nominal_state(1);
    
    Eigen::MatrixXd R = Eigen::MatrixXd::Identity(2,2) * R_gps;
    Eigen::MatrixXd S = H * P * H.transpose() + R;
    Eigen::MatrixXd K = P * H.transpose() * S.inverse();
    
    Eigen::VectorXd error_state = K * innovation;
    injectErrorState(error_state);
    
    Eigen::MatrixXd I = Eigen::MatrixXd::Identity(8, 8);
    P = (I - K * H) * P;
}
