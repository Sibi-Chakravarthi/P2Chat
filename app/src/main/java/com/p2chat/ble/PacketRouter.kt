package com.p2chat.ble

import com.p2chat.data.mesh.BufferedPacketDao
import com.p2chat.data.mesh.SeenMessageDao

/**
 * PacketRouter interface for BLE mesh packet routing and eviction (PRD §5.3, Decision 8, D-6).
 */
interface PacketRouter {
    /**
     * Evicts a message ID from seen_messages and packet_buffer so malicious
     * or corrupted frames cannot squat the ID and block the genuine frame (D-6).
     */
    suspend fun evict(msgId: Long)
}

/**
 * Default implementation backed by Room mesh DAOs.
 */
class DefaultPacketRouter(
    private val seenMessageDao: SeenMessageDao,
    private val bufferedPacketDao: BufferedPacketDao
) : PacketRouter {

    override suspend fun evict(msgId: Long) {
        seenMessageDao.delete(msgId)
        bufferedPacketDao.delete(msgId)
    }
}
