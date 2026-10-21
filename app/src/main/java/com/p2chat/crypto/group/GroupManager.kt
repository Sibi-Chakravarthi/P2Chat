package com.p2chat.crypto.group

import com.p2chat.crypto.keys.KeyVault
import com.p2chat.crypto.prim.Ecdsa
import com.p2chat.data.group.GroupDao
import com.p2chat.data.group.GroupEntity
import com.p2chat.data.group.RosterMemberDao
import com.p2chat.data.identity.LocalIdentity
import com.p2chat.wire.BleConstants
import java.security.PrivateKey
import java.security.SecureRandom

/**
 * Manages group lifecycle, group keys, and roster state (SEC-8, SEC-10, T1.8).
 * Ensures group keys are always stored wrapped under KeyVault at rest.
 */
class GroupManager(
    private val groupDao: GroupDao,
    private val rosterMemberDao: RosterMemberDao,
    private val keyVault: KeyVault = KeyVault()
) {

    private val secureRandom = SecureRandom()

    /**
     * Creates a new encrypted group with random 4-byte ID, fresh 256-bit key, epoch 1,
     * and an initial admin-signed roster version 1 (SEC-8).
     */
    suspend fun createGroup(
        name: String,
        adminIdentity: LocalIdentity,
        adminSigningPrivateKey: PrivateKey
    ): Pair<GroupEntity, SignedRoster> {
        // 1. Generate random 4-byte group ID (1 .. 0xFFFFFFFF)
        val groupId = (secureRandom.nextLong() and 0xFFFFFFFFL).let { if (it == 0L) 1L else it }

        // 2. Generate 256-bit AES group key and wrap under KeyVault
        val rawGroupKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val wrappedGroupKey = keyVault.wrap(rawGroupKey)

        // 3. Create initial roster with admin as member 1
        val adminMemberInfo = RosterMemberInfo(
            senderFp = adminIdentity.senderFp,
            signingPub = adminIdentity.signingPub,
            ecdhPub = adminIdentity.ecdhPub,
            alias = adminIdentity.alias,
            revoked = false
        )
        val membersList = listOf(adminMemberInfo)

        val rosterVersion = 1
        val unsignedRosterBytes = RosterCodec.encodeForSigning(
            groupId = groupId,
            rosterVersion = rosterVersion,
            adminFp = adminIdentity.senderFp,
            members = membersList
        )
        val adminSignature = Ecdsa.sign(adminSigningPrivateKey, unsignedRosterBytes)

        val signedRoster = SignedRoster(
            groupId = groupId,
            rosterVersion = rosterVersion,
            adminFp = adminIdentity.senderFp,
            members = membersList,
            signature = adminSignature
        )

        val groupEntity = GroupEntity(
            groupId = groupId,
            name = name,
            myRole = "ADMIN",
            currentEpoch = BleConstants.INITIAL_KEY_EPOCH,
            wrappedCurrentKey = wrappedGroupKey,
            rosterVersion = rosterVersion
        )

        // 4. Persist group and roster
        groupDao.insertGroup(groupEntity)
        rosterMemberDao.insertOrUpdate(adminMemberInfo.toRosterMember(groupId))

        return Pair(groupEntity, signedRoster)
    }

    /**
     * Applies a ROSTER_SYNC update, accepting it only if it has a higher version
     * and a valid admin signature (SEC-10).
     */
    suspend fun applyRosterSync(
        signedRoster: SignedRoster,
        adminSigningPubkey: ByteArray
    ): Boolean {
        val group = groupDao.getGroup(signedRoster.groupId) ?: return false

        val verdict = RosterVerifier.verify(
            signedRoster = signedRoster,
            adminSigningPubkey = adminSigningPubkey,
            currentRosterVersion = group.rosterVersion
        )

        if (verdict !is RosterVerifier.RosterVerdict.Valid) {
            return false
        }

        // Update group roster version
        val updatedGroup = group.copy(rosterVersion = signedRoster.rosterVersion)
        groupDao.updateGroup(updatedGroup)

        // Persist updated members
        val dbMembers = signedRoster.members.map { it.toRosterMember(signedRoster.groupId) }
        rosterMemberDao.insertAll(dbMembers)

        return true
    }

    /**
     * Joins a group as a member upon accepting an invite (SEC-9).
     */
    suspend fun joinGroup(
        groupId: Long,
        name: String,
        rawGroupKey: ByteArray,
        keyEpoch: Int,
        rosterVersion: Int,
        members: List<RosterMemberInfo>
    ): GroupEntity {
        val existing = groupDao.getGroup(groupId)
        if (existing != null) {
            return existing
        }
        val wrappedGroupKey = keyVault.wrap(rawGroupKey)
        val groupEntity = GroupEntity(
            groupId = groupId,
            name = name,
            myRole = "MEMBER",
            currentEpoch = keyEpoch,
            wrappedCurrentKey = wrappedGroupKey,
            rosterVersion = rosterVersion
        )
        groupDao.insertGroup(groupEntity)
        val dbMembers = members.map { it.toRosterMember(groupId) }
        rosterMemberDao.insertAll(dbMembers)
        return groupEntity
    }

    fun unwrapGroupKey(group: GroupEntity): ByteArray {
        return keyVault.unwrap(group.wrappedCurrentKey)
    }

    fun unwrapPreviousGroupKey(group: GroupEntity): ByteArray? {
        val wrapped = group.wrappedPrevKey ?: return null
        return keyVault.unwrap(wrapped)
    }
}
