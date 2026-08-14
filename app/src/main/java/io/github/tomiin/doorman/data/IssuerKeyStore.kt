package io.github.tomiin.doorman.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

// The 32 bytes behind Doorman's `localSecretKey()` witness.
//
// The contract derives every identity from this: an issuer's tag is
// issuerId(sk), a holder's id is holderId(sk), a venue pseudonym is
// patronId(sk, venue). It is the one value that must never leave the device,
// so it is generated here, stored encrypted at rest, and handed to the
// circuit as a private witness — never as a circuit argument, which would
// put it on-chain.
//
// Generated once per install. Losing it means losing the identity, which is
// the correct behaviour for a demo; a production build would derive it from
// the sigil seed so it survives a reinstall and syncs across devices.
@Singleton
class IssuerKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "doorman-identity-prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private companion object {
        const val KEY = "doorman.localSecretKey"
    }

    /** The device's secret key, minted on first use. */
    fun secretKey(): ByteArray {
        prefs.getString(KEY, null)?.let { return it.hexToBytes() }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY, fresh.toHex()).apply()
        return fresh
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
