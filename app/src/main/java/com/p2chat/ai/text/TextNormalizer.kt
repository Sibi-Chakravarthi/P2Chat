package com.p2chat.ai.text

/**
 * Text normalizer for on-device AI moderation (AI-2, T3.6).
 *
 * Rules:
 * 1. Strips zero-width characters (\u200B, \u200C, \u200D, \u200E, \u200F, \u2060, \uFEFF).
 * 2. Converts to lowercase for case-insensitive matching.
 * 3. Strips mask characters (* and #) commonly used to obfuscate profanity.
 * 4. Collapses runs of more than 2 repeated characters to 2 ('heeeelp' -> 'heelp', so 'good' and 'see' survive).
 * 5. Maps @->a, 1/!->i, $->s, 0->o, 3->e only when bounded by letters on both sides, ensuring
 *    '12:00', '1 km', 'stop!', and '@name' remain completely unchanged.
 */
object TextNormalizer {

    private val ZERO_WIDTH_REGEX = Regex("[\u200B\u200C\u200D\u200E\u200F\u2060\uFEFF]")
    private val MASK_CHARS_REGEX = Regex("[*#]")
    private val RUNS_REGEX = Regex("(.)\\1{2,}")
    private val LEET_AT_REGEX = Regex("(?<=[a-z])@(?=[a-z])")
    private val LEET_ONE_EXCL_REGEX = Regex("(?<=[a-z])[1!](?=[a-z])")
    private val LEET_DOLLAR_REGEX = Regex("(?<=[a-z])\\$(?=[a-z])")
    private val LEET_ZERO_REGEX = Regex("(?<=[a-z])0(?=[a-z])")
    private val LEET_THREE_REGEX = Regex("(?<=[a-z])3(?=[a-z]|\\b)")

    fun normalize(text: String): String {
        if (text.isEmpty()) return ""

        var res = ZERO_WIDTH_REGEX.replace(text, "")
        res = res.lowercase()
        res = MASK_CHARS_REGEX.replace(res, "")
        res = RUNS_REGEX.replace(res, "$1$1")
        res = LEET_AT_REGEX.replace(res, "a")
        res = LEET_ONE_EXCL_REGEX.replace(res, "i")
        res = LEET_DOLLAR_REGEX.replace(res, "s")
        res = LEET_ZERO_REGEX.replace(res, "o")
        res = LEET_THREE_REGEX.replace(res, "e")
        return res
    }

    /**
     * Alias for [normalize] to satisfy preprocessing specifications (Phase 3).
     */
    fun normalizeText(text: String): String = normalize(text)

    /**
     * Strict string preprocessing step before tokenization (Phase 2 fix):
     * Lowercases text and strips all non-alphanumeric and non-space characters.
     */
    fun preprocess(text: String): String {
        return text.lowercase().replace(Regex("[^a-z0-9 ]"), "")
    }
}
