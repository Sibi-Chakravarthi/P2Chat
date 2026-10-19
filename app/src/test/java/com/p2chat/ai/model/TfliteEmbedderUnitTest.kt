package com.p2chat.ai.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class TfliteEmbedderUnitTest {

    @Test
    fun testMissingAssetFileProducesTypedModelUnavailable() {
        val nonExistentFile = File("non_existent_model_dir/missing.tflite")
        val embedder = TfliteEmbedder.fromFile(nonExistentFile)

        assertFalse("Embedder must not be available when file is missing", embedder.isAvailable)

        val dummy = IntArray(128)
        try {
            embedder.embedBlocking(dummy, dummy)
            fail("Expected ModelUnavailable to be thrown")
        } catch (e: ModelUnavailable) {
            assertTrue("Expected AssetNotFound or InitializationFailed",
                e is ModelUnavailable.AssetNotFound || e is ModelUnavailable.InitializationFailed)
        }
    }

    @Test
    fun testClosedEmbedderRejectsInference() {
        val nonExistentFile = File("non_existent_model_dir/missing.tflite")
        val embedder = TfliteEmbedder.fromFile(nonExistentFile)

        embedder.close()
        assertFalse(embedder.isAvailable)

        try {
            embedder.embedBlocking(IntArray(128), IntArray(128))
            fail("Expected ModelUnavailable.Closed")
        } catch (e: ModelUnavailable) {
            assertTrue(e is ModelUnavailable.Closed)
        }
    }

    @Test
    fun testEmptyBufferInitializationBehavior() {
        // Supplying empty ByteBuffer without TFLite native runtime
        val emptyBuf = ByteBuffer.allocateDirect(100)
        val embedder = TfliteEmbedder.fromBuffer(emptyBuf)

        // On desktop JVM, either native library is missing or format is invalid.
        // In either case, embedder must fail gracefully with ModelUnavailable.
        assertFalse(embedder.isAvailable)
        try {
            embedder.embedBlocking(IntArray(128), IntArray(128))
            fail("Expected ModelUnavailable on invalid buffer")
        } catch (e: ModelUnavailable) {
            assertTrue(e is ModelUnavailable.InitializationFailed)
        }
    }
}
