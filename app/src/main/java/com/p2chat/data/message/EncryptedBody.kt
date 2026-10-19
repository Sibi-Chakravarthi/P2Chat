package com.p2chat.data.message

/**
 * Message text container supporting at-rest encryption and typed decryption errors (SEC-15).
 */
data class EncryptedBody(
    val text: String,
    val error: String? = null
) {
    val isError: Boolean get() = error != null

    companion object {
        fun of(text: String): EncryptedBody = EncryptedBody(text = text, error = null)
        fun error(reason: String): EncryptedBody = EncryptedBody(text = "", error = reason)
    }
}
