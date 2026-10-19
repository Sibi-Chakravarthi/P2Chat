package com.p2chat.ai.text

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader

/**
 * Unit tests for TextNormalizer parity against Python fixtures (T3.6, AI-2).
 */
class TextNormalizerTest {

    @Test
    fun testUnchangedStrings() {
        val unchanged = listOf(
            "12:00",
            "1 km",
            "stop!",
            "@name",
            "room 101",
            "$500",
            "10:30 am",
            "0 degrees",
            "step 1",
            "call 911",
            "level 0",
            "25% discount!"
        )

        for (str in unchanged) {
            assertEquals("Expected '$str' to remain unchanged", str, TextNormalizer.normalize(str))
        }
    }

    @Test
    fun testRepeatedCharacterCollapse() {
        assertEquals("heelp", TextNormalizer.normalize("heeeelp"))
        assertEquals("heelp", TextNormalizer.normalize("heeeeeeeelp"))
        assertEquals("good", TextNormalizer.normalize("good"))
        assertEquals("see", TextNormalizer.normalize("see"))
        assertEquals("soo", TextNormalizer.normalize("soooo"))
    }

    @Test
    fun testLeetspeakSubstitution() {
        assertEquals("bad boy", TextNormalizer.normalize("b@d boy"))
        assertEquals("fine day", TextNormalizer.normalize("f!ne day"))
        assertEquals("time is up", TextNormalizer.normalize("t1me is up"))
        assertEquals("darkness", TextNormalizer.normalize("d@rkness"))
        assertEquals("cold night", TextNormalizer.normalize("c0ld night"))
        assertEquals("c00l music", TextNormalizer.normalize("c00l music")) // 00 not bounded by letters on both sides
    }

    @Test
    fun testZeroWidthStripping() {
        assertEquals("toxic message", TextNormalizer.normalize("to\u200Bxi\u200Bc message"))
        assertEquals("secret alert", TextNormalizer.normalize("se\u200Ccr\u200Det alert"))
        assertEquals("welcome", TextNormalizer.normalize("\uFEFFwelcome"))
    }

    @Test
    fun testParityWithPythonTokenizerFixtures() {
        val stream = javaClass.classLoader!!.getResourceAsStream("ai/tokenizer_fixtures.json")
            ?: throw IllegalStateException("Resource ai/tokenizer_fixtures.json not found")

        val jsonText = InputStreamReader(stream, Charsets.UTF_8).readText()
        val root = JSONObject(jsonText)
        val fixtures = root.getJSONArray("fixtures")

        assertTrue("Expected at least 100 fixtures", fixtures.length() >= 100)

        for (i in 0 until fixtures.length()) {
            val f = fixtures.getJSONObject(i)
            val rawInput = f.getString("input")
            val expectedNormalized = f.getString("normalized")
            val actualNormalized = TextNormalizer.normalize(rawInput)

            assertEquals(
                "Fixture #${f.getInt("id")} mismatch for input '$rawInput'",
                expectedNormalized,
                actualNormalized
            )
        }
    }

    @Test
    fun testPerformance_under1msPer350ByteMessage() {
        val sample350Bytes = "This is a typical 350-byte encrypted chat message sent over BLE mesh with some leetspeak b@d f!ne " +
                "and repeated characteeeeeers plus numbers 12:00 and 1 km to ensure the regular expression normalizer " +
                "executes extremely fast on mobile CPU threads without causing frame drop or delay in pipeline."

        // Warm up
        repeat(100) {
            TextNormalizer.normalize(sample350Bytes)
        }

        val iterations = 1000
        val t0 = System.nanoTime()
        repeat(iterations) {
            TextNormalizer.normalize(sample350Bytes)
        }
        val elapsedNs = System.nanoTime() - t0
        val avgMs = (elapsedNs.toDouble() / iterations) / 1_000_000.0

        println("[TextNormalizerTest] Average normalization time: %.4f ms".format(avgMs))
        assertTrue("Expected average normalization time under 1 ms, got $avgMs ms", avgMs < 1.0)
    }
}
