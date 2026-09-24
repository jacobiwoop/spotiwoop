package com.spotywoop.kt.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit

/**
 * Client Spotify metadata pour récupérer l'ISRC d'un track.
 * Réutilise le SpotifyClient partagé pour bénéficier de la session déjà initialisée.
 */
class SpotifyMetadataClient(private val spotifyClient: SpotifyClient = SpotifyClient()) {

    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    fun trackIsrc(spotifyTrackId: String): String {
        val gid = base62ToGid(spotifyTrackId.trim())
        val accessToken = runBlocking { spotifyClient.getValidAccessToken() }
        return fetchIsrc(gid, accessToken)
    }

    private fun fetchIsrc(gid: String, accessToken: String): String {
        val url = "https://spclient.wg.spotify.com/metadata/4/track/$gid?market=from_token"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()

        val body = http.newCall(req).execute().use { resp ->
            if (resp.code != 200) throw IOException("Spotify metadata: HTTP ${resp.code}")
            resp.body!!.string()
        }

        val arr = JSONObject(body).optJSONArray("external_id")
            ?: throw IOException("Spotify metadata: pas d'external_id")
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if (item.optString("type").equals("isrc", ignoreCase = true)) {
                return item.optString("id").uppercase().trim()
                    .ifBlank { throw IOException("ISRC vide") }
            }
        }
        throw IOException("ISRC non trouvé pour GID $gid")
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

        fun base62ToGid(trackId: String): String {
            val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            var value = BigInteger.ZERO
            for (c in trackId) {
                val idx = alphabet.indexOf(c)
                require(idx >= 0) { "Caractère base62 invalide : $c" }
                value = value.multiply(BigInteger.valueOf(62)).add(BigInteger.valueOf(idx.toLong()))
            }
            return value.toString(16).padStart(32, '0')
        }
    }
}
