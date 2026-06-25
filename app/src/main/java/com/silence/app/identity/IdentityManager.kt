package com.silence.app.identity

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.subtle.X25519
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Long-term identity for E2EE call authentication.
 *
 * Generates an X25519 keypair on first launch. The public key acts as the
 * user's identity. Contacts are other public keys — exchanged via QR code.
 *
 * Call verification uses a Short Authentication String (sas()) derived from
 * both parties' identity keys: it confirms the remote identity (the key
 * exchanged via QR). It does not by itself detect an active DTLS media-path
 * relay; that would require binding the DTLS certificate to this identity.
 */
@Singleton
class IdentityManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("silence_identity", Context.MODE_PRIVATE)

    private val _identity = MutableStateFlow<Identity?>(null)
    val identity: Flow<Identity?> = _identity.asStateFlow()

    data class Identity(
        val publicKey: ByteArray,  // 32 bytes, X25519
        val privateKey: ByteArray, // 32 bytes, X25519
        val fingerprint: String    // hex-encoded SHA-256 of public key, 16 chars
    ) {
        val publicKeyB64: String get() = Base64.encodeToString(publicKey, Base64.NO_WRAP)

        /** The QR-code payload that another user scans to add this identity. */
        val qrPayload: String get() = "silence://$publicKeyB64"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Identity) return false
            return publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int = publicKey.contentHashCode()
    }

    init {
        loadOrCreate()
    }

    private fun loadOrCreate() {
        val storedPub = prefs.getString(KEY_PUBLIC, null)
        val storedPriv = prefs.getString(KEY_PRIVATE, null)

        if (storedPub != null && storedPriv != null) {
            val pub = Base64.decode(storedPub, Base64.NO_WRAP)
            val priv = Base64.decode(storedPriv, Base64.NO_WRAP)
            _identity.value = Identity(pub, priv, fingerprint(pub))
        } else {
            val priv = ByteArray(32)
            SecureRandom().nextBytes(priv)
            val pub = X25519.publicFromPrivate(priv)
            prefs.edit()
                .putString(KEY_PUBLIC, Base64.encodeToString(pub, Base64.NO_WRAP))
                .putString(KEY_PRIVATE, Base64.encodeToString(priv, Base64.NO_WRAP))
                .apply()
            _identity.value = Identity(pub, priv, fingerprint(pub))
        }
    }

    fun fingerprint(pubKey: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(pubKey)
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Symmetric Short Authentication String for two X25519 identities.
     *
     * SHA-256 over the sorted concatenation of both identity public keys (base64),
     * so both ends compute the identical string — valid for manual comparison.
     * Stable across calls (identity keys are long-term), unlike the per-call
     * DTLS-SRTP certificate fingerprint.
     */
    fun sas(remotePubKeyB64: String): String {
        val localB64 = _identity.value?.publicKeyB64 ?: return ""
        if (remotePubKeyB64.isEmpty()) return ""
        val (a, b) = if (localB64 <= remotePubKeyB64) localB64 to remotePubKeyB64 else remotePubKeyB64 to localB64
        val hash = MessageDigest.getInstance("SHA-256").digest((a + b).toByteArray(Charsets.UTF_8))
        return hash.take(16).joinToString("") { "%02x".format(it) } // 128-bit, 32 hex chars
    }

    companion object {
        private const val KEY_PUBLIC = "id_pub"
        private const val KEY_PRIVATE = "id_priv"
    }
}
