package com.p2chat.pipeline

import com.p2chat.ai.FilterService
import com.p2chat.ai.FilterVerdict
import com.p2chat.ble.PacketRouter
import com.p2chat.crypto.envelope.EnvelopeVerifier
import com.p2chat.crypto.envelope.MessageLimits
import com.p2chat.crypto.envelope.PacketVerdict
import com.p2chat.crypto.keys.KeyVault
import com.p2chat.data.ai.QuarantineDao
import com.p2chat.data.ai.QuarantineEntity
import com.p2chat.data.group.GroupDao
import com.p2chat.data.group.RosterMember
import com.p2chat.data.group.RosterMemberDao
import com.p2chat.data.identity.PeerDao
import com.p2chat.data.message.EncryptedBody
import com.p2chat.data.message.MessageDao
import com.p2chat.data.message.MessageEntity
import com.p2chat.util.Clock
import com.p2chat.util.SecureLog
import com.p2chat.wire.PacketHeader
import com.p2chat.wire.PacketType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * End-to-end incoming packet coordinator (T4.2, SEC-6, SEC-12..14, NET-9, NET-10, AI-1, AI-7, AI-12, BENCH-5, D-6).
 *
 * Implements Layer 2 -> Layer 1 -> Layer 3 -> Room data flow:
 * 1. Checks group membership (non-member packets are marked [PacketVerdict.NotMemberOpaque] for relay-only).
 * 2. Evaluates shared group key and epoch synchronization with key fallback (Directive 2, STEP 3.1).
 * 3. Enforces roster and pinned peer membership and revoked sender checks, supporting one-way invite joins via group-key-only auth.
 * 4. Authenticates packet via ECDSA signature or AES-256-GCM tag + 24-byte header AAD.
 * 5. Routes benchmark packets directly to [BenchPacketListener] (never touches chat DB or AI filter).
 * 6. Routes control packets to [ControlPacketHandler] and automatically decrypts held future-epoch packets.
 * 7. Dispatches chat messages to fail-safe [FilterService.classify] on [Dispatchers.Default] (Directive 4) and persists to Room.
 */
