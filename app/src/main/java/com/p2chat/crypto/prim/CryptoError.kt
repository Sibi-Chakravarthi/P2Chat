package com.p2chat.crypto.prim

sealed class CryptoError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class BadKey(message: String, cause: Throwable? = null) : CryptoError(message, cause)
    class BadTag(message: String, cause: Throwable? = null) : CryptoError(message, cause)
    class BadAad(message: String, cause: Throwable? = null) : CryptoError(message, cause)
    class AuthFailed(message: String, cause: Throwable? = null) : CryptoError(message, cause)
    class BadSignature(message: String, cause: Throwable? = null) : CryptoError(message, cause)
    class InvalidLength(message: String) : CryptoError(message)
}
