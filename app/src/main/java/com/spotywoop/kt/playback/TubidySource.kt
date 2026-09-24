package com.spotywoop.kt.playback

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Extracteur autonome Tubidy s'exécutant directement sur le téléphone via OkHttp.
 * Résout les morceaux populaires en MP3 direct (~800 ms) sans serveur externe.
 */
object TubidySource {
    private const val TAG = "TubidySource"
    private const val BASE_URL = "https://mp3.tubidy.cool"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Matcher pour liens de veille Tubidy : /watch/{id}/mp4/fs
    private val WATCH_REGEX = Pattern.compile("""href=["'](?://mp3\.tubidy\.cool)?/watch/([a-zA-Z0-9_=-]+)/""")
    // Matcher pour lien direct audio d2mefast.net
    private val D2ME_REGEX = Pattern.compile("""href=["'](https?://[^"']*d2mefast\.net/[^"']+)["']""")

    fun resolveWithQuery(query: String): ResolvedStream? {
        if (query.isBlank()) return null
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$BASE_URL/search.php?q=$encodedQuery"

            Log.d(TAG, "Recherche Tubidy: $searchUrl")
            val searchReq = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$BASE_URL/")
                .build()

            val searchHtml = httpClient.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string() ?: return null
            }

            val watchMatcher = WATCH_REGEX.matcher(searchHtml)
            if (!watchMatcher.find()) {
                Log.d(TAG, "Aucun résultat trouvé sur Tubidy pour '$query'")
                return null
            }
            val videoId = watchMatcher.group(1) ?: return null
            resolveWithWatchId(videoId)
        } catch (e: Exception) {
            Log.w(TAG, "Échec Tubidy pour '$query': ${e.message}")
            null
        }
    }

    fun resolveWithWatchId(videoId: String): ResolvedStream? {
        if (videoId.isBlank()) return null
        return try {
            // Format MP3 Audio (lnk=6)
            val formatUrl = "$BASE_URL/watch.php?id=$videoId&p=mp4&t=ssl&act=down&lnk=6"
            Log.d(TAG, "Récupération format MP3 Tubidy pour ID $videoId: $formatUrl")

            val formatReq = Request.Builder()
                .url(formatUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$BASE_URL/watch/$videoId/mp4/fs")
                .build()

            val formatHtml = httpClient.newCall(formatReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string() ?: return null
            }

            val d2meMatcher = D2ME_REGEX.matcher(formatHtml)
            if (!d2meMatcher.find()) {
                Log.d(TAG, "Lien direct d2mefast non prêt pour ID $videoId")
                return null
            }

            val streamUrl = d2meMatcher.group(1) ?: return null
            Log.i(TAG, "Succès Tubidy MP3 direct trouvé: $streamUrl")

            ResolvedStream(
                url = streamUrl,
                source = "Tubidy MP3",
                quality = "MP3 Direct (Ultra-rapide)",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$BASE_URL/",
                    "X-Tubidy-Id" to videoId,
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Échec Tubidy direct pour ID $videoId: ${e.message}")
            null
        }
    }
}
