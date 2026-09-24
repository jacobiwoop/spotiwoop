package com.spotywoop.kt.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Résolution Songlink → Tidal track ID.
 *
 * Port de songLinkTidalURL + tidalTrackID (desktop.go).
 * GET https://song.link/s/<spotifyId> → parse __NEXT_DATA__ JSON → ID Tidal numérique.
 */
object SonglinkClient {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    private val NEXT_DATA_RE = Regex(
        """<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val TIDAL_ID_RE = Regex("""/track/(\d+)""")

    /**
     * Retourne l'ID Tidal numérique pour un track Spotify.
     * Lève IOException si Songlink ne trouve pas de correspondance Tidal.
     */
    fun tidalId(spotifyTrackId: String): Long {
        val request = Request.Builder()
            .url("https://song.link/s/$spotifyTrackId")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val html = http.newCall(request).execute().use { resp ->
            if (resp.code != 200) throw IOException("Songlink HTTP ${resp.code}")
            resp.body!!.string()
        }

        val json = NEXT_DATA_RE.find(html)?.groupValues?.get(1)
            ?: throw IOException("Songlink: __NEXT_DATA__ introuvable")

        // Cherche toutes les URLs Tidal dans le JSON brut
        val tidalUrl = TIDAL_ID_RE.find(json)?.value
            ?: findTidalUrlInJson(json)
            ?: throw IOException("Tidal: aucune URL trouvée dans Songlink")

        val match = TIDAL_ID_RE.find(tidalUrl)
            ?: throw IOException("Tidal: impossible d'extraire l'ID depuis $tidalUrl")

        return match.groupValues[1].toLong()
    }

    /** Cherche spécifiquement une entrée platform=="tidal" dans le JSON. */
    private fun findTidalUrlInJson(json: String): String? {
        // Regex simple pour extraire l'URL Tidal depuis les sections links du JSON
        val platformPattern = Regex(""""platform"\s*:\s*"tidal"[^}]*"url"\s*:\s*"([^"]+)"""")
        val reversePattern = Regex(""""url"\s*:\s*"([^"]+)"[^}]*"platform"\s*:\s*"tidal"""")
        return (platformPattern.find(json) ?: reversePattern.find(json))?.groupValues?.get(1)
    }
}
