package com.p2chat.data.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.p2chat.data.db.AppDatabase
import com.p2chat.data.group.GroupDao
import com.p2chat.data.group.GroupEntity
import com.p2chat.data.group.RosterMember
import com.p2chat.data.group.RosterMemberDao
import com.p2chat.data.settings.SettingDao
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IdentityAndGroupDatabaseTest {

    private lateinit var db: AppDatabase
    private lateinit var localIdentityDao: LocalIdentityDao
    private lateinit var peerDao: PeerDao
    private lateinit var groupDao: GroupDao
    private lateinit var rosterMemberDao: RosterMemberDao
    private lateinit var settingDao: SettingDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.createInMemory(context)
        localIdentityDao = db.localIdentityDao()
        peerDao = db.peerDao()
        groupDao = db.groupDao()
        rosterMemberDao = db.rosterMemberDao()
        settingDao = db.settingDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testLocalIdentity_insertAndRetrieve() = runBlocking {
        val identity = LocalIdentity(
            senderFp = 0x1234567890ABCDEFL,
            alias = "Alice",
            signingPub = ByteArray(65) { 0x01 },
            ecdhPub = ByteArray(65) { 0x02 },
            wrappedSigningPriv = ByteArray(80) { 0x03 },
            wrappedEcdhPriv = ByteArray(80) { 0x04 }
        )

        val id = localIdentityDao.insertIdentity(identity)
        assertTrue(id > 0)
        assertTrue(localIdentityDao.hasIdentity())

        val retrieved = localIdentityDao.getIdentity()
        assertNotNull(retrieved)
        assertEquals(identity, retrieved)

        // Update alias
        localIdentityDao.updateAlias(identity.senderFp, "Alice In Wonderland")
        assertEquals("Alice In Wonderland", localIdentityDao.getIdentity()?.alias)
    }

    @Test
    fun testPeerPinning_keyConflictReturnsConflictResult() = runBlocking {
        val originalPeer = Peer(
            senderFp = 0xABCDEF0123456789L,
            signingPub = ByteArray(65) { 0x0A },
            ecdhPub = ByteArray(65) { 0x0B },
            alias = "Bob",
            verified = true,
            firstSeenMs = 1000L
        )

        // 1. Initial pinning
        val firstResult = peerDao.pinOrVerifyPeer(originalPeer)
        assertTrue(firstResult is PeerPinningResult.Success)
        assertEquals(1, peerDao.count())

        // 2. Same peer presented again -> Unchanged
        val secondResult = peerDao.pinOrVerifyPeer(originalPeer)
        assertTrue(secondResult is PeerPinningResult.Unchanged)
        assertEquals(1, peerDao.count())

        // 3. Attacker presents DIFFERENT signing key for same fingerprint -> KeyConflict!
        val spoofedPeer = Peer(
            senderFp = 0xABCDEF0123456789L,
            signingPub = ByteArray(65) { 0xFF.toByte() }, // Tampered key!
            ecdhPub = ByteArray(65) { 0x0B },
            alias = "Bob (Impostor)",
            verified = false,
            firstSeenMs = 2000L
        )

        val conflictResult = peerDao.pinOrVerifyPeer(spoofedPeer)
        assertTrue("Attempting to alter pinned keys must return KeyConflict", conflictResult is PeerPinningResult.KeyConflict)

        // Ensure database was NOT overwritten
        val currentInDb = peerDao.getPeer(originalPeer.senderFp)
        assertNotNull(currentInDb)
        assertEquals("Bob", currentInDb?.alias)
        assertTrue(originalPeer.signingPub.contentEquals(currentInDb?.signingPub))
    }

    @Test
    fun testRoster_multiGroupIsolation() = runBlocking {
        val group1 = 100L
        val group2 = 200L
        val peerFp = 0x5555L

        val memberGroup1 = RosterMember(
            groupId = group1,
            senderFp = peerFp,
            signingPub = ByteArray(65) { 1 },
            ecdhPub = ByteArray(65) { 2 },
            alias = "Charlie G1",
            revoked = false
        )
        val memberGroup2 = RosterMember(
            groupId = group2,
            senderFp = peerFp,
            signingPub = ByteArray(65) { 1 },
            ecdhPub = ByteArray(65) { 2 },
            alias = "Charlie G2",
            revoked = false
        )

        rosterMemberDao.insertOrUpdate(memberGroup1)
        rosterMemberDao.insertOrUpdate(memberGroup2)

        // Ensure each group maintains its separate membership row
        assertEquals(1, rosterMemberDao.countActiveMembers(group1))
        assertEquals(1, rosterMemberDao.countActiveMembers(group2))
        assertEquals("Charlie G1", rosterMemberDao.getMember(group1, peerFp)?.alias)
        assertEquals("Charlie G2", rosterMemberDao.getMember(group2, peerFp)?.alias)

        // Revoking in group 1 does not affect group 2
        rosterMemberDao.revokeMember(group1, peerFp)
        assertEquals(0, rosterMemberDao.countActiveMembers(group1))
        assertEquals(1, rosterMemberDao.countActiveMembers(group2))
    }

    @Test
    fun testSettings_typedStorage() = runBlocking {
        settingDao.putString("theme", "dark")
        settingDao.putFloat("tau_warn", 0.65f)
        settingDao.putBoolean("block_similar", true)

        assertEquals("dark", settingDao.getString("theme"))
        assertEquals(0.65f, settingDao.getFloat("tau_warn", 0.0f), 0.001f)
        assertTrue(settingDao.getBoolean("block_similar", false))
    }
}
