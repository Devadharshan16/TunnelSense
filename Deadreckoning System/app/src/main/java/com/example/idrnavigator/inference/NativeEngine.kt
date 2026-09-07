package com.example.idrnavigator.inference

object NativeEngine {
    init {
        try {
            System.loadLibrary("idr_jni")
        } catch (t: Throwable) {
            android.util.Log.e("NativeEngine", "Failed to load C++ JNI library: ${t.message}")
        }
    }

    /**
     * Initializes the C++ Error State Kalman Filter (InEKF).
     */
    external fun initCoreEngine()

    /**
     * Pushes a single raw IMU sample to the C++ EKF predict step.
     * This should be called strictly at 200Hz.
     */
    external fun pushImuSample(accX: Float, accY: Float, gyroZ: Float, dt: Float)

    /**
     * Injects the AI's predicted speed and Aleatoric Variance directly into the C++ EKF's Measurement Noise Matrix (R).
     */
    external fun injectAiVariance(predictedSpeedKmH: Float, variance: Float)

    external fun injectMagHeading(measuredHeadingRad: Float, variance: Float)

    /**
     * Pulls the filtered X, Y, and Heading from C++ back to Kotlin for UI rendering.
     * Returns: [x, y, heading]
     */
    external fun getFilterState(): FloatArray
}


