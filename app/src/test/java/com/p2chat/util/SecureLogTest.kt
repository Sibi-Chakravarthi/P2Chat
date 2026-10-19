package com.p2chat.util

import org.junit.Assert.assertNotNull
import org.junit.Test

class SecureLogTest {

    @Test
    fun testSecureLog_callsDoNotThrow() {
        SecureLog.d("TestTag", "Debug message")
        SecureLog.i("TestTag", "Info message")
        SecureLog.w("TestTag", "Warning message")
        SecureLog.w("TestTag", "Warning with error", RuntimeException("test warning"))
        SecureLog.e("TestTag", "Error message")
        SecureLog.e("TestTag", "Error with exception", RuntimeException("test error"))
        assertNotNull(SecureLog)
    }
}
