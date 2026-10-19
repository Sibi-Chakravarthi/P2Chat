package com.p2chat.ai.score

import android.content.Context
import org.json.JSONArray
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * In-memory repository of bundled toxic centroids (T3.9, AI-14, AI-15).
 * Stores centroids in a contiguous FloatArray for cache-friendly dot-product scanning.
 */
class VectorStore(
    val flatCentroids: FloatArray,
    val categories: List<String>
) {
    val vectorCount: Int = categories.size

    init {
        require(flatCentroids.size == vectorCount * EMBEDDING_DIM) {
            "Centroid array size (${flatCentroids.size}) must equal vectorCount ($vectorCount) * $EMBEDDING_DIM"
        }
    }

    fun getVector(index: Int): FloatArray {
        require(index in 0 until vectorCount) { "Index $index out of bounds [0, $vectorCount)" }
        val offset = index * EMBEDDING_DIM
        return flatCentroids.copyOfRange(offset, offset + EMBEDDING_DIM)
    }

    companion object {
        const val EMBEDDING_DIM = 384
        const val BYTES_PER_VECTOR = EMBEDDING_DIM * 4 // 1,536 bytes
        const val DEFAULT_BIN_ASSET = "toxic_vectors.bin"
        const val DEFAULT_META_ASSET = "toxic_vectors_meta.json"

        /**
         * Loads vector store from Android assets (T3.9, AI-14).
         */
        fun fromAssets(
            context: Context,
            binAssetName: String = DEFAULT_BIN_ASSET,
            metaAssetName: String = DEFAULT_META_ASSET
        ): VectorStore {
            val binStream = context.assets.open(binAssetName)
            val metaStream = context.assets.open(metaAssetName)
            return fromStreams(binStream, metaStream)
        }

        /**
         * Loads vector store from input streams.
         */
        fun fromStreams(binStream: InputStream, metaStream: InputStream): VectorStore {
            val binBytes = binStream.use { it.readBytes() }
            val metaJson = metaStream.use { InputStreamReader(it, Charsets.UTF_8).readText() }
            return fromBytes(binBytes, metaJson)
        }

        /**
         * Loads vector store from binary byte array and metadata JSON string.
         * Enforces that binary size is a multiple of 1,536 bytes (AI-14).
         */
        fun fromBytes(binBytes: ByteArray, metaJson: String): VectorStore {
            if (binBytes.isEmpty()) {
                throw IllegalArgumentException("Vector binary data cannot be empty")
            }
            if (binBytes.size % BYTES_PER_VECTOR != 0) {
                throw IllegalArgumentException(
                    "Invalid vector binary length: ${binBytes.size} bytes. " +
                            "Must be an exact multiple of $BYTES_PER_VECTOR bytes (AI-14)."
                )
            }

            val vectorCount = binBytes.size / BYTES_PER_VECTOR
            val jsonArray = JSONArray(metaJson)
            if (jsonArray.length() != vectorCount) {
                throw IllegalArgumentException(
                    "Sidecar metadata length (${jsonArray.length()}) does not match " +
                            "vector count ($vectorCount) from binary asset."
                )
            }

            val categories = ArrayList<String>(vectorCount)
            for (i in 0 until vectorCount) {
                categories.add(jsonArray.getString(i))
            }

            val flatArray = FloatArray(vectorCount * EMBEDDING_DIM)
            ByteBuffer.wrap(binBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
                .get(flatArray)

            return VectorStore(flatArray, categories)
        }
    }
}
