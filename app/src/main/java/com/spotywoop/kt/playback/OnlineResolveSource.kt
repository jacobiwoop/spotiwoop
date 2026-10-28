package com.spotywoop.kt.playback

import android.util.Log
import com.spotywoop.kt.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Résolveur en ligne `online-resolve` (Vercel) : Audiomack et Tubidy en course côté serveur,
 * avec reconnaissance du bon enregistrement par la durée Spotify. Le lien rendu se lit directement.
 */
object OnlineResolveSource {
    private const val TAG = "OnlineResolve"

    private val http = OkHttpClient.Builder().addInterceptor(com.spotywoop.kt.data.AppLog.Http)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS) // le serveur abandonne lui-même après 15 s
        .build()

    /** Rend null quand aucune source n'a le titre (404) ou que la clé n'est pas configurée. */
    fun resolve(artists: String?, title: String?, durationMs: Long?, trackId: String? = null): ResolvedStream? {
        val apiKey = BuildConfig.ONLINE_RESOLVE_API_KEY
        if (apiKey.isBlank()) {
            Log.w(TAG, "onlineResolve.apiKey absent de local.properties : source désactivée")
            return null
        }
        // Même format que la recherche Spotify de l'app : "<artistes> <titre>".
        val query = listOfNotNull(artists, title).joinToString(" ").trim()
        if (query.isBlank()) return null

        val url = BuildConfig.ONLINE_RESOLVE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .apply { if (durationMs != null && durationMs > 0) addQueryParameter("duration", ((durationMs + 500) / 1000).toString()) }
            // Les invités (« Stromae, Erik Hassle ») départagent deux versions de même durée.
            .apply { if (!artists.isNullOrBlank()) addQueryParameter("artist", artists) }
            // yt-dlp tourne en parallèle : plutôt « introuvable » qu'une version sans l'invité.
            .addQueryParameter("strict", "1")
            // Titre venu d'Audiomack (accueil) : le serveur le lit par son id, et retombe sur la course s'il est bloqué.
            .apply { trackId?.removePrefix("am:")?.takeIf { trackId.startsWith("am:") }?.let { addQueryParameter("am", it) } }
            .build()
        val request = Request.Builder().url(url).header("X-Api-Key", apiKey).build()

        http.newCall(request).execute().use { resp ->
            if (resp.code == 404) {
                Log.i(TAG, "Aucune source pour '$query'")
                return null
            }
            if (!resp.isSuccessful) throw IOException("online-resolve : HTTP ${resp.code}")
            val best = JSONObject(resp.body?.string() ?: throw IOException("online-resolve : réponse vide"))
                .optJSONObject("best") ?: return null
            val streamUrl = best.optString("streamUrl").ifBlank { return null }
            val provider = best.optString("provider")
            val format = best.optString("format").uppercase()
            Log.i(TAG, "'$query' -> $provider ($format) : ${best.optString("title")}")
            return ResolvedStream(
                url = streamUrl,
                source = "En ligne · ${provider.replaceFirstChar { it.uppercase() }}",
                quality = if (provider == "audiomack") "$format jusqu'à 256 kbit/s" else "$format 128 kbit/s",
            )
        }
    }
}
