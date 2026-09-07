#ifndef EKF_H
#define EKF_H

#include <Eigen/Dense>

class ErrorStateEKF {
public:
    ErrorStateEKF();
    
    // Core Prediction Step using raw IMU data
    // dt: Time delta since last update (usually 0.1s for 10Hz)
    void predict(double ax, double ay, double gyro_z, double dt);
    
    // Measurement Update 1: TCN Velocity Aiding
    void updateVelocity(double v_tcn, double R_vel);
    
    // Measurement Update 2: Non-Holonomic Constraints (Vehicle can't slide sideways)
    void updateNHC(double R_nhc);
    
    // Measurement Update 3: GPS (when GNSS is not denied)
    void updateGPS(double gps_x, double gps_y, double R_gps);

    // Getters for the Mobile UI to consume
    double getX() const { return nominal_state(0); }
    double getY() const { return nominal_state(1); }
    double getHeading() const { return nominal_state(2); }

private:
    // 8-Dimensional Nominal State Vector:
    // [0] x (East)
    // [1] y (North)
    // [2] heading (Yaw)
    // [3] vx_b (Forward Velocity in Body Frame)
    // [4] vy_b (Lateral Velocity in Body Frame)
    // [5] b_ax (Accel X Bias)
    // [6] b_ay (Accel Y Bias)
    // [7] b_g  (Gyro Z Bias)
    Eigen::VectorXd nominal_state;
    
    // 8x8 Error-State Covariance Matrix
    Eigen::MatrixXd P;
    
    // 8x8 Process Noise Covariance Matrix
    Eigen::MatrixXd Q;
    
    // Helper function to inject the calculated error state back into the nominal state
    void injectErrorState(const Eigen::VectorXd& error_state);
};

#endif // EKF_H
