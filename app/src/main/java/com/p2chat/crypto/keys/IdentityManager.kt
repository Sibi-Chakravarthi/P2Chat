package com.p2chat.crypto.keys

import com.p2chat.data.identity.LocalIdentity
import com.p2chat.data.identity.LocalIdentityDao
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey

/**
 * IdentityManager responsible for generating, persisting, and unlocking local cryptographic keys.
 * (T1.4, ID-1, ID-2, SEC-1).
 */
class IdentityManager(
    private val localIdentityDao: LocalIdentityDao,
    private val keyVault: KeyVault = KeyVault(),
    private val ecProvider: EcProvider = EcProvider.get()
) {

    /**
     * Retrieves the existing LocalIdentity or generates a fresh one if none exists.
     */
    suspend fun getOrCreateIdentity(alias: String): LocalIdentity {
        val existing = localIdentityDao.getIdentity()
        if (existing != null) {
            return existing
        }

        // Generate separate signing (ECDSA) and encryption (ECDH) P-256 keypairs
        val signingKeyPair = ecProvider.generateKeyPair()
        val ecdhKeyPair = ecProvider.generateKeyPair()

        val signingPubBytes = EcProvider.encodeUncompressedPublicKey(signingKeyPair.public)
        val ecdhPubBytes = EcProvider.encodeUncompressedPublicKey(ecdhKeyPair.public)

        val senderFp = EcProvider.computeSenderFp(signingPubBytes)

        val wrappedSigningPriv = keyVault.wrapPrivateKey(signingKeyPair.private)
        val wrappedEcdhPriv = keyVault.wrapPrivateKey(ecdhKeyPair.private)

        val identity = LocalIdentity(
            senderFp = senderFp,
            alias = alias,
            signingPub = signingPubBytes,
            ecdhPub = ecdhPubBytes,
            wrappedSigningPriv = wrappedSigningPriv,
            wrappedEcdhPriv = wrappedEcdhPriv
        )

        localIdentityDao.insertIdentity(identity)
        return identity
    }

    suspend fun getIdentity(): LocalIdentity? {
        return localIdentityDao.getIdentity()
    }

    suspend fun hasIdentity(): Boolean {
        return localIdentityDao.hasIdentity()
    }

    suspend fun updateAlias(newAlias: String) {
        val identity = getIdentity() ?: return
        localIdentityDao.updateAlias(identity.senderFp, newAlias)
    }

    fun unwrapSigningPrivateKey(identity: LocalIdentity): PrivateKey {
        return keyVault.unwrapPrivateKey(identity.wrappedSigningPriv)
    }

    fun unwrapEcdhPrivateKey(identity: LocalIdentity): PrivateKey {
        return keyVault.unwrapPrivateKey(identity.wrappedEcdhPriv)
    }

    fun getSigningPublicKey(identity: LocalIdentity): PublicKey {
        return EcProvider.decodeUncompressedPublicKey(identity.signingPub)
    }

    fun getEcdhPublicKey(identity: LocalIdentity): PublicKey {
        return EcProvider.decodeUncompressedPublicKey(identity.ecdhPub)
    }

    fun getSigningKeyPair(identity: LocalIdentity): KeyPair {
        return KeyPair(getSigningPublicKey(identity), unwrapSigningPrivateKey(identity))
    }

    fun getEcdhKeyPair(identity: LocalIdentity): KeyPair {
        return KeyPair(getEcdhPublicKey(identity), unwrapEcdhPrivateKey(identity))
    }
}
