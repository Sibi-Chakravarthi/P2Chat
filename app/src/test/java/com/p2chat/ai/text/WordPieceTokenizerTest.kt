package com.p2chat.ai.text

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStreamReader

/**
 * JVM unit tests for WordPieceTokenizer verifying 100% fixture parity against Python (T3.7, AI-3).
 */
class WordPieceTokenizerTest {

    private lateinit var tokenizer: WordPieceTokenizer

    @Before
    fun setup() {
        val stream = javaClass.classLoader!!.getResourceAsStream("ai/vocab.txt")
            ?: throw IllegalStateException("Resource ai/vocab.txt not found")
        tokenizer = WordPieceTokenizer.fromInputStream(stream)
    }

    @Test
    fun testAll105FixturesMatchPythonOutputs() {
        val stream = javaClass.classLoader!!.getResourceAsStream("ai/tokenizer_fixtures.json")
            ?: throw IllegalStateException("Resource ai/tokenizer_fixtures.json not found")

        val jsonText = InputStreamReader(stream, Charsets.UTF_8).readText()
        val root = JSONObject(jsonText)
        val fixtures = root.getJSONArray("fixtures")

        assertTrue("Expected at least 100 fixtures", fixtures.length() >= 100)

        for (i in 0 until fixtures.length()) {
            val f = fixtures.getJSONObject(i)
            val fixtureId = f.getInt("id")
            val rawInput = f.getString("input")
            val expectedNormalized = f.getString("normalized")

            val expectedIdsArr = f.getJSONArray("token_ids")
            val expectedIds = IntArray(expectedIdsArr.length()) { expectedIdsArr.getInt(it) }

            val expectedMaskArr = f.getJSONArray("attention_mask")
            val expectedMask = IntArray(expectedMaskArr.length()) { expectedMaskArr.getInt(it) }

            // Normalize first per pipeline
            val normalized = TextNormalizer.normalize(rawInput)
            assertEquals("Normalized mismatch for fixture #$fixtureId", expectedNormalized, normalized)

            val tokenized = tokenizer.encode(normalized, maxLength = 128)

            assertArrayEquals(
                "Token IDs mismatch for fixture #$fixtureId ('$rawInput')",
                expectedIds,
                tokenized.tokenIds
            )
            assertArrayEquals(
                "Attention mask mismatch for fixture #$fixtureId ('$rawInput')",
                expectedMask,
                tokenized.attentionMask
            )
        }
    }

    @Test
    fun testLongTextTruncationWithSepToken() {
        val longText = (1..50).joinToString(" ") { "unexpected and unconstitutional occurrence number $it" }
        val tokenized = tokenizer.encode(longText, maxLength = 128)

        assertEquals(128, tokenized.tokenIds.size)
        assertEquals(128, tokenized.attentionMask.size)

        // First token must be [CLS] (101)
        assertEquals(WordPieceTokenizer.CLS_ID, tokenized.tokenIds[0])
        assertEquals(1, tokenized.attentionMask[0])

        // Last token must be [SEP] (102) because text exceeded max length
        assertEquals(WordPieceTokenizer.SEP_ID, tokenized.tokenIds[127])
        assertEquals(1, tokenized.attentionMask[127])

        // All attention mask entries must be 1 (no padding needed)
        assertTrue(tokenized.attentionMask.all { it == 1 })
    }

    @Test
    fun testEmptyInputProducesValidTokens() {
        val tokenized = tokenizer.encode("", maxLength = 128)

        assertEquals(128, tokenized.tokenIds.size)
        assertEquals(128, tokenized.attentionMask.size)

        // [CLS] at 0, [SEP] at 1, [PAD] for rest
        assertEquals(WordPieceTokenizer.CLS_ID, tokenized.tokenIds[0])
        assertEquals(WordPieceTokenizer.SEP_ID, tokenized.tokenIds[1])
        assertEquals(1, tokenized.attentionMask[0])
        assertEquals(1, tokenized.attentionMask[1])

        for (i in 2 until 128) {
            assertEquals(WordPieceTokenizer.PAD_ID, tokenized.tokenIds[i])
            assertEquals(0, tokenized.attentionMask[i])
        }
    }

    @Test
    fun testUnknownCharactersProduceUnkWithoutException() {
        val strangeText = "Hello \uD83D\uDE00 \u1F60 \u0370 unknown glyphs"
        val tokenized = tokenizer.encode(strangeText, maxLength = 128)

        assertNotNull(tokenized)
        assertEquals(128, tokenized.tokenIds.size)
        assertEquals(WordPieceTokenizer.CLS_ID, tokenized.tokenIds[0])
        assertTrue("Expected [UNK] token (100) to be present for unknown characters", tokenized.tokenIds.contains(WordPieceTokenizer.UNK_ID))
    }
}
