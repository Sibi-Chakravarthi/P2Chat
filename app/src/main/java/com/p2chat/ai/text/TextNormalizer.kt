package com.p2chat.ai.text

/**
 * Text normalizer for on-device AI moderation (AI-2, T3.6).
 *
 * Rules:
 * 1. Strips zero-width characters (\u200B, \u200C, \u200D, \u200E, \u200F, \u2060, \uFEFF).
 * 2. Collapses runs of more than 2 repeated characters to 2 ('heeeelp' -> 'heelp', so 'good' and 'see' survive).
 * 3. Maps @->a, 1/!->i, $->s, 0->o only when bounded by letters on both sides, ensuring
 *    '12:00', '1 km', 'stop!', and '@name' remain completely unchanged.
 */
object TextNormalizer {

    private val ZERO_WIDTH_REGEX = Regex("[\u200B\u200C\u200D\u200E\u200F\u2060\uFEFF]")
    private val RUNS_REGEX = Regex("(.)\\1{2,}")
    private val LEET_AT_REGEX = Regex("(?<=[a-zA-Z])@(?=[a-zA-Z])")
    private val LEET_ONE_EXCL_REGEX = Regex("(?<=[a-zA-Z])[1!](?=[a-zA-Z])")
    private val LEET_DOLLAR_REGEX = Regex("(?<=[a-zA-Z])\\$(?=[a-zA-Z])")
    private val LEET_ZERO_REGEX = Regex("(?<=[a-zA-Z])0(?=[a-zA-Z])")

    fun normalize(text: String): String {
        if (text.isEmpty()) return ""

        var res = ZERO_WIDTH_REGEX.replace(text, "")
        res = RUNS_REGEX.replace(res, "$1$1")
        res = LEET_AT_REGEX.replace(res, "a")
        res = LEET_ONE_EXCL_REGEX.replace(res, "i")
        res = LEET_DOLLAR_REGEX.replace(res, "s")
        res = LEET_ZERO_REGEX.replace(res, "o")
        return res
    }
}
