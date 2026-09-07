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
        private const val MODEL_NAME = "tiny_tcn_fp32.onnx"
        private const val MODEL_DATA_NAME = "tiny_tcn_fp32.onnx.data"
        private const val SCALER_NAME = "scaler_params.json"

        const val INPUT_NODE_NAME = "input"
        const val OUTPUT_NODE_NAME = "mu"
        const val VARIANCE_NODE_NAME = "log_var"
        
        const val NUM_CHANNELS = 12
        const val WINDOW_LENGTH = 200
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
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
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

    private val tensorShape = longArrayOf(1L, NUM_CHANNELS.toLong(), WINDOW_LENGTH.toLong())
    private val inputBuffer = FloatBuffer.allocate(NUM_CHANNELS * WINDOW_LENGTH)

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
     * Run inference on a normalized flat tensor of shape [1, 12, 200] (2400 floats).
     * Returns a Pair: (Predicted Velocity in km/h, Aleatoric Variance)
     */
    fun predictVelocityKmH(normalizedFlatTensor: FloatArray): Pair<Float, Float> {
        val activeSession = session ?: return Pair(0f, 0f)
        if (normalizedFlatTensor.size != NUM_CHANNELS * WINDOW_LENGTH) {
            Log.e(TAG, "Invalid tensor size: ${normalizedFlatTensor.size}, expected ${NUM_CHANNELS * WINDOW_LENGTH}")
            return Pair(0f, 0f)
        }

        val startTime = System.nanoTime()
        inputBuffer.clear()
        inputBuffer.put(normalizedFlatTensor)
        inputBuffer.flip()

        return try {
            val inputTensor = OnnxTensor.createTensor(env, inputBuffer, tensorShape)
            inputTensor.use { tensor ->
                val results = activeSession.run(Collections.singletonMap(INPUT_NODE_NAME, tensor))
                results.use { outputMap ->
                    // Extract Mu (Velocity)
                    val muTensor = outputMap.get(OUTPUT_NODE_NAME).get() as OnnxTensor
                    val rawPredictedKmH = muTensor.floatBuffer.get(0)
                    
                    // Extract Log-Variance (Uncertainty)
                    val varTensor = outputMap.get(VARIANCE_NODE_NAME).get() as OnnxTensor
                    val logVariance = varTensor.floatBuffer.get(0)
                    
                    val actualVariance = kotlin.math.exp(logVariance.toDouble()).toFloat()

                    val elapsedNanos = System.nanoTime() - startTime
                    lastInferenceLatencyMs = kotlin.math.max(1L, (elapsedNanos + 500_000L) / 1_000_000L)

                    val clampedPrediction = if (rawPredictedKmH < 0f) 0f else rawPredictedKmH
                    
                    Log.d(
                        TAG,
                        "ONNX TCN inference: speed=${"%.4f".format(clampedPrediction)} km/h, " +
                        "variance=${"%.4f".format(actualVariance)}, latency=${lastInferenceLatencyMs} ms"
                    )

                    Pair(clampedPrediction, actualVariance)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference execution error", e)
            Pair(0f, 0f)
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
