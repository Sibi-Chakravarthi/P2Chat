package com.p2chat.ui.onboarding

import com.p2chat.data.identity.LocalIdentity
import com.p2chat.data.identity.Peer

sealed class OnboardingUiState {
    object Idle : OnboardingUiState()
    object Loading : OnboardingUiState()

    data class Created(
        val identity: LocalIdentity,
        val formattedFingerprint: String,
        val qrBase64: String,
        val scannedPeer: Peer? = null,
        val keyConflictWarning: String? = null,
        val joinedGroupName: String? = null
    ) : OnboardingUiState() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Created

            if (identity != other.identity) return false
            if (formattedFingerprint != other.formattedFingerprint) return false
            if (qrBase64 != other.qrBase64) return false
            if (scannedPeer != other.scannedPeer) return false
            if (keyConflictWarning != other.keyConflictWarning) return false
            if (joinedGroupName != other.joinedGroupName) return false

            return true
        }

        override fun hashCode(): Int {
            var result = identity.hashCode()
            result = 31 * result + formattedFingerprint.hashCode()
            result = 31 * result + qrBase64.hashCode()
            result = 31 * result + (scannedPeer?.hashCode() ?: 0)
            result = 31 * result + (keyConflictWarning?.hashCode() ?: 0)
            result = 31 * result + (joinedGroupName?.hashCode() ?: 0)
            return result
        }
    }

    data class Error(val message: String) : OnboardingUiState()
}
