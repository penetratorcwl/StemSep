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

data class InferenceStats(
    val chunks: Int,
    val averageMillis: Long,
)

class OnnxMdxEngine(
    modelFile: File,
    threads: Int = 4,
    requestedBackend: ComputeBackend = ComputeBackend.CPU,
    val acceleratorMode: AcceleratorMode = AcceleratorMode.BALANCED,
) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val outputName: String
    private val requestedThreads = threads.coerceAtLeast(1)
    private val statsLock = Any()
    private var inferenceChunks = 0
    private var inferenceNanos = 0L
    val activeBackend: ComputeBackend
    val effectiveThreads: Int
    val fallbackReason: String?

    init {
        var effectiveBackend = requestedBackend
        var reason: String? = null
        session = try {
            createSession(modelFile, requestedBackend, requestedThreads)
        } catch (error: Exception) {
            if (
                requestedBackend == ComputeBackend.CPU ||
                acceleratorMode == AcceleratorMode.ACCELERATOR_ONLY
            ) {
                throw error
            }
            Log.w(TAG, "Falling back to CPU after ${requestedBackend.cacheId} failed", error)
            effectiveBackend = ComputeBackend.CPU
            reason = error.message?.takeIf { it.isNotBlank() }
                ?: error::class.java.simpleName
            createSession(modelFile, ComputeBackend.CPU, requestedThreads)
        }
        activeBackend = effectiveBackend
        effectiveThreads = if (effectiveBackend == ComputeBackend.CPU) {
            requestedThreads
        } else {
            1
        }
        fallbackReason = reason
        inputName = session.inputNames.first()
        outputName = session.outputNames.first()
    }

    fun stats(): InferenceStats = synchronized(statsLock) {
        InferenceStats(
            chunks = inferenceChunks,
            averageMillis = if (inferenceChunks == 0) {
                0
            } else {
                inferenceNanos / inferenceChunks / 1_000_000
            },
        )
    }

    fun inference(config: MdxConfig): MdxInference = MdxInference { input, output ->
        val shape = longArrayOf(
            1L,
            4L,
            config.frequencyBins.toLong(),
            config.timeFrames.toLong(),
        )
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            val started = System.nanoTime()
            val result = session.run(mapOf(inputName to tensor))
            val elapsed = System.nanoTime() - started
            synchronized(statsLock) {
                inferenceChunks++
                inferenceNanos += elapsed
            }
            result.use {
                val value = it.get(outputName).orElseGet {
                    it.get(0)
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
                val flags = EnumSet.of(NNAPIFlags.USE_FP16)
                if (acceleratorMode == AcceleratorMode.ACCELERATOR_ONLY) {
                    flags.add(NNAPIFlags.CPU_DISABLED)
                }
                options.addNnapi(flags)
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
