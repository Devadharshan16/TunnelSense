#include <jni.h>
#include "ekf.h"
#include "preprocessor.h"

// Global instances for the Android lifecycle
static ErrorStateEKF* g_ekf = nullptr;

extern "C"
JNIEXPORT void JNICALL
Java_com_example_idrnavigator_inference_NativeEngine_initCoreEngine(JNIEnv *env, jobject thiz) {
    if (!g_ekf) {
        g_ekf = new ErrorStateEKF();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_idrnavigator_inference_NativeEngine_pushImuSample(JNIEnv *env, jobject thiz, jfloat acc_x, jfloat acc_y, jfloat gyro_z, jfloat dt) {
    // This is called at exactly 200Hz by the Android OS SensorEventListener
    if (g_ekf) {
        g_ekf->predict(acc_x, acc_y, gyro_z, dt);
    }
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_example_idrnavigator_inference_NativeEngine_getFilterState(JNIEnv *env, jobject thiz) {
    // Return the current (X, Y, Heading) to the Android UI
    jfloatArray result = env->NewFloatArray(3);
    if (g_ekf) {
        jfloat state[3] = {
            static_cast<jfloat>(g_ekf->getX()),
            static_cast<jfloat>(g_ekf->getY()),
            static_cast<jfloat>(g_ekf->getHeading())
        };
        env->SetFloatArrayRegion(result, 0, 3, state);
    }
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_idrnavigator_inference_NativeEngine_injectAiVariance(JNIEnv *env, jobject thiz, jfloat predicted_speed, jfloat variance) {
    // Inject the Aleatoric Uncertainty dynamically!
    if (g_ekf) {
        g_ekf->updateVelocity(predicted_speed, variance);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_idrnavigator_inference_NativeEngine_injectMagHeading(JNIEnv *env, jobject thiz, jfloat measured_heading, jfloat variance) {
    if (g_ekf) {
        g_ekf->updateHeading(measured_heading, variance);
    }
}
