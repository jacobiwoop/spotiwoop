package com.spotywoop.kt.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Client natif YouTube Radio (Innertube direct) en pur Kotlin.
 *
 * 1. Recherche du videoId via l'endpoint public Innertube /search
 * 2. Génération de la playlist YouTube Mix via /next (RD{videoId})
 * 3. Retourne 25 morceaux similaires avec title, artist, cover et id direct.
 */
object YouTubeRadioClient {

    private val http = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val radioCache = ConcurrentHashMap<String, List<TrackResult>>()

    suspend fun fetchRadioTracks(query: String, limit: Int = 25): List<TrackResult> =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim()
            if (cleanQuery.isBlank()) return@withContext emptyList()

            radioCache[cleanQuery]?.let { return@withContext it }

            try {
                // Étape 1 : Récupérer le videoId pour initialiser la radio
                val videoId = searchVideoId(cleanQuery) ?: return@withContext emptyList()

                // Étape 2 : Récupérer le YouTube Mix RD{videoId}
                val tracks = fetchMixPlaylist(videoId, limit)
                if (tracks.isNotEmpty()) {
                    radioCache[cleanQuery] = tracks
                }
                tracks
            } catch (e: Exception) {
                android.util.Log.e("YouTubeRadioClient", "Erreur radio YouTube: ${e.message}", e)
                emptyList()
            }
        }

    private fun searchVideoId(query: String): String? {
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

        return http.newCall(request).execute().use { resp ->
            if (resp.code != 200) return null
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)

            val sections = json.optJSONObject("contents")
                ?.optJSONObject("twoColumnSearchResultsRenderer")
                ?.optJSONObject("primaryContents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: return null

            if (sections.length() == 0) return null
            val firstSection = sections.optJSONObject(0)
                ?.optJSONObject("itemSectionRenderer")
                ?.optJSONArray("contents") ?: return null

            for (i in 0 until firstSection.length()) {
                val item = firstSection.optJSONObject(i) ?: continue
                val vr = item.optJSONObject("videoRenderer")
                val vid = vr?.optString("videoId")
                if (!vid.isNullOrBlank()) {
                    return vid
                }
            }
            null
        }
    }

    private fun fetchMixPlaylist(videoId: String, limit: Int): List<TrackResult> {
        val payload = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20240101.00.00")
                    put("hl", "fr")
                    put("gl", "FR")
                })
            })
            put("videoId", videoId)
            put("playlistId", "RD$videoId")
        }

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/next")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        return http.newCall(request).execute().use { resp ->
            if (resp.code != 200) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val json = JSONObject(body)

            val contents = json.optJSONObject("contents")
                ?.optJSONObject("twoColumnWatchNextResults")
                ?.optJSONObject("playlist")
                ?.optJSONObject("playlist")
                ?.optJSONArray("contents") ?: return emptyList()

            val results = mutableListOf<TrackResult>()
            for (i in 0 until contents.length()) {
                if (results.size >= limit) break
                val item = contents.optJSONObject(i) ?: continue
                val vr = item.optJSONObject("playlistPanelVideoRenderer") ?: continue

                val vid = vr.optString("videoId")
                if (vid.isBlank()) continue

                val title = vr.optJSONObject("title")?.optString("simpleText")
                    ?: vr.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    ?: "Morceau"

                val artist = vr.optJSONObject("shortBylineText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    ?: vr.optJSONObject("longBylineText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    ?: "Artiste"

                val durText = vr.optJSONObject("lengthText")?.optString("simpleText") ?: ""
                val parts = durText.split(":").mapNotNull { it.toIntOrNull() }
                val durMs = when (parts.size) {
                    2 -> (parts[0] * 60 + parts[1]) * 1000L
                    3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
                    else -> 0L
                }

                val cleanTitle = title.replace(Regex("""\s*[\(\[].*?(Official|Video|Clip|Audio|Lyric|HQ).*?[\)\]]""", RegexOption.IGNORE_CASE), "").trim()

                results.add(
                    TrackResult(
                        id = "yt:$vid",
                        name = cleanTitle.ifBlank { title },
                        artists = artist,
                        album = "Radio YouTube",
                        durationMs = durMs,
                        cover = "https://i.ytimg.com/vi/$vid/hqdefault.jpg",
                        isExplicit = false,
                    )
                )
            }
            results
        }
    }
}
