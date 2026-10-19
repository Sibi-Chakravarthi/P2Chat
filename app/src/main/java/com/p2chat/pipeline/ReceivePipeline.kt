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
import com.p2chat.data.group.RosterMemberDao
import com.p2chat.data.message.EncryptedBody
import com.p2chat.data.message.MessageDao
import com.p2chat.data.message.MessageEntity
import com.p2chat.util.Clock
import com.p2chat.util.SecureLog
import com.p2chat.wire.PacketHeader
import com.p2chat.wire.PacketType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * End-to-end incoming packet coordinator (T4.2, SEC-6, SEC-12..14, NET-9, NET-10, AI-1, AI-7, AI-12, BENCH-5, D-6).
 *
 * Implements Layer 2 -> Layer 1 -> Layer 3 -> Room data flow:
 * 1. Checks group membership (non-member packets are marked [PacketVerdict.NotMemberOpaque] for relay-only).
 * 2. Evaluates epoch policy: buffers future-epoch frames, drops expired epochs.
 * 3. Enforces roster membership and revoked sender checks.
 * 4. Verifies ECDSA signature BEFORE attempting decryption. On signature or GCM auth failure,
 *    immediately invokes [PacketRouter.evict] to prevent msgId squatting attacks (D-6).
 * 5. Routes benchmark packets directly to [BenchPacketListener] (never touches chat DB or AI filter).
 * 6. Routes control packets to [ControlPacketHandler] and automatically decrypts held future-epoch packets.
 * 7. Dispatches chat messages to [FilterService.classify] on [Dispatchers.Default] and persists to Room.
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
    private val clock: Clock = Clock.System
) {
    companion object {
        private const val TAG = "ReceivePipeline"
    }

    var benchListener: BenchPacketListener? = null

    // Buffer for holding packets arriving for future epochs (N+1) before key rotation completes
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
            return@withContext PacketVerdict.NotMemberOpaque
        }

        // 4. Epoch evaluation (SEC-12, SEC-13)
        val currentEpoch = group.currentEpoch
        val groupKeyBytes: ByteArray

        if (header.keyEpoch > currentEpoch) {
            // Buffer opaque frame for future epoch
            heldFutureEpochPackets.getOrPut(Pair(header.groupId, header.keyEpoch)) { ArrayList() }
                .add(frameBytes)
            return@withContext PacketVerdict.HoldFutureEpoch
        } else if (header.keyEpoch < currentEpoch) {
            if (header.keyEpoch == group.prevEpoch && group.wrappedPrevKey != null) {
                val graceExpires = group.graceExpiresMs ?: 0L
                if (clock.currentTimeMillis() > graceExpires) {
                    return@withContext PacketVerdict.DropEpochExpired
                }
                groupKeyBytes = keyVault.unwrap(group.wrappedPrevKey)
            } else {
                return@withContext PacketVerdict.DropEpochExpired
            }
        } else {
            groupKeyBytes = keyVault.unwrap(group.wrappedCurrentKey)
        }

        // 5. Roster membership & revocation check (SEC-1, SEC-9)
        val roster = rosterMemberDao.getMembers(header.groupId)
        val member = roster.firstOrNull { it.senderFp == header.senderFp }
        if (member == null) {
            return@withContext PacketVerdict.DropUnknownSender
        }
        if (member.revoked) {
            return@withContext PacketVerdict.DropRevokedSender
        }

        // 6. Cryptographic verification & decryption (SEC-6, D-6)
        val verdict = EnvelopeVerifier.verifyAndOpen(
            frameBytes = frameBytes,
            groupKey = groupKeyBytes,
            senderSigningPubkey = member.signingPub,
            clock = clock
        )

        // D-6 / Decision 8: Evict msgId on forged or tampered packet to stop squatting
        if (verdict is PacketVerdict.DropBadSignature || verdict is PacketVerdict.DropAuthFail) {
            SecureLog.w(TAG, "Integrity check failed (${verdict.javaClass.simpleName}); evicting msgId ${header.msgId}")
            packetRouter.evict(header.msgId)
            return@withContext verdict
        }

        if (verdict !is PacketVerdict.Accept) {
            return@withContext verdict
        }

        // 7. Route accepted packet by packet type
        when (header.packetType) {
            PacketType.BENCH_DATA -> {
                // BENCH-5: direct to BenchRunner, never to messages or FilterService
                benchListener?.onBenchData(
                    msgId = header.msgId,
                    initialTtl = header.ttl,
                    receivedTtl = header.ttl,
                    payload = frameBytes
                )
                return@withContext verdict
            }

            PacketType.BENCH_ACK -> {
                benchListener?.onBenchAck(
                    msgId = header.msgId,
                    receivedTtl = header.ttl,
                    ackMonoNs = System.nanoTime()
                )
                return@withContext verdict
            }

            PacketType.KEY_ROTATE, PacketType.ROSTER_SYNC, PacketType.PAIRWISE_INVITE -> {
                controlPacketHandler.handleControlPacket(verdict, frameBytes)
                return@withContext verdict
            }

            PacketType.CHAT -> {
                // 8. Content filtering on receiving device (AI-1, AI-7, AI-12)
                val filterVerdict = filterService.classify(header.msgId, verdict.plaintext)

                // 9. Persist to Room
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
                            sigOk = true,
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
                            sigOk = true,
                            tier = 3,
                            score = filterVerdict.score,
                            revealed = true
                        )
                        messageDao.insertMessage(message)
                    }

                    is FilterVerdict.NotScanned -> {
                        // AI-13: Persist blurred with tap-to-reveal
                        val message = MessageEntity(
                            msgId = header.msgId,
                            groupId = header.groupId,
                            senderFp = header.senderFp,
                            keyEpoch = header.keyEpoch,
                            sentAtMs = verdict.sentAtMs,
                            receivedAtMs = clock.currentTimeMillis(),
                            body = EncryptedBody.of(verdict.plaintext),
                            sigOk = true,
                            tier = 0,
                            score = 0f,
                            revealed = false
                        )
                        messageDao.insertMessage(message)
                    }
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
