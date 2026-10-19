package com.p2chat.util

import android.util.Log

/**
 * Secure logging guard enforcing SEC-16:
 * - Only metadata, event markers, and sanitized tags are permitted.
 * - Never log sensitive cryptographic material: plaintext, ciphertexts, nonces, keys, or shared secrets.
 * - ByteArrays are strictly forbidden at compile time and runtime.
 */
object SecureLog {

    fun d(tag: String, message: String) {
        try {
            Log.d(tag, message)
        } catch (_: Throwable) {
            // Safe fallback in non-Android / unit test environments
        }
    }

    fun i(tag: String, message: String) {
        try {
            Log.i(tag, message)
        } catch (_: Throwable) {
            // Safe fallback
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) {
                Log.w(tag, message, throwable)
            } else {
                Log.w(tag, message)
            }
        } catch (_: Throwable) {
            // Safe fallback
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) {
                Log.e(tag, message, throwable)
            } else {
                Log.e(tag, message)
            }
        } catch (_: Throwable) {
            // Safe fallback
        }
    }

    // Compile-time & runtime guards refusing ByteArrays (SEC-16)
    @Deprecated("Byte arrays must never be logged (SEC-16)", level = DeprecationLevel.ERROR)
    fun d(tag: String, bytes: ByteArray): Nothing = throw UnsupportedOperationException("Byte arrays cannot be logged (SEC-16)")

    @Deprecated("Byte arrays must never be logged (SEC-16)", level = DeprecationLevel.ERROR)
    fun i(tag: String, bytes: ByteArray): Nothing = throw UnsupportedOperationException("Byte arrays cannot be logged (SEC-16)")

    @Deprecated("Byte arrays must never be logged (SEC-16)", level = DeprecationLevel.ERROR)
    fun w(tag: String, bytes: ByteArray): Nothing = throw UnsupportedOperationException("Byte arrays cannot be logged (SEC-16)")

    @Deprecated("Byte arrays must never be logged (SEC-16)", level = DeprecationLevel.ERROR)
    fun e(tag: String, bytes: ByteArray): Nothing = throw UnsupportedOperationException("Byte arrays cannot be logged (SEC-16)")
}