class ReceivePipeline(
    private val groupDao: GroupDao,
    private val rosterMemberDao: RosterMemberDao,
    private val messageDao: MessageDao,
    private val quarantineDao: QuarantineDao,
    private val keyVault: KeyVault,
    private val filterService: FilterService,
    private val packetRouter: PacketRouter,
    private val controlPacketHandler: ControlPacketHandler,
    private val clock: Clock = Clock.System,
    private val peerDao: PeerDao? = null
) {
    companion object {
        private const val TAG = "ReceivePipeline"
    }

    // Buffer for holding packets arriving for future epochs before key rotation completes
    private val heldFutureEpochPackets = ConcurrentHashMap<Pair<Long, Int>, MutableList<ByteArray>>()

    init {
        controlPacketHandler.setOnEpochRotatedListener { groupId, newEpoch ->
            onNewEpochKeyAvailable(groupId, newEpoch)
        }
    }

    /**
     * Processes an incoming raw BLE frame end-to-end.
     */
    suspend fun processFrame(frameBytes: ByteArray): PacketVerdict = withContext(Dispatchers.Default) {
        if (frameBytes.size < 2) {
            return@withContext PacketVerdict.DropMalformed
        }

        // 1. Frame header validation
        val header = PacketHeader.decode(frameBytes, 0).getOrElse {
            return@withContext PacketVerdict.DropMalformed
        }

        // 2. TTL check
        if (header.ttl < 1 || header.ttl > MessageLimits.MAX_TTL) {
            return@withContext PacketVerdict.DropTtl
        }

        // 3. Group membership check (SEC-2, NET-10)
        val group = groupDao.getGroup(header.groupId)
        if (group == null) {
            if (header.packetType == PacketType.PAIRWISE_INVITE || header.packetType == PacketType.GROUP_REMOVE_MEMBER) {
                controlPacketHandler.handleControlPacket(
                    PacketVerdict.Accept(
                        header = header,
                        sentAtMs = clock.currentTimeMillis(),
                        plaintext = ""
                    ),
                    frameBytes
                )
            }
            return@withContext PacketVerdict.NotMemberOpaque
        }

        // Pairwise-sealed control packets are processed directly by ControlPacketHandler
        if (header.packetType == PacketType.GROUP_REMOVE_MEMBER) {
            val verdict = PacketVerdict.Accept(
                header = header,
                sentAtMs = clock.currentTimeMillis(),
                plaintext = ""
            )
            controlPacketHandler.handleControlPacket(verdict, frameBytes)
            return@withContext verdict
        }

        // 4. Roster membership & revocation check (SEC-1, SEC-9)
        val existingMember = rosterMemberDao.getMember(header.groupId, header.senderFp)
        if (existingMember != null && existingMember.revoked) {
            return@withContext PacketVerdict.DropRevokedSender
        }

        val pinnedPeer = if (existingMember == null) peerDao?.getPeer(header.senderFp) else null
        val senderSigningPub: ByteArray? = when {
            existingMember != null && existingMember.signingPub.isNotEmpty() -> existingMember.signingPub
            pinnedPeer != null && pinnedPeer.signingPub.isNotEmpty() -> pinnedPeer.signingPub
            else -> null
        }

        // 5. Shared group key resolution & Decryption with Key Fallback (Step 4 & Step 6)
        val currentGroupKeyBytes = try {
            keyVault.unwrap(group.wrappedCurrentKey)
        } catch (t: Throwable) {
            SecureLog.e(TAG, "Failed to unwrap current group key for group ${header.groupId}: ${t.message}")
            null
        }

        // First attempt decryption with current key
        var verdict: PacketVerdict = if (currentGroupKeyBytes != null) {
            EnvelopeVerifier.verifyAndOpen(
                frameBytes = frameBytes,
                groupKey = currentGroupKeyBytes,
                senderSigningPubkey = senderSigningPub,
                clock = clock
            )
        } else {
            PacketVerdict.DropAuthFail
        }

        // If decryption fails (DropAuthFail) and wrappedPrevKey != null, immediately retry with wrappedPrevKey
        if (verdict is PacketVerdict.DropAuthFail && group.wrappedPrevKey != null) {
            val prevGroupKeyBytes = try {
                keyVault.unwrap(group.wrappedPrevKey)
            } catch (_: Throwable) {
                null
            }
            if (prevGroupKeyBytes != null) {
                val prevVerdict = EnvelopeVerifier.verifyAndOpen(
                    frameBytes = frameBytes,
                    groupKey = prevGroupKeyBytes,
                    senderSigningPubkey = senderSigningPub,
                    clock = clock
                )
                if (prevVerdict is PacketVerdict.Accept) {
                    verdict = prevVerdict
                }
            }
        }

        // If packet could not be decrypted right now:
        if (verdict !is PacketVerdict.Accept) {
            // If packet is for a future epoch, hold for when key arrives (SEC-12, SEC-13)
            if (header.keyEpoch > group.currentEpoch) {
                heldFutureEpochPackets.getOrPut(Pair(header.groupId, header.keyEpoch)) { ArrayList() }
                    .add(frameBytes)
                return@withContext PacketVerdict.HoldFutureEpoch
            }

            // If packet is for an expired epoch
            if (header.keyEpoch < group.currentEpoch) {
                return@withContext PacketVerdict.DropEpochExpired
            }

            // D-6 Eviction: On bad signature or auth failure, evict from router
            if (verdict is PacketVerdict.DropBadSignature || verdict is PacketVerdict.DropAuthFail) {
                SecureLog.w(TAG, "Integrity check failed (${verdict.javaClass.simpleName}); evicting msgId ${header.msgId}")
                packetRouter.evict(header.msgId)
            }
            return@withContext verdict
        }

        // Packet is accepted! If header epoch is newer, synchronize local group epoch
        if (header.keyEpoch > group.currentEpoch) {
            val updatedGroup = group.copy(currentEpoch = header.keyEpoch)
            groupDao.updateGroup(updatedGroup)
            SecureLog.d(TAG, "Synchronized group ${header.groupId} epoch from ${group.currentEpoch} to ${header.keyEpoch}")
        }

        // Auto-register sender into group roster (Fix A)
        if (existingMember == null) {
            val newMember = if (pinnedPeer != null) {
                RosterMember(
                    groupId = header.groupId,
                    senderFp = header.senderFp,
                    signingPub = pinnedPeer.signingPub,
                    ecdhPub = pinnedPeer.ecdhPub,
                    alias = pinnedPeer.alias,
                    revoked = false
                )
            } else {
                RosterMember(
                    groupId = header.groupId,
                    senderFp = header.senderFp,
                    signingPub = ByteArray(0),
                    ecdhPub = ByteArray(0),
                    alias = "Peer-${java.lang.Long.toUnsignedString(header.senderFp, 16).takeLast(4).uppercase()}",
                    revoked = false
                )
            }
            rosterMemberDao.insertOrUpdate(newMember)
            SecureLog.d(TAG, "Auto-registered member ${header.senderFp} (${newMember.alias}) into group ${header.groupId} roster")
        }

        // 6. Route accepted packet by packet type
        when (header.packetType) {
            PacketType.BENCH_DATA, PacketType.BENCH_ACK -> {
                // Benchmark packets are ignored and never touch message DB or AI filter
                return@withContext verdict
            }

            PacketType.KEY_ROTATE, PacketType.ROSTER_SYNC, PacketType.PAIRWISE_INVITE, PacketType.GROUP_REMOVE_MEMBER -> {
                controlPacketHandler.handleControlPacket(verdict, frameBytes)
                return@withContext verdict
            }

            PacketType.CHAT -> {
                // 7. Non-Blocking Fail-Safe Content Filtering (AI-1, AI-7, AI-12, Directive 4, STEP 3.3)
                val filterVerdict = withTimeoutOrNull(500L) {
                    runCatching {
                        filterService.classify(header.msgId, verdict.plaintext)
                    }.getOrNull()
                } ?: run {
                    SecureLog.w(TAG, "TFLite moderation timed out or failed for msgId ${header.msgId}; failing safe to NotScanned")
                    FilterVerdict.NotScanned("Moderation timeout or error")
                }

                // 8. Persist to Room (Messages NEVER drop or vanish)
                try {
                    when (filterVerdict) {
                        is FilterVerdict.Tier1Quarantine -> {
                            // Persist only to quarantine table (AI-7)
                            val quarantineEntry = QuarantineEntity(
                                msgId = header.msgId,
                                groupId = header.groupId,
                                senderFp = header.senderFp,
                                body = EncryptedBody.of(verdict.plaintext),
                                score = filterVerdict.score,
                                nearestCategory = filterVerdict.topCategories.firstOrNull()?.category ?: "threat",
                                createdAtMs = verdict.sentAtMs,
                                expiresAtMs = verdict.sentAtMs + QuarantineDao.SEVEN_DAYS_MS
                            )
                            quarantineDao.insert(quarantineEntry)
                        }

                        is FilterVerdict.Tier2Warning -> {
                            // Persist to messages blurred (revealed = false)
                            val message = MessageEntity(
                                msgId = header.msgId,
                                groupId = header.groupId,
                                senderFp = header.senderFp,
                                keyEpoch = header.keyEpoch,
                                sentAtMs = verdict.sentAtMs,
                                receivedAtMs = clock.currentTimeMillis(),
                                body = EncryptedBody.of(verdict.plaintext),
                                sigOk = senderSigningPub != null,
                                tier = 2,
                                score = filterVerdict.score,
                                revealed = false
                            )
                            messageDao.insertMessage(message)
                        }

                        is FilterVerdict.Tier3Clean -> {
                            // Persist to messages clean (revealed = true)
                            val message = MessageEntity(
                                msgId = header.msgId,
                                groupId = header.groupId,
                                senderFp = header.senderFp,
                                keyEpoch = header.keyEpoch,
                                sentAtMs = verdict.sentAtMs,
                                receivedAtMs = clock.currentTimeMillis(),
                                body = EncryptedBody.of(verdict.plaintext),
                                sigOk = senderSigningPub != null,
                                tier = 3,
                                score = filterVerdict.score,
                                revealed = true
                            )
                            messageDao.insertMessage(message)
                        }

                        is FilterVerdict.NotScanned -> {
                            // Persist with revealed = true so messages appear readable immediately without requiring extra tap
                            val message = MessageEntity(
                                msgId = header.msgId,
                                groupId = header.groupId,
                                senderFp = header.senderFp,
                                keyEpoch = header.keyEpoch,
                                sentAtMs = verdict.sentAtMs,
                                receivedAtMs = clock.currentTimeMillis(),
                                body = EncryptedBody.of(verdict.plaintext),
                                sigOk = senderSigningPub != null,
                                tier = 0,
                                score = 0f,
                                revealed = true
                            )
                            messageDao.insertMessage(message)
                        }
                    }
                } catch (t: Throwable) {
                    SecureLog.e(TAG, "Error persisting message ${header.msgId} to Room: ${t.message}; falling back to clean save")
                    try {
                        messageDao.insertMessage(
                            MessageEntity(
                                msgId = header.msgId,
                                groupId = header.groupId,
                                senderFp = header.senderFp,
                                keyEpoch = header.keyEpoch,
                                sentAtMs = verdict.sentAtMs,
                                receivedAtMs = clock.currentTimeMillis(),
                                body = EncryptedBody.of(verdict.plaintext),
                                sigOk = senderSigningPub != null,
                                tier = 3,
                                score = 0f,
                                revealed = true
                            )
                        )
                    } catch (_: Throwable) {}
                }
                return@withContext verdict
            }

            else -> {
                SecureLog.w(TAG, "Unhandled accepted packet type: ${header.packetType}")
                return@withContext verdict
            }
        }
    }

    /**
     * Releases and processes any held packets buffered for [newEpoch] (SEC-12).
     */
    suspend fun onNewEpochKeyAvailable(groupId: Long, newEpoch: Int) {
        val key = Pair(groupId, newEpoch)
        val heldList = heldFutureEpochPackets.remove(key) ?: return
        SecureLog.d(TAG, "Releasing ${heldList.size} held packets for group $groupId epoch $newEpoch")
        for (packetBytes in heldList) {
            processFrame(packetBytes)
        }
    }

    fun getHeldPacketCount(groupId: Long, epoch: Int): Int {
        return heldFutureEpochPackets[Pair(groupId, epoch)]?.size ?: 0
    }
}
