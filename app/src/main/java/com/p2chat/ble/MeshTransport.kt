package com.p2chat.ble

import com.p2chat.util.SecureLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MeshTransport coordinates active BLE peer links and provides broadcast/flooding delivery (T2.9, NET-6, NET-7).
 */
interface MeshTransport {
    /**
     * Floods a wire frame to all connected peer sinks, optionally excluding the source peer.
     * Returns the count of sinks to which delivery was initiated successfully.
     */
    suspend fun flood(frameBytes: ByteArray, excludePeerId: String? = null): Int

    /**
     * Registers an active peer connection sink.
     */
    fun registerSink(sink: PeerSink)

    /**
     * Unregisters a disconnected or lost peer sink.
     */
    fun unregisterSink(sink: PeerSink)

    /**
     * Returns the list of currently registered peer sinks.
     */
    fun getSinks(): List<PeerSink>
}

/**
 * Thread-safe default in-memory MeshTransport implementation.
 */
class DefaultMeshTransport : MeshTransport {
    companion object {
        private const val TAG = "DefaultMeshTransport"
    }

    private val sinks = CopyOnWriteArrayList<PeerSink>()

    override fun registerSink(sink: PeerSink) {
        sinks.addIfAbsent(sink)
        SecureLog.d(TAG, "Registered peer sink ${sink.peerId} (total sinks: ${sinks.size})")
    }

    override fun unregisterSink(sink: PeerSink) {
        sinks.remove(sink)
        SecureLog.d(TAG, "Unregistered peer sink ${sink.peerId} (remaining sinks: ${sinks.size})")
    }

    override fun getSinks(): List<PeerSink> = sinks.toList()

    override suspend fun flood(frameBytes: ByteArray, excludePeerId: String?): Int {
        var delivered = 0
        for (sink in sinks) {
            if (excludePeerId != null && sink.peerId == excludePeerId) {
                continue
            }
            try {
                if (sink.sendFrame(frameBytes)) {
                    delivered++
                }
            } catch (t: Throwable) {
                SecureLog.w(TAG, "Failed to send frame to sink ${sink.peerId}: ${t.message}")
            }
        }
        return delivered
    }
}
