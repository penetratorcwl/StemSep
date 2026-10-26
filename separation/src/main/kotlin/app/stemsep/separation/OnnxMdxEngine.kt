package app.stemsep.separation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.util.Log
import app.stemsep.dsp.MdxConfig
import app.stemsep.dsp.MdxInference
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.util.EnumSet

class OnnxMdxEngine(
    modelFile: File,
    threads: Int = 4,
    requestedBackend: ComputeBackend = ComputeBackend.CPU,
) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val outputName: String
    val activeBackend: ComputeBackend

    init {
        var effectiveBackend = requestedBackend
        session = try {
            createSession(modelFile, requestedBackend, threads)
        } catch (error: Exception) {
            if (requestedBackend == ComputeBackend.CPU) throw error
            Log.w(TAG, "Falling back to CPU after ${requestedBackend.cacheId} failed", error)
            effectiveBackend = ComputeBackend.CPU
            createSession(modelFile, ComputeBackend.CPU, threads)
        }
        activeBackend = effectiveBackend
        inputName = session.inputNames.first()
        outputName = session.outputNames.first()
    }

    fun inference(config: MdxConfig): MdxInference = MdxInference { input, output ->
        val shape = longArrayOf(
            1L,
            4L,
            config.frequencyBins.toLong(),
            config.timeFrames.toLong(),
        )
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val value = result.get(outputName).orElseGet {
                    result.get(0)
                }
                val outputTensor = value as? OnnxTensor
                    ?: error("Model output is not a float tensor")
                val buffer = outputTensor.floatBuffer
                require(buffer.remaining() >= output.size) {
                    "Model output is smaller than expected"
                }
                buffer.get(output)
            }
        }
    }

    override fun close() {
        session.close()
    }

    private fun createSession(
        modelFile: File,
        backend: ComputeBackend,
        threads: Int,
    ): OrtSession {
        val options = OrtSession.SessionOptions()
        try {
            options.setInterOpNumThreads(1)
            options.setIntraOpNumThreads(
                if (backend == ComputeBackend.CPU) threads.coerceAtLeast(1) else 1,
            )
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (backend == ComputeBackend.SNAPDRAGON_NNAPI) {
                options.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
            }
            return environment.createSession(modelFile.absolutePath, options)
        } finally {
            options.close()
        }
    }

    private companion object {
        const val TAG = "StemSepOnnx"
    }
}
