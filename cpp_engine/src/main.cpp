#include <iostream>
#include "ekf.h"

int main() {
    std::cout << "Starting IDR Core Engine (C++)..." << std::endl;
    
    // Initialize the filter
    ErrorStateEKF ekf;
    
    std::cout << "EKF Initialized." << std::endl;
    std::cout << "Initial Position: (" << ekf.getX() << ", " << ekf.getY() << ")" << std::endl;

    // TODO: Load S-M.csv or bridge JNI inputs here
    // TODO: Load TFLite Model here
    
    return 0;
}
