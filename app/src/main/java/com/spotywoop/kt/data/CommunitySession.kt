package com.spotywoop.kt.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Session communautaire SpotiFLAC.
 * Forme identique au fichier JSON produit par le port Go :
 *   { install_id, session_id, session_secret, expires_at }
 *
 * Le secret n'est jamais loggé. Il est stocké chiffré dans le Keystore Android.
 */
data class CommunitySession(
    val installId: String,
    val sessionId: String,
    val sessionSecret: String,
    val expiresAt: String,
) {
    fun isValid(): Boolean {
        if (sessionId.isBlank() || sessionSecret.isBlank()) return false
        return try {
            val exp = java.time.Instant.parse(expiresAt)
            exp.isAfter(java.time.Instant.now().plusSeconds(60))
        } catch (_: Exception) {
            false
        }
    }
}

/** État de la session exposé à l'UI. */
enum class SessionState { MISSING, VALID, EXPIRED }

/** Stockage chiffré de la session communautaire. */
object CommunitySessionStore {
    private const val PREFS_FILE = "spotiflac_community"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_SESSION_SECRET = "session_secret"
    private const val KEY_EXPIRES_AT = "expires_at"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(context: Context): CommunitySession? {
        val p = runCatching { prefs(context) }.getOrNull() ?: return null
        val id = p.getString(KEY_SESSION_ID, null) ?: return null
        val secret = p.getString(KEY_SESSION_SECRET, null) ?: return null
        return CommunitySession(
            installId = p.getString(KEY_INSTALL_ID, "") ?: "",
            sessionId = id,
            sessionSecret = secret,
            expiresAt = p.getString(KEY_EXPIRES_AT, "") ?: "",
        )
    }

    fun save(context: Context, session: CommunitySession) {
        prefs(context).edit()
            .putString(KEY_INSTALL_ID, session.installId)
            .putString(KEY_SESSION_ID, session.sessionId)
            .putString(KEY_SESSION_SECRET, session.sessionSecret)
            .putString(KEY_EXPIRES_AT, session.expiresAt)
            .apply()
    }

    fun clear(context: Context) {
        runCatching { prefs(context).edit().clear().apply() }
    }

    fun sessionState(context: Context): SessionState {
        val s = load(context) ?: return SessionState.MISSING
        return if (s.isValid()) SessionState.VALID else SessionState.EXPIRED
    }
}
