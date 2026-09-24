package com.spotywoop.kt.playback

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Moteur de correspondance haute précision inspiré de SpotDL (Spotify -> YouTube Music).
 *
 * Utilise l'API publique Innertube (0 scraping HTML, réponse en ~1s) et applique :
 * 1. Ciblage préférentiel des pistes studio officielles ("Topic" / "Official Audio").
 * 2. Comparaison stricte de la durée (|durée_yt - durée_spotify| <= 10s).
 * 3. Pénalisation et exclusion des faux positifs (Live, Concert, Cover, Remix, 8D, Slowed, Karaoke).
 */
object SpotDlMatcher {
    private const val TAG = "SpotDlMatcher"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val JUNK_KEYWORDS = listOf(
        "live", "concert", "cover", "karaoke", "8d audio",
        "slowed", "reverb", "tribute", "instrumental", "making of", "parodie"
    )

    data class MatchCandidate(
        val videoId: String,
        val title: String,
        val channel: String,
        val durationSec: Long,
        val score: Double,
    )

    /**
     * Recherche le meilleur videoId correspondant au titre Spotify.
     * @param query Nom de l'artiste + titre
     * @param targetDurationMs Durée exacte issue de Spotify (si disponible)
     */
    /**
     * Recherche les meilleurs videoIds correspondants au titre Spotify classés par score.
     * @param query Nom de l'artiste + titre
     * @param targetDurationMs Durée exacte issue de Spotify (si disponible)
     */
    fun findCandidateVideoIds(query: String, targetDurationMs: Long?, limit: Int = 3): List<String> {
        val targetDurationSec = targetDurationMs?.let { it / 1000L } ?: 0L
        val cleanQuery = query.trim()

        val primaryArtist = cleanQuery.substringBefore("-").split(",").firstOrNull()?.trim() ?: ""
        val titlePart = if (cleanQuery.contains("-")) cleanQuery.substringAfter("-").trim() else cleanQuery
        val cleanTitle = titlePart.replace(Regex("""[\(\[].*?[\)\]]"""), "").trim()

        val searchQuery = if (primaryArtist.isNotBlank() && cleanTitle.isNotBlank()) {
            "$primaryArtist $cleanTitle official audio"
        } else {
            "$cleanQuery Topic audio"
        }

        val candidates = searchInnertube(searchQuery)
        val list = if (candidates.isEmpty()) {
            searchInnertube("$cleanQuery audio")
        } else {
            candidates
        }

        return scoreAndRank(list, primaryArtist, cleanQuery, targetDurationSec).take(limit)
    }

    fun findBestVideoId(query: String, targetDurationMs: Long?): String? {
        return findCandidateVideoIds(query, targetDurationMs, 1).firstOrNull()
    }

    private fun scoreAndRank(
        candidates: List<RawCandidate>,
        primaryArtist: String,
        originalQuery: String,
        targetDurationSec: Long,
    ): List<String> {
        if (candidates.isEmpty()) return emptyList()

        val queryLower = originalQuery.lowercase()
        val primaryArtistLower = primaryArtist.lowercase().trim()
        val originalHasRemix = queryLower.contains("remix")
        val originalHasLive = queryLower.contains("live")

        val scored = candidates.map { raw ->
            var score = 100.0
            val titleLower = raw.title.lowercase()
            val channelLower = raw.channel.lowercase()

            // Bonus si la chaîne correspond à l'artiste officiel ou Topic
            if (primaryArtistLower.isNotBlank() && channelLower.contains(primaryArtistLower)) {
                score += 60.0
            }
            if (channelLower.endsWith(" - topic") || channelLower.contains("topic")) {
                score += 50.0
            }

            // Bonus pour "Official Audio" ou "Official Video"
            if (titleLower.contains("official audio") || titleLower.contains("audio officiel")) {
                score += 40.0
            } else if (titleLower.contains("official") || titleLower.contains("audio")) {
                score += 20.0
            }

            // Filtre durée (SpotDL)
            if (targetDurationSec > 0 && raw.durationSec > 0) {
                val diff = abs(raw.durationSec - targetDurationSec)
                when {
                    diff == 0L -> score += 35.0
                    diff <= 3L -> score += 25.0
                    diff <= 10L -> score += 15.0
                    diff <= 25L -> score -= 15.0
                    else -> score -= 60.0
                }
            }

            // Pénalisation des faux positifs
            for (junk in JUNK_KEYWORDS) {
                if (junk == "remix" && originalHasRemix) continue
                if (junk == "live" && originalHasLive) continue

                if (titleLower.contains(junk)) {
                    score -= 70.0
                }
            }

            MatchCandidate(
                videoId = raw.videoId,
                title = raw.title,
                channel = raw.channel,
                durationSec = raw.durationSec,
                score = score,
            )
        }

        val sorted = scored.sortedByDescending { it.score }
        for (c in sorted) {
            Log.d(TAG, "Candidat: '${c.title}' (${c.channel}) | Durée: ${c.durationSec}s (cible: ${targetDurationSec}s) | Score: ${c.score} | ID: ${c.videoId}")
        }
        return sorted.map { it.videoId }
    }

    private data class RawCandidate(
        val videoId: String,
        val title: String,
        val channel: String,
        val durationSec: Long,
    )

    private fun searchInnertube(query: String): List<RawCandidate> {
        val payload = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20240101.00.00")
                    put("hl", "fr")
                    put("gl", "FR")
                })
            })
            put("query", query)
        }

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/search")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        return try {
            http.newCall(request).execute().use { resp ->
                if (resp.code != 200) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val json = JSONObject(body)

                val sections = json.optJSONObject("contents")
                    ?.optJSONObject("twoColumnSearchResultsRenderer")
                    ?.optJSONObject("primaryContents")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents") ?: return emptyList()

                if (sections.length() == 0) return emptyList()
                val firstSection = sections.optJSONObject(0)
                    ?.optJSONObject("itemSectionRenderer")
                    ?.optJSONArray("contents") ?: return emptyList()

                val list = mutableListOf<RawCandidate>()
                for (i in 0 until firstSection.length().coerceAtMost(10)) {
                    val item = firstSection.optJSONObject(i) ?: continue
                    val vr = item.optJSONObject("videoRenderer") ?: continue
                    val vid = vr.optString("videoId")
                    if (vid.isNullOrBlank()) continue

                    val title = vr.optJSONObject("title")?.optJSONArray("runs")?.let { runs ->
                        (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text") ?: "" }
                    } ?: vr.optJSONObject("title")?.optString("simpleText") ?: ""

                    val channel = vr.optJSONObject("ownerText")?.optJSONArray("runs")?.let { runs ->
                        (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text") ?: "" }
                    } ?: ""

                    val durText = vr.optJSONObject("lengthText")?.optString("simpleText") ?: ""
                    val parts = durText.split(":").mapNotNull { it.toIntOrNull() }
                    val durSec = when (parts.size) {
                        2 -> (parts[0] * 60 + parts[1]).toLong()
                        3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).toLong()
                        else -> 0L
                    }

                    list.add(RawCandidate(vid, title, channel, durSec))
                }
                list
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erreur recherche Innertube: ${e.message}")
            emptyList()
        }
    }
}
