package com.p2chat.ui.onboarding

import com.p2chat.crypto.invite.QrPayload
import com.p2chat.crypto.invite.QrPayloadCodec
import com.p2chat.crypto.keys.EcProvider
import com.p2chat.crypto.keys.IdentityManager
import com.p2chat.crypto.keys.SoftwareEcProvider
import com.p2chat.data.identity.LocalIdentity
import com.p2chat.data.identity.LocalIdentityDao
import com.p2chat.data.identity.Peer
import com.p2chat.data.identity.PeerDao
import com.p2chat.data.identity.PeerPinningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeLocalIdentityDao : LocalIdentityDao {
        var identity: LocalIdentity? = null

        override suspend fun insertIdentity(identity: LocalIdentity): Long {
            this.identity = identity
            return 1L
        }

        override suspend fun getIdentity(): LocalIdentity? = identity
        override fun observeIdentity(): Flow<LocalIdentity?> = flowOf(identity)

        override suspend fun updateAlias(senderFp: Long, newAlias: String): Int {
            identity = identity?.copy(alias = newAlias)
            return 1
        }

        override suspend fun hasIdentity(): Boolean = identity != null
        override suspend fun clearIdentity(): Int {
            identity = null
            return 1
        }
    }

    private class FakePeerDao : PeerDao {
        val peers = mutableMapOf<Long, Peer>()

        override suspend fun insertRaw(peer: Peer): Long {
            peers[peer.senderFp] = peer
            return 1L
        }

        override suspend fun getPeer(senderFp: Long): Peer? = peers[senderFp]

        override fun observeAllPeers(): Flow<List<Peer>> = flowOf(peers.values.toList())
        override suspend fun getAllPeers(): List<Peer> = peers.values.toList()

        override suspend fun updateVerification(senderFp: Long, verified: Boolean): Int {
            val p = peers[senderFp] ?: return 0
            peers[senderFp] = p.copy(verified = verified)
            return 1
        }

        override suspend fun updateAlias(senderFp: Long, alias: String): Int {
            val p = peers[senderFp] ?: return 0
            peers[senderFp] = p.copy(alias = alias)
            return 1
        }

        override suspend fun deletePeer(senderFp: Long): Int {
            return if (peers.remove(senderFp) != null) 1 else 0
        }

        override suspend fun count(): Int = peers.size
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testAliasValidation_1to32Chars_andRejectionOf33Chars() {
        val fakeIdentityDao = FakeLocalIdentityDao()
        val fakePeerDao = FakePeerDao()
        val identityManager = IdentityManager(fakeIdentityDao)
        val viewModel = OnboardingViewModel(identityManager, fakePeerDao)

        // 1. Empty alias -> invalid
        assertFalse("Empty alias must be invalid", viewModel.validateAlias(""))
        assertNotNull(viewModel.aliasError.value)

        // 2. Exactly 1 char -> valid
        assertTrue("1 character alias must be valid", viewModel.validateAlias("A"))
        assertNull(viewModel.aliasError.value)

        // 3. Exactly 32 chars -> valid
        val valid32 = "A".repeat(32)
        assertTrue("32 character alias must be valid", viewModel.validateAlias(valid32))
        assertNull(viewModel.aliasError.value)

        // 4. 33 chars -> rejected (PRD ID-3, T1.14 bar)
        val invalid33 = "A".repeat(33)
        assertFalse("33 character alias must be rejected", viewModel.validateAlias(invalid33))
        assertEquals("Alias cannot exceed 32 characters", viewModel.aliasError.value)
    }

    @Test
    fun testIdentityGeneration_success_createsFingerprintAndQr() = runTest(testDispatcher) {
        val fakeIdentityDao = FakeLocalIdentityDao()
        val fakePeerDao = FakePeerDao()
        val identityManager = IdentityManager(fakeIdentityDao)
        val viewModel = OnboardingViewModel(identityManager, fakePeerDao)

        viewModel.setAlias("TestNode")
        viewModel.generateIdentity()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("UI state must be Created after generation", state is OnboardingUiState.Created)
        val created = state as OnboardingUiState.Created

        assertEquals("TestNode", created.identity.alias)
        assertNotNull(created.identity.signingPub)
        assertNotNull(created.identity.ecdhPub)

        // Fingerprint format check (e.g. A4F9:82C1:09E3:77B2)
        val expectedHex = EcProvider.senderFpHex(created.identity.senderFp).uppercase().chunked(4).joinToString(":")
        assertEquals("Formatted fingerprint must match expected hex", expectedHex, created.formattedFingerprint)

        // QR code payload round-trip check
        val decodedQrResult = QrPayloadCodec.decodeFromBase64(created.qrBase64)
        assertTrue("QR payload must decode cleanly", decodedQrResult.isSuccess)
        val qrPayload = decodedQrResult.getOrThrow()
        assertEquals("TestNode", qrPayload.alias)
        assertTrue(created.identity.signingPub.contentEquals(qrPayload.signingPub))
        assertTrue(created.identity.ecdhPub.contentEquals(qrPayload.ecdhPub))
    }

    @Test
    fun testRelaunch_withExistingIdentity_restoresCreatedStateImmediately() = runTest(testDispatcher) {
        val fakeIdentityDao = FakeLocalIdentityDao()
        val fakePeerDao = FakePeerDao()
        val identityManager = IdentityManager(fakeIdentityDao)

        // Pre-create identity
        identityManager.getOrCreateIdentity("PersistentUser")

        // Instantiate new ViewModel as if app relaunched
        val viewModel = OnboardingViewModel(identityManager, fakePeerDao)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Relaunch must skip Idle and immediately restore Created state", state is OnboardingUiState.Created)
        assertEquals("PersistentUser", (state as OnboardingUiState.Created).identity.alias)
    }

    @Test
    fun testQrScanning_pinsPeerAndDetectsKeyConflict() = runTest(testDispatcher) {
        val fakeIdentityDao = FakeLocalIdentityDao()
        val fakePeerDao = FakePeerDao()
        val identityManager = IdentityManager(fakeIdentityDao)
        val viewModel = OnboardingViewModel(identityManager, fakePeerDao)

        viewModel.setAlias("LocalUser")
        viewModel.generateIdentity()
        advanceUntilIdle()

        // Generate peer's identity and QR payload
        val peerSigningKp = SoftwareEcProvider.generateKeyPair()
        val peerEcdhKp = SoftwareEcProvider.generateKeyPair()
        val peerSigningPub = EcProvider.encodeUncompressedPublicKey(peerSigningKp.public)
        val peerEcdhPub = EcProvider.encodeUncompressedPublicKey(peerEcdhKp.public)

        val peerQrPayload = QrPayload(
            signingPub = peerSigningPub,
            ecdhPub = peerEcdhPub,
            alias = "Alice"
        )
        val peerQrBase64 = QrPayloadCodec.encodeToBase64(peerQrPayload)

        // 1. Scan peer QR for first time (TOFU)
        viewModel.processScannedQr(peerQrBase64)
        advanceUntilIdle()

        var state = viewModel.uiState.value as OnboardingUiState.Created
        assertNotNull("Scanned peer must be attached to state", state.scannedPeer)
        assertEquals("Alice", state.scannedPeer!!.alias)
        assertTrue("Peer must be marked verified after QR scan", state.scannedPeer!!.verified)
        assertNull("No conflict warning on first pin", state.keyConflictWarning)

        // 2. Scan same peer with CHANGED keys (simulating impersonation / MITM)
        val forgedSigningKp = SoftwareEcProvider.generateKeyPair()
        val forgedSigningPub = EcProvider.encodeUncompressedPublicKey(forgedSigningKp.public)
        val changedQrPayload = QrPayload(
            signingPub = peerSigningPub, // Same declared fingerprint...
            ecdhPub = peerSigningPub,    // but modified key bytes
            alias = "Alice_Impostor"
        )
        // Note: in PeerPinning, senderFp is derived from signingPub.
        // If an attacker uses the same senderFp with a different signing key:
        val changedPeer = Peer(
            senderFp = state.scannedPeer!!.senderFp,
            signingPub = forgedSigningPub,
            ecdhPub = peerEcdhPub,
            alias = "Alice",
            verified = true,
            firstSeenMs = System.currentTimeMillis()
        )
        // Trigger key conflict in DAO
        val conflictResult = fakePeerDao.pinOrVerifyPeer(changedPeer)
        assertTrue("Must detect key conflict", conflictResult is PeerPinningResult.KeyConflict)
    }

    @Test
    fun testRandomAlias_generatesValidAliasWithin32Chars() {
        val fakeIdentityDao = FakeLocalIdentityDao()
        val fakePeerDao = FakePeerDao()
        val identityManager = IdentityManager(fakeIdentityDao)
        val viewModel = OnboardingViewModel(identityManager, fakePeerDao)

        for (i in 1..20) {
            viewModel.generateRandomAlias()
            val alias = viewModel.alias.value
            assertTrue("Random alias must be non-empty", alias.isNotEmpty())
            assertTrue("Random alias must be <= 32 chars", alias.length <= 32)
            assertNull("Random alias must have no error", viewModel.aliasError.value)
        }
    }
}
