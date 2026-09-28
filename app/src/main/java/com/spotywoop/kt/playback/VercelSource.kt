package com.spotywoop.kt.playback

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Source distante interrogeant l'API serverless Vercel couplée à la base de données Turso.
 * Permet un retour en ~80 ms si le titre est en cache, ou ~1.3 s si résolution cloud live.
 */
object VercelSource {
    private const val TAG = "VercelSource"
    private const val API_BASE = "https://tubidy-resolver-api.vercel.app/api/resolve"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun resolve(spotifyTrackId: String, query: String): ResolvedStream? {
        if (query.isBlank() && spotifyTrackId.isBlank()) return null
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val encodedId = URLEncoder.encode(spotifyTrackId, "UTF-8")
            val url = "$API_BASE?q=$encodedQuery&id=$encodedId"

            Log.d(TAG, "Interrogation Vercel Cloud: $url")
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                if (!json.optBoolean("success", false)) return null

                val streamUrl = json.getString("streamUrl")
                val isCached = json.optBoolean("cached", false)
                val tubidyId = json.optString("tubidyId", "")

                val headers = mutableMapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://audio.tubidy.com/"
                )
                if (tubidyId.isNotBlank()) {
                    headers["X-Tubidy-Id"] = tubidyId
                }

                Log.i(TAG, "Succès Vercel (cached=$isCached, id=$tubidyId): $streamUrl")
                ResolvedStream(
                    url = streamUrl,
                    source = if (isCached) "Cloud Turso (~80ms)" else "Cloud Vercel",
                    quality = "MP3 Direct",
                    headers = headers
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Échec Vercel pour '$query': ${e.message}")
            null
        }
    }
}
