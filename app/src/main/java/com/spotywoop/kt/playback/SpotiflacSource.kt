package com.spotywoop.kt.playback

import android.content.Context
import com.spotywoop.kt.data.CommunitySessionStore
import com.spotywoop.kt.data.CommunitySignature
import com.spotywoop.kt.data.SonglinkClient
import com.spotywoop.kt.data.SpotifyMetadataClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Source audio SpotiFLAC — chaîne complète en Kotlin, sans backend.
 *
 * Spotify ID
 *   → ISRC (spclient metadata, token anonyme)
 *   → Tidal ID (Songlink)
 *   → /api/dl signé HMAC-SHA256 roulant
 *   → MANIFEST:<base64>
 *   → data:application/dash+xml;base64,<base64>   ← ExoPlayer DASH natif
 *
 * Si la session est manquante ou expirée, lève IOException
 * (l'UI doit afficher l'écran Paramètres pour relancer la vérification).
 */
class SpotiflacSource(
    private val context: Context,
    private val appVersion: String = "7.2.2",
) : StreamSource {
    override val name = "SpotiFLAC FLAC"

    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val metaClient = SpotifyMetadataClient()

    override fun resolve(spotifyTrackId: String): ResolvedStream {
        // 1. Session communautaire
        val session = CommunitySessionStore.load(context)
            ?: throw IOException("Session SpotiFLAC manquante — ouvrez ⚙ Paramètres pour vous connecter")
        if (!session.isValid())
            throw IOException("Session SpotiFLAC expirée — ouvrez ⚙ Paramètres pour vous reconnecter")

        // 2. ISRC (thread IO — on est dans ResolvingDataSource)
        val isrc = metaClient.trackIsrc(spotifyTrackId)

        // 3. Tidal ID via Songlink
        val tidalId = SonglinkClient.tidalId(spotifyTrackId)

        // 4. Payload /api/dl
        val payloadBytes = """{"id":"$tidalId","quality":"16"}""".toByteArray()

        // 5. Endpoint (URL déchiffrée AES-GCM)
        val endpoint = CommunitySignature.tidalDlEndpoint()

        // 6. Headers HMAC
        val sigHeaders = CommunitySignature.headers(
            method = "POST",
            path = "/api/dl",
            body = payloadBytes,
            session = session,
            appVersion = appVersion,
        )

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(payloadBytes.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .header("User-Agent", "SpotiFLAC")
        for ((k, v) in sigHeaders) reqBuilder.header(k, v)

        val responseBody = http.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.code != 200)
                throw IOException("SpotiFLAC /api/dl HTTP ${resp.code}")
            resp.body!!.string()
        }

        // 7. Parse la réponse
        val urlField = JSONObject(responseBody).optString("url")
            .ifBlank { throw IOException("SpotiFLAC: champ url absent") }

        val audioUrl = when {
            urlField.startsWith("MANIFEST:") -> {
                val b64 = urlField.removePrefix("MANIFEST:")
                "data:application/dash+xml;base64,$b64"
            }
            urlField.startsWith("http") -> urlField
            else -> throw IOException("SpotiFLAC: format d'URL inconnu : ${urlField.take(40)}")
        }

        return ResolvedStream(
            url = audioUrl,
            source = name,
            quality = "FLAC · DASH",
        )
    }
}
