package com.spotywoop.kt.data

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Port Kotlin de la signature HMAC-SHA256 roulante de SpotiFLAC (desktop.go).
 *
 * Fenêtre temporelle de 5 minutes (300 s) :
 *   rollingKey = HMAC-SHA256(session_secret, "<window>:<session_id>")
 *   signature  = HMAC-SHA256(rollingKey, "<signing_input>")
 *
 * Headers produits :
 *   X-Sig-Session, X-Sig-Timestamp, X-Sig-Nonce,
 *   X-Sig-Body-SHA256, X-Sig-Signature, X-Sig-App-Version, X-Sig-Platform
 */
object CommunitySignature {

    /** Retourne la map de headers à ajouter à la requête POST /api/dl. */
    fun headers(
        method: String,
        path: String,
        body: ByteArray,
        session: CommunitySession,
        appVersion: String = "unknown",
        nowMs: Long = System.currentTimeMillis(),
    ): Map<String, String> {
        val nowSec = nowMs / 1000
        val window = nowSec / 300

        val bodyHash = sha256Hex(body)
        val timestamp = formatTimestamp(nowMs)
        val nonce = randomHex(12)

        val rollingKey = hmacSha256(
            key = session.sessionSecret.toByteArray(),
            message = "$window:${session.sessionId}".toByteArray(),
        )

        val signingInput = listOf(
            "SPOTIFLAC-HMAC-V1", method, path, "", bodyHash,
            timestamp, nonce, session.sessionId, appVersion, "desktop",
        ).joinToString("\n")

        val signature = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(hmacSha256(rollingKey, signingInput.toByteArray()))

        return mapOf(
            "X-Sig-Session" to session.sessionId,
            "X-Sig-Timestamp" to timestamp,
            "X-Sig-Nonce" to nonce,
            "X-Sig-Body-SHA256" to bodyHash,
            "X-Sig-Signature" to signature,
            "X-Sig-App-Version" to appVersion,
            "X-Sig-Platform" to "desktop",
        )
    }

    // ── URLs chiffrées (AES-256-GCM) ─────────────────────────────────────

    private val URL_SEED_PARTS = listOf("spotif", "lac:co", "mmunity:url:v1")
    private val URL_AAD = "spotiflac|community|url|v1".toByteArray()

    private val TIDAL_NONCE = byteArrayOf(
        0x67.b, 0xfc.b, 0xe8.b, 0xc2.b, 0x2e.b, 0x43.b, 0xef.b, 0x00.b,
        0x03.b, 0x8e.b, 0xf7.b, 0x7c.b,
    )
    private val TIDAL_CIPHERTEXT = byteArrayOf(
        0xeb.b, 0x2e.b, 0x2e.b, 0x26.b, 0xbf.b, 0x49.b, 0x8f.b, 0xc7.b,
        0x5e.b, 0x14.b, 0x6c.b, 0xfb.b, 0xd2.b, 0x24.b, 0x07.b, 0xf0.b,
        0x9d.b, 0x17.b, 0x55.b, 0x03.b, 0x1b.b, 0x09.b, 0x20.b, 0x31.b,
        0x71.b, 0xeb.b, 0xf8.b, 0x7c.b, 0x33.b, 0x7d.b,
    )
    private val TIDAL_TAG = byteArrayOf(
        0xa8.b, 0x67.b, 0xc6.b, 0x71.b, 0x4c.b, 0x5c.b, 0x2a.b, 0xfc.b,
        0x4e.b, 0x83.b, 0xfc.b, 0x0b.b, 0x36.b, 0xcc.b, 0x21.b, 0xe9.b,
    )

    private val VERIFY_NONCE = byteArrayOf(
        0x37.b, 0x68.b, 0x07.b, 0x7e.b, 0xe1.b, 0x02.b, 0x94.b, 0xd7.b,
        0x24.b, 0xd7.b, 0xdc.b, 0x54.b,
    )
    private val VERIFY_CIPHERTEXT = byteArrayOf(
        0x01.b, 0x6d.b, 0xb0.b, 0x5f.b, 0x66.b, 0x08.b, 0xab.b, 0x6a.b,
        0x99.b, 0x66.b, 0x5b.b, 0xfc.b, 0x70.b, 0x99.b, 0xe6.b, 0xdb.b,
        0x54.b, 0xa7.b, 0x9e.b, 0x20.b, 0xb9.b, 0x6b.b, 0xd3.b, 0xca.b,
        0x42.b, 0xb4.b, 0xaf.b, 0xc5.b, 0x69.b,
    )
    private val VERIFY_TAG = byteArrayOf(
        0x1d.b, 0x91.b, 0x11.b, 0xce.b, 0xf7.b, 0xe2.b, 0x18.b, 0x76.b,
        0xe0.b, 0x5d.b, 0xb3.b, 0xc5.b, 0xee.b, 0x99.b, 0xe4.b, 0xf2.b,
    )

    /** URL de base du endpoint communautaire Tidal + "/api/dl". */
    fun tidalDlEndpoint(): String =
        decryptUrl(TIDAL_NONCE, TIDAL_CIPHERTEXT, TIDAL_TAG) + "/api/dl"

    /** URL de base du endpoint de vérification communautaire. */
    fun verifyEndpoint(): String =
        decryptUrl(VERIFY_NONCE, VERIFY_CIPHERTEXT, VERIFY_TAG)

    // ── Helpers internes ─────────────────────────────────────────────────

    private fun decryptUrl(nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in URL_SEED_PARTS) digest.update(part.toByteArray())
        val key = SecretKeySpec(digest.digest(), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(URL_AAD)
        val sealed = ciphertext + tag
        return String(cipher.doFinal(sealed))
    }

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun randomHex(bytes: Int): String {
        val arr = ByteArray(bytes)
        java.security.SecureRandom().nextBytes(arr)
        return arr.joinToString("") { "%02x".format(it) }
    }

    private fun formatTimestamp(ms: Long): String {
        val inst = java.time.Instant.ofEpochMilli(ms)
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(inst)
    }

    // Extension pour écrire les octets littéraux sans cast explicite
    private val Int.b get() = toByte()
}
