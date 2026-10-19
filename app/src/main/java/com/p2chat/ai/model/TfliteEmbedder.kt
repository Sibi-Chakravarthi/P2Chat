package com.p2chat.ai.model

import android.content.Context
import com.p2chat.util.SecureLog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Typed exceptions indicating that the semantic filter model is unavailable (AI-13).
 */
sealed class ModelUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AssetNotFound(message: String, cause: Throwable? = null) : ModelUnavailable(message, cause)
    class InitializationFailed(message: String, cause: Throwable? = null) : ModelUnavailable(message, cause)
    class InferenceFailed(message: String, cause: Throwable? = null) : ModelUnavailable(message, cause)
    class Closed(message: String = "TfliteEmbedder is closed") : ModelUnavailable(message)
}

/**
 * Common contract for sentence embedders (T3.8).
 */
interface SentenceEmbedder : AutoCloseable {
    val isAvailable: Boolean
    val lastInferenceLatencyMs: Long
    suspend fun embed(inputIds: IntArray, attentionMask: IntArray): FloatArray
    fun reload()
}

/**
 * On-device semantic sentence embedder using quantized all-MiniLM-L6-v2 TFLite model (T3.8, AI-4).
 *
 * Guarantees:
 * - Thread-safe inference guarded by [Mutex] (TFLite Interpreter is not thread-safe).
 * - Mean pooling over attention mask and L2 normalization to unit length (AI-5).
 * - Maps any initialization, asset, or inference failure to [ModelUnavailable] (AI-13).
 * - Supports dynamic reload and close.
 */
