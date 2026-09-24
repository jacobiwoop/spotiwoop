package com.spotywoop.kt.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Récupère les suggestions de recherche en temps réel via l'API publique Google / YouTube Music.
 */
object SearchSuggestionsClient {
    private const val TAG = "SuggestionsClient"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    fun getSuggestions(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        return try {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=$encoded"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val jsonStr = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body?.string() ?: return emptyList()
            }

            val rootArr = JSONArray(jsonStr)
            val suggestionsArr = rootArr.optJSONArray(1) ?: return emptyList()

            val list = mutableListOf<String>()
            for (i in 0 until suggestionsArr.length()) {
                val item = suggestionsArr.optString(i, "").trim()
                if (item.isNotBlank() && !list.contains(item)) {
                    list.add(item)
                }
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "Échec récupération suggestions pour '$query': ${e.message}")
            emptyList()
        }
    }
}
