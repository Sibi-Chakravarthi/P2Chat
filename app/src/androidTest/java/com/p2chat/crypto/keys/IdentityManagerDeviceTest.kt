package com.p2chat.crypto.keys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.p2chat.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec

@RunWith(AndroidJUnit4::class)
class IdentityManagerDeviceTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var identityManager: IdentityManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.createInMemory(context)
        identityManager = IdentityManager(db.localIdentityDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun testIdentityCreation_andStoredKeyCannotBeParsedDirectly() = runBlocking {
        val identity = identityManager.getOrCreateIdentity("Alice")
        assertNotNull(identity)
        assertEquals("Alice", identity.alias)

        // Confirm wrapped private key cannot be parsed as raw PKCS8 EC key without unwrapping
        val kf = KeyFactory.getInstance("EC")
        try {
            kf.generatePrivate(PKCS8EncodedKeySpec(identity.wrappedSigningPriv))
            fail("Raw wrapped bytes must NOT parse as valid EC private key!")
        } catch (e: Exception) {
            // Success: wrapped key is encrypted ciphertext
        }

        // Unwrapping works
        val signingPriv = identityManager.unwrapSigningPrivateKey(identity)
        assertNotNull(signingPriv)
        assertEquals("EC", signingPriv.algorithm)

        // Loading identity returns the exact same identity
        val loaded = identityManager.getIdentity()
        assertNotNull(loaded)
        assertEquals(identity.senderFp, loaded!!.senderFp)
        assertTrue(identity.signingPub.contentEquals(loaded.signingPub))
    }

    @Test
    fun testBothProviderPathsProduceSameSecret() {
        val softwarePairA = SoftwareEcProvider.generateKeyPair()
        val softwarePairB = SoftwareEcProvider.generateKeyPair()

        val secret1 = SoftwareEcProvider.deriveSharedSecret(softwarePairA.private, softwarePairB.public)
        val secret2 = AndroidKeystoreEcProvider.deriveSharedSecret(softwarePairA.private, softwarePairB.public)

        assertTrue("Both provider paths must produce identical shared secret", secret1.contentEquals(secret2))
    }
}