class TfliteEmbedder(
    private val bufferProvider: () -> ByteBuffer
) : SentenceEmbedder {

    companion object {
        const val DEFAULT_MODEL_ASSET = "all-MiniLM-L6-v2-quant.tflite"
        const val EMBEDDING_DIM = 384
        const val MAX_SEQUENCE_LENGTH = 128
        private const val TAG = "TfliteEmbedder"

        fun fromAsset(context: Context, assetName: String = DEFAULT_MODEL_ASSET): TfliteEmbedder {
            return TfliteEmbedder { loadAssetBuffer(context, assetName) }
        }

        fun fromFile(file: File): TfliteEmbedder {
            return TfliteEmbedder { loadFileBuffer(file) }
        }

        fun fromBuffer(buffer: ByteBuffer): TfliteEmbedder {
            return TfliteEmbedder { buffer }
        }

        private fun loadAssetBuffer(context: Context, assetName: String): MappedByteBuffer {
            try {
                val fileDescriptor = context.assets.openFd(assetName)
                FileInputStream(fileDescriptor.fileDescriptor).use { stream ->
                    val channel = stream.channel
                    val startOffset = fileDescriptor.startOffset
                    val declaredLength = fileDescriptor.declaredLength
                    return channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
                }
            } catch (e: Exception) {
                throw ModelUnavailable.AssetNotFound("Failed to map asset '$assetName': ${e.message}", e)
            }
        }

        private fun loadFileBuffer(file: File): MappedByteBuffer {
            if (!file.isFile) {
                throw ModelUnavailable.AssetNotFound("Model file '${file.path}' does not exist")
            }
            try {
                FileInputStream(file).use { stream ->
                    val channel = stream.channel
                    return channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                }
            } catch (e: Exception) {
                throw ModelUnavailable.AssetNotFound("Failed to map model file '${file.path}': ${e.message}", e)
            }
        }
    }

    private val mutex = Mutex()

    @Volatile
    private var interpreter: Interpreter? = null

    @Volatile
    private var isClosed: Boolean = false

    @Volatile
    private var initFailure: ModelUnavailable? = null

    @Volatile
    private var _lastInferenceLatencyMs: Long = 0L

    init {
        try {
            initializeInterpreter()
        } catch (e: ModelUnavailable) {
            initFailure = e
            SecureLog.w(TAG, "Model unavailable at initialization: ${e.message}")
        } catch (e: Throwable) {
            val failure = ModelUnavailable.InitializationFailed("Unexpected initialization error: ${e.message}", e)
            initFailure = failure
            SecureLog.w(TAG, "Initialization failed: ${e.message}")
        }
    }

    override val isAvailable: Boolean
        get() = interpreter != null && !isClosed && initFailure == null

    override val lastInferenceLatencyMs: Long
        get() = _lastInferenceLatencyMs

    private fun initializeInterpreter() {
        val buffer = bufferProvider()
        val options = Interpreter.Options().apply {
            setNumThreads(2)
        }
        val interp = try {
            Interpreter(buffer, options)
        } catch (e: UnsatisfiedLinkError) {
            throw ModelUnavailable.InitializationFailed("TFLite native runtime unavailable: ${e.message}", e)
        } catch (e: Throwable) {
            throw ModelUnavailable.InitializationFailed("Failed to create Interpreter: ${e.message}", e)
        }
        interp.allocateTensors()
        interpreter = interp
    }

    /**
     * Embeds the tokenized sequence into a 384-dimensional L2-normalized vector (AI-4, AI-5).
     * Guarded by [Mutex] for thread safety.
     */
    override suspend fun embed(inputIds: IntArray, attentionMask: IntArray): FloatArray = mutex.withLock {
        if (isClosed) {
            throw ModelUnavailable.Closed()
        }
        initFailure?.let { throw it }
        val interp = interpreter
            ?: throw ModelUnavailable.InitializationFailed("Interpreter is null")

        val t0 = System.nanoTime()
        try {
            val paddedIds = Array(1) { IntArray(MAX_SEQUENCE_LENGTH) }
            val paddedMask = Array(1) { IntArray(MAX_SEQUENCE_LENGTH) }
            System.arraycopy(inputIds, 0, paddedIds[0], 0, minOf(inputIds.size, MAX_SEQUENCE_LENGTH))
            System.arraycopy(attentionMask, 0, paddedMask[0], 0, minOf(attentionMask.size, MAX_SEQUENCE_LENGTH))

            val numInputs = interp.inputTensorCount
            val inputs = arrayOfNulls<Any>(numInputs)
            for (i in 0 until numInputs) {
                val name = interp.getInputTensor(i).name().lowercase()
                if (name.contains("mask")) {
                    inputs[i] = paddedMask
                } else if (name.contains("input_ids") || name.contains("ids")) {
                    inputs[i] = paddedIds
                } else {
                    inputs[i] = if (i == 0) paddedMask else paddedIds
                }
            }

            val outTensor = interp.getOutputTensor(0)
            val outShape = outTensor.shape()

            val outputs = HashMap<Int, Any>()
            val rawVector: FloatArray

            if (outShape.size == 2 && outShape[1] == EMBEDDING_DIM) {
                val outBuf = Array(1) { FloatArray(EMBEDDING_DIM) }
                outputs[0] = outBuf
                interp.runForMultipleInputsOutputs(inputs, outputs)
                rawVector = outBuf[0]
            } else if (outShape.size == 3 && outShape[2] == EMBEDDING_DIM) {
                val seqLen = outShape[1]
                val outTokens = Array(1) { Array(seqLen) { FloatArray(EMBEDDING_DIM) } }
                outputs[0] = outTokens
                interp.runForMultipleInputsOutputs(inputs, outputs)

                val pooled = FloatArray(EMBEDDING_DIM)
                var maskCount = 0f
                for (t in 0 until minOf(seqLen, MAX_SEQUENCE_LENGTH)) {
                    val m = paddedMask[0][t]
                    if (m > 0) {
                        maskCount += 1f
                        for (d in 0 until EMBEDDING_DIM) {
                            pooled[d] += outTokens[0][t][d]
                        }
                    }
                }
                if (maskCount > 0f) {
                    for (d in 0 until EMBEDDING_DIM) {
                        pooled[d] /= maskCount
                    }
                }
                rawVector = pooled
            } else {
                throw ModelUnavailable.InferenceFailed("Unexpected output shape: ${outShape.contentToString()}")
            }

            // AI-5: L2 normalize
            val norm = sqrt(rawVector.fold(0.0) { acc, v -> acc + (v * v) }).toFloat()
            val result = if (norm > 1e-9f) {
                FloatArray(EMBEDDING_DIM) { rawVector[it] / norm }
            } else {
                FloatArray(EMBEDDING_DIM) // degenerate zero vector
            }

            _lastInferenceLatencyMs = (System.nanoTime() - t0) / 1_000_000L
            return result
        } catch (e: ModelUnavailable) {
            throw e
        } catch (e: Throwable) {
            throw ModelUnavailable.InferenceFailed("Inference execution failed: ${e.message}", e)
        }
    }

    fun embedBlocking(inputIds: IntArray, attentionMask: IntArray): FloatArray = runBlocking {
        embed(inputIds, attentionMask)
    }

    override fun reload() {
        close()
        isClosed = false
        initFailure = null
        try {
            initializeInterpreter()
        } catch (e: ModelUnavailable) {
            initFailure = e
            throw e
        } catch (e: Throwable) {
            val failure = ModelUnavailable.InitializationFailed("Reload failed: ${e.message}", e)
            initFailure = failure
            throw failure
        }
    }

    override fun close() {
        isClosed = true
        try {
            interpreter?.close()
        } catch (_: Throwable) {
        } finally {
            interpreter = null
        }
    }
}
