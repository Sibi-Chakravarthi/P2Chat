package com.p2chat.ai.text

import java.io.InputStream
import java.text.Normalizer
import java.util.Locale

/**
 * Encoded model input container holding fixed-size token IDs and attention mask.
 */
data class TokenizedInput(
    val tokenIds: IntArray,
    val attentionMask: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TokenizedInput

        if (!tokenIds.contentEquals(other.tokenIds)) return false
        if (!attentionMask.contentEquals(other.attentionMask)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = tokenIds.contentHashCode()
        result = 31 * result + attentionMask.contentHashCode()
        return result
    }
}

/**
 * Pure Kotlin WordPiece tokenizer matching Hugging Face BertTokenizer uncased (AI-3, T3.7).
 * Handles full 32-bit Unicode code points including surrogate pairs and emojis.
 */
class WordPieceTokenizer(private val vocab: Map<String, Int>) {

    companion object {
        const val MAX_SEQUENCE_LENGTH = 128
        const val CLS_TOKEN = "[CLS]"
        const val SEP_TOKEN = "[SEP]"
        const val UNK_TOKEN = "[UNK]"
        const val PAD_TOKEN = "[PAD]"

        const val CLS_ID = 101
        const val SEP_ID = 102
        const val UNK_ID = 100
        const val PAD_ID = 0

        fun fromInputStream(stream: InputStream): WordPieceTokenizer {
            val vocab = VocabLoader.loadVocab(stream)
            return WordPieceTokenizer(vocab)
        }

        private fun isControlCp(cp: Int): Boolean {
            if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
            val type = Character.getType(cp)
            return type == Character.CONTROL.toInt() ||
                    type == Character.FORMAT.toInt() ||
                    type == Character.PRIVATE_USE.toInt() ||
                    type == Character.SURROGATE.toInt()
        }

        private fun isPunctuationCp(cp: Int): Boolean {
            if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) {
                return true
            }
            val type = Character.getType(cp)
            return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
                    type == Character.DASH_PUNCTUATION.toInt() ||
                    type == Character.START_PUNCTUATION.toInt() ||
                    type == Character.END_PUNCTUATION.toInt() ||
                    type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
                    type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                    type == Character.OTHER_PUNCTUATION.toInt()
        }
    }

    /**
     * Basic tokenization: strips control characters by Unicode code point, normalizes NFD,
     * strips accents (Mn), lowercases, and splits on whitespace and punctuation.
     */
    fun basicTokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        // 1. Strip control characters by 32-bit code point
        val cleanBuilder = java.lang.StringBuilder()
        val codePoints = text.codePoints().toArray()
        for (cp in codePoints) {
            if (!isControlCp(cp)) {
                cleanBuilder.appendCodePoint(cp)
            }
        }

        // 2. NFD decomposition and strip accents (Mn - Non-spacing marks)
        val normalized = Normalizer.normalize(cleanBuilder.toString(), Normalizer.Form.NFD)
        val stripped = java.lang.StringBuilder()
        for (cp in normalized.codePoints().toArray()) {
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) {
                stripped.appendCodePoint(cp)
            }
        }

        // 3. Lowercase
        val lower = stripped.toString().lowercase(Locale.ROOT)

        // 4. Split on whitespace and punctuation by code point
        val tokens = ArrayList<String>()
        val currentToken = java.lang.StringBuilder()

        for (cp in lower.codePoints().toArray()) {
            if (Character.isWhitespace(cp)) {
                if (currentToken.isNotEmpty()) {
                    tokens.add(currentToken.toString())
                    currentToken.clear()
                }
            } else if (isPunctuationCp(cp)) {
                if (currentToken.isNotEmpty()) {
                    tokens.add(currentToken.toString())
                    currentToken.clear()
                }
                tokens.add(String(Character.toChars(cp)))
            } else {
                currentToken.appendCodePoint(cp)
            }
        }
        if (currentToken.isNotEmpty()) {
            tokens.add(currentToken.toString())
        }

        return tokens
    }

    /**
     * Greedy longest-match WordPiece tokenization for a single word.
     */
    fun wordpieceTokenize(word: String): List<String> {
        if (word.length > 200) {
            return listOf(UNK_TOKEN)
        }

        val subTokens = ArrayList<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var curSubstr: String? = null
            while (start < end) {
                var substr = word.substring(start, end)
                if (start > 0) {
                    substr = "##$substr"
                }
                if (vocab.containsKey(substr)) {
                    curSubstr = substr
                    break
                }
                end--
            }
            if (curSubstr == null) {
                return listOf(UNK_TOKEN)
            }
            subTokens.add(curSubstr)
            start = end
        }
        return subTokens
    }

    /**
     * Tokenizes [text] into WordPiece subwords.
     */
    fun tokenize(text: String): List<String> {
        val words = basicTokenize(text)
        val tokens = ArrayList<String>()
        for (w in words) {
            tokens.addAll(wordpieceTokenize(w))
        }
        return tokens
    }

    /**
     * Encodes [text] into fixed-size [TokenizedInput] (length [maxLength], default 128).
     * Structure: [CLS] + subwords[:maxLength - 2] + [SEP] + padding to [maxLength].
     */
    fun encode(text: String, maxLength: Int = MAX_SEQUENCE_LENGTH): TokenizedInput {
        val rawTokens = tokenize(text)
        val truncated = if (rawTokens.size > maxLength - 2) {
            rawTokens.subList(0, maxLength - 2)
        } else {
            rawTokens
        }

        val tokenIds = IntArray(maxLength) { PAD_ID }
        val attentionMask = IntArray(maxLength) { 0 }

        tokenIds[0] = CLS_ID
        attentionMask[0] = 1

        for (i in truncated.indices) {
            tokenIds[i + 1] = vocab[truncated[i]] ?: UNK_ID
            attentionMask[i + 1] = 1
        }

        val sepIndex = truncated.size + 1
        tokenIds[sepIndex] = SEP_ID
        attentionMask[sepIndex] = 1

        return TokenizedInput(tokenIds, attentionMask)
    }
}
