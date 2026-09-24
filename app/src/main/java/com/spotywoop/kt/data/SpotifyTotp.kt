package com.spotywoop.kt.data

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TOTP attendu par open.spotify.com/api/token (RFC 6238 : SHA1, 6 chiffres, pas de 30 s).
 * Le secret et sa version suivent ceux de SpotiFLAC ; quand Spotify les fait tourner,
 * la récupération du token échoue jusqu'à mise à jour de ces deux constantes.
 */
object SpotifyTotp {
    private const val SECRET =
        "GM3TMMJTGYZTQNZVGM4DINJZHA4TGOBYGMZTCMRTGEYDSMJRHE4TEOBUG4YTCMRUGQ4DQOJUGQYTAMRRGA2TCMJSHE3TCMBY"
    const val VERSION = 61

    private val key: ByteArray by lazy { base32Decode(SECRET) }

    fun generate(timeMillis: Long = System.currentTimeMillis()): String {
        val counter = timeMillis / 1000 / 30
        val message = ByteArray(8) { i -> (counter ushr (8 * (7 - i))).toByte() }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val hash = mac.doFinal(message)
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    internal fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = input.trimEnd('=').uppercase()
        val out = ArrayList<Byte>(clean.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val value = alphabet.indexOf(c)
            require(value >= 0) { "caractère base32 invalide : $c" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add((buffer shr bits and 0xff).toByte())
            }
        }
        return out.toByteArray()
    }
}
