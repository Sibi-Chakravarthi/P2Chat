package com.p2chat.util

/**
 * Clock abstraction for deterministic time-dependent operations and testing (AGENTS.md).
 */
interface Clock {
    fun currentTimeMillis(): Long

    object System : Clock {
        override fun currentTimeMillis(): Long = java.lang.System.currentTimeMillis()
    }
}

/**
 * Mutable fake clock for deterministic testing.
 */
class FakeClock(var currentMs: Long) : Clock {
    override fun currentTimeMillis(): Long = currentMs

    fun advanceMillis(millis: Long) {
        currentMs += millis
    }

    fun setTime(millis: Long) {
        currentMs = millis
    }
}
