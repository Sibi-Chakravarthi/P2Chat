package com.p2chat.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.p2chat.ai.model.ModelUnavailable
import com.p2chat.ai.model.TfliteEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStreamReader

@RunWith(AndroidJUnit4::class)
class TfliteEmbedderDeviceTest {

    private lateinit var context: Context
    private lateinit var embedder: TfliteEmbedder

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        embedder = TfliteEmbedder.fromAsset(context)
    }

    @After
    fun teardown() {
        embedder.close()
    }

    private fun loadFixturesJson(): JSONObject {
        // Try application assets first, then instrumentation test assets
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val stream = try {
            context.assets.open("embedder_fixtures.json")
        } catch (_: Exception) {
            testContext.assets.open("embedder_fixtures.json")
        }
        val text = InputStreamReader(stream, Charsets.UTF_8).readText()
        return JSONObject(text)
    }

    @Test
    fun testEmbedderInitializationAndCosineSimilarityOn20Fixtures() = runBlocking {
        assertTrue("Embedder must be available after loading asset", embedder.isAvailable)

        val root = loadFixturesJson()
        val fixtures = root.getJSONArray("fixtures")
        assertTrue("Must have at least 20 fixtures", fixtures.length() >= 20)

        val count = minOf(fixtures.length(), 20)
        val latencies = LongArray(count)

        for (i in 0 until count) {
            val f = fixtures.getJSONObject(i)
            val fixtureId = f.getInt("id")
            val rawInput = f.getString("input")

            val idsArr = f.getJSONArray("token_ids")
            val inputIds = IntArray(idsArr.length()) { idsArr.getInt(it) }

            val maskArr = f.getJSONArray("attention_mask")
            val mask = IntArray(maskArr.length()) { maskArr.getInt(it) }

            val expArr = f.getJSONArray("embedding")
            val expected = FloatArray(expArr.length()) { expArr.getDouble(it).toFloat() }

            val actual = embedder.embed(inputIds, mask)
            assertEquals("Embedding must be 384 dimensions", 384, actual.size)

            latencies[i] = embedder.lastInferenceLatencyMs

            // Compute cosine similarity: dot product since both are L2 normalized
            var dot = 0.0
            var normActual = 0.0
            for (d in 0 until 384) {
                dot += (actual[d] * expected[d])
                normActual += (actual[d] * actual[d])
            }

            // Norm should be 1.0 (unless degenerate zero vector)
            if (f.getDouble("norm") > 1e-6) {
                assertEquals("Actual vector must be L2 unit norm", 1.0, kotlin.math.sqrt(normActual), 1e-4)
                assertTrue(
                    "Cosine similarity for fixture #$fixtureId ('$rawInput') was $dot, expected >= 0.999",
                    dot >= 0.999
                )
            }
        }
    }

    @Test
    fun testConcurrentInferenceSafetyAcrossFourCoroutines() = runBlocking {
        assertTrue(embedder.isAvailable)

        val root = loadFixturesJson()
        val fixtures = root.getJSONArray("fixtures")

        // Launch 4 concurrent coroutines with different fixtures
        val deferreds = (0 until 4).map { idx ->
            async(Dispatchers.Default) {
                val f = fixtures.getJSONObject(idx)
                val idsArr = f.getJSONArray("token_ids")
                val inputIds = IntArray(idsArr.length()) { idsArr.getInt(it) }
                val maskArr = f.getJSONArray("attention_mask")
                val mask = IntArray(maskArr.length()) { maskArr.getInt(it) }
                val expArr = f.getJSONArray("embedding")
                val expected = FloatArray(expArr.length()) { expArr.getDouble(it).toFloat() }

                val actual = embedder.embed(inputIds, mask)

                var dot = 0.0
                for (d in 0 until 384) {
                    dot += (actual[d] * expected[d])
                }
                dot
            }
        }

        val results = deferreds.awaitAll()
        for (i in 0 until 4) {
            assertTrue("Concurrent coroutine $i output must match expected with cosine >= 0.999 (was ${results[i]})", results[i] >= 0.999)
        }
    }

    @Test
    fun testCloseAndReloadLifecycle() = runBlocking {
        assertTrue(embedder.isAvailable)

        val dummy = IntArray(128)
        val vec1 = embedder.embed(dummy, dummy)
        assertEquals(384, vec1.size)

        embedder.close()
        assertFalse("Embedder must not be available after close", embedder.isAvailable)

        try {
            embedder.embed(dummy, dummy)
            fail("Expected ModelUnavailable.Closed")
        } catch (e: Exception) {
            assertTrue("Expected ModelUnavailable.Closed, got $e", e is ModelUnavailable.Closed)
        }

        embedder.reload()
        assertTrue("Embedder must be available after reload", embedder.isAvailable)

        val vec2 = embedder.embed(dummy, dummy)
        assertEquals(384, vec2.size)
    }
}
