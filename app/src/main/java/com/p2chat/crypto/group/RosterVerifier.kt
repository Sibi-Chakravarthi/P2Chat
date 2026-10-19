package com.p2chat.crypto.group

import com.p2chat.crypto.prim.Ecdsa
import com.p2chat.wire.BleConstants

/**
 * Validates group roster versions, bounds, and admin digital signatures (SEC-10, T1.8).
 */
object RosterVerifier {

    sealed class RosterVerdict {
        object Valid : RosterVerdict()
        data class OutdatedVersion(val current: Int, val proposed: Int) : RosterVerdict()
        object ExceedsMaxMembers : RosterVerdict()
        object InvalidSignature : RosterVerdict()
    }

    fun verify(
        signedRoster: SignedRoster,
        adminSigningPubkey: ByteArray,
        currentRosterVersion: Int
    ): RosterVerdict {
        // 1. Enforce 16 member ceiling
        if (signedRoster.members.size > BleConstants.MAX_ROSTER_MEMBERS) {
            return RosterVerdict.ExceedsMaxMembers
        }

        // 2. Enforce strictly strictly monotonic roster version
        if (signedRoster.rosterVersion <= currentRosterVersion) {
            return RosterVerdict.OutdatedVersion(current = currentRosterVersion, proposed = signedRoster.rosterVersion)
        }

        // 3. Verify admin ECDSA signature
        val dataToVerify = RosterCodec.encodeForSigning(
            groupId = signedRoster.groupId,
            rosterVersion = signedRoster.rosterVersion,
            adminFp = signedRoster.adminFp,
            members = signedRoster.members
        )

        val validSig = Ecdsa.verify(adminSigningPubkey, dataToVerify, signedRoster.signature)
        if (!validSig) {
            return RosterVerdict.InvalidSignature
        }

        return RosterVerdict.Valid
    }
}
