package com.example.idrnavigator.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.Collections

/**
 * On-device ONNX Runtime runner for TinyTCN velocity estimation model.
 *
 * Model specs:
 *  - File: tiny_tcn.onnx + tiny_tcn.onnx.data
 *  - Input node: "imu_vibration_input" [1, 11, 10]
 *  - Output node: "predicted_velocity" [1, 1] (in km/h)
 *  - Scaler: scaler_params.json (mean and scale vectors)
 */
class OnnxVelocityRunner(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "OnnxVelocityRunner"
        private const val MODEL_NAME = "tiny_tcn.onnx"
        private const val MODEL_DATA_NAME = "tiny_tcn.onnx.data"
        private const val SCALER_NAME = "scaler_params.json"

        const val INPUT_NODE_NAME = "imu_vibration_input"
        const val OUTPUT_NODE_NAME = "predicted_velocity"
        const val NUM_CHANNELS = 11
        const val WINDOW_LENGTH = 10
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    val mean = FloatArray(NUM_CHANNELS)
    val scale = FloatArray(NUM_CHANNELS)

    var isModelLoaded: Boolean = false
        private set

    /** Last measured inference latency in milliseconds */
    var lastInferenceLatencyMs: Long = 0L
        private set

    init {
        loadScalerParams()
        initOnnxSession()
    }

    private fun loadScalerParams() {
        try {
            val jsonString = context.assets.open(SCALER_NAME).bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)
            val meanArray = json.getJSONArray("mean")
            val scaleArray = json.getJSONArray("scale")

            for (i in 0 until NUM_CHANNELS) {
                mean[i] = meanArray.getDouble(i).toFloat()
                scale[i] = scaleArray.getDouble(i).toFloat()
            }
            Log.d(TAG, "Loaded scaler parameters for $NUM_CHANNELS channels")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load scaler parameters from assets", e)
        }
    }

    private fun initOnnxSession() {
        try {
            // ONNX models with external data (.onnx.data) require both files to reside in the same physical filesystem folder
            val modelsDir = File(context.filesDir, "onnx_models").apply { mkdirs() }
            val modelFile = File(modelsDir, MODEL_NAME)
            val dataFile = File(modelsDir, MODEL_DATA_NAME)

            copyAssetIfNeeded(MODEL_NAME, modelFile)
            copyAssetIfNeeded(MODEL_DATA_NAME, dataFile)

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            session = env.createSession(modelFile.absolutePath, sessionOptions)
            isModelLoaded = true
            Log.d(TAG, "ONNX TinyTCN session created successfully: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX Runtime session", e)
            isModelLoaded = false
        }
    }

    private fun copyAssetIfNeeded(assetName: String, destinationFile: File) {
        val assetFd = try { context.assets.openFd(assetName) } catch (_: Exception) { null }
        val expectedLength = assetFd?.length ?: -1L
        assetFd?.close()

        if (!destinationFile.exists() || (expectedLength > 0 && destinationFile.length() != expectedLength)) {
            Log.d(TAG, "Copying $assetName to ${destinationFile.absolutePath}...")
            context.assets.open(assetName).use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    /**
     * Run inference on a normalized flat tensor of shape [1, 11, 10] (110 floats).
     * Returns the predicted velocity in km/h.
     */
    fun predictVelocityKmH(normalizedFlatTensor: FloatArray): Float {
        val activeSession = session ?: return 0f
        if (normalizedFlatTensor.size != NUM_CHANNELS * WINDOW_LENGTH) {
            Log.e(TAG, "Invalid tensor size: ${normalizedFlatTensor.size}, expected ${NUM_CHANNELS * WINDOW_LENGTH}")
            return 0f
        }

        val startTime = System.nanoTime()
        val tensorShape = longArrayOf(1L, NUM_CHANNELS.toLong(), WINDOW_LENGTH.toLong())
        val inputBuffer = FloatBuffer.wrap(normalizedFlatTensor)

        return try {
            val inputTensor = OnnxTensor.createTensor(env, inputBuffer, tensorShape)
            inputTensor.use { tensor ->
                val results = activeSession.run(Collections.singletonMap(INPUT_NODE_NAME, tensor))
                results.use { outputMap ->
                    val outputTensor = outputMap.get(0) as OnnxTensor
                    val rawPredictedKmH = outputTensor.floatBuffer.get(0)

                    lastInferenceLatencyMs = (System.nanoTime() - startTime) / 1_000_000L

                    // Clamp negative predictions (vehicles don't move backward in highway model)
                    if (rawPredictedKmH < 0f) 0f else rawPredictedKmH
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference execution error", e)
            0f
        }
    }

    override fun close() {
        try {
            session?.close()
            session = null
            env.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ONNX resources", e)
        }
    }
}
