package com.p2chat.crypto.invite

import com.p2chat.crypto.keys.EcProvider
import com.p2chat.data.identity.Peer
import com.p2chat.data.identity.PeerDao
import com.p2chat.data.identity.PeerPinningResult

sealed class PinningVerdict {
    data class Pinned(val peer: Peer) : PinningVerdict()
    data class AlreadyPinned(val peer: Peer) : PinningVerdict()
    data class PeerKeyChanged(
        val existing: Peer,
        val newSigningPub: ByteArray,
        val newEcdhPub: ByteArray
    ) : PinningVerdict()
}

/**
 * Trust-On-First-Use (TOFU) key pinning manager (ID-5, SEC-8, T1.9).
 * Ensures that if a known sender_fp presents a different signing or ECDH key,
 * the change is strictly rejected and surfaced as PeerKeyChanged.
 */
object PeerPinning {

    suspend fun pinFromQr(
        peerDao: PeerDao,
        qrPayload: QrPayload,
        nowMs: Long = System.currentTimeMillis()
    ): PinningVerdict {
        val senderFp = EcProvider.computeSenderFp(qrPayload.signingPub)
        val peer = Peer(
            senderFp = senderFp,
            signingPub = qrPayload.signingPub,
            ecdhPub = qrPayload.ecdhPub,
            alias = qrPayload.alias,
            verified = true,
            firstSeenMs = nowMs
        )

        return when (val result = peerDao.pinOrVerifyPeer(peer)) {
            is PeerPinningResult.Success -> PinningVerdict.Pinned(result.peer)
            is PeerPinningResult.Unchanged -> PinningVerdict.AlreadyPinned(result.peer)
            is PeerPinningResult.KeyConflict -> PinningVerdict.PeerKeyChanged(
                existing = result.existing,
                newSigningPub = result.attempted.signingPub,
                newEcdhPub = result.attempted.ecdhPub
            )
        }
    }
}
