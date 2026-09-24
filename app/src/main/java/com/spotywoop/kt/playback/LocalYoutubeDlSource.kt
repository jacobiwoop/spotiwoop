package com.spotywoop.kt.playback

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.IOException

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Source audio ultra-rapide et autonome s'exécutant DIRECTEMENT sur le téléphone (moteur Seal / youtubedl-android).
 *
 * Avantages capitaux :
 * 1. Utilise la connexion résidentielle / 4G du téléphone -> 0 blocage bot/captcha par YouTube ou SoundCloud !
 * 2. Autonomie complète -> Fonctionne même si le serveur cloud est éteint ou inaccessible.
 * 3. Récupère directement le flux audio complet sans téléchargement sur disque (-g / --get-url).
 */
class LocalYoutubeDlSource(private val context: Context) : StreamSource {
    override val name = "Moteur Natif (yt-dlp)"

    private val httpClient = OkHttpClient.Builder()
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    companion object {
        const val CHROME_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
        private const val TAG = "LocalYoutubeDl"
        @Volatile
        private var isInitialized = false
        private val initLock = Any()

        fun ensureInitialized(ctx: Context): Boolean {
            if (isInitialized) return true
            synchronized(initLock) {
                if (isInitialized) return true
                return try {
                    Log.i(TAG, "Initialisation de youtubedl-android en cours...")
                    YoutubeDL.getInstance().init(ctx.applicationContext)
                    isInitialized = true
                    Log.i(TAG, "youtubedl-android initialisé avec succès ! Version actuelle: ${YoutubeDL.getInstance().versionName(ctx)}")

                    // Mise à jour en tâche de fond pour obtenir les derniers extracteurs (dont visionos)
                    Thread {
                        try {
                            Log.i(TAG, "Mise à jour de yt-dlp en cours...")
                            val status = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
                            Log.i(TAG, "Mise à jour yt-dlp terminée: $status (nouvelle version: ${YoutubeDL.getInstance().versionName(ctx)})")
                        } catch (t: Throwable) {
                            Log.w(TAG, "Impossible de mettre à jour yt-dlp: ${t.message}")
                        }
                    }.start()

                    true
                } catch (e: Throwable) {
                    Log.e(TAG, "Échec de l'initialisation de youtubedl-android: ${e.message}", e)
                    false
                }
            }
        }
    }

    override fun resolve(spotifyTrackId: String): ResolvedStream {
        throw UnsupportedOperationException("Utiliser resolveWithQuery")
    }

    private fun isStreamAccessible(url: String, headers: Map<String, String>): Boolean {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1024")
                .apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }
                .build()
            httpClient.newCall(req).execute().use { resp ->
                resp.isSuccessful || resp.code in 200..399
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vérification flux échouée pour $url: ${e.message}")
            false
        }
    }

    fun resolveWithVideoId(videoId: String): ResolvedStream {
        if (!ensureInitialized(context)) {
            throw IOException("Moteur natif non initialisé")
        }
        val directUrl = "https://www.youtube.com/watch?v=$videoId"
        val stream = resolveTarget(directUrl, isYouTube = true)
        if (!isStreamAccessible(stream.url, stream.headers)) {
            throw IOException("Flux inaccessible pour $videoId")
        }
        return stream.copy(
            headers = stream.headers + ("X-YouTube-Id" to videoId)
        )
    }

    fun resolveWithQuery(query: String, durationMs: Long? = null): ResolvedStream {
        if (!ensureInitialized(context)) {
            throw IOException("Moteur natif non initialisé")
        }

        // Étape 1 : Si c'est déjà une URL YouTube directe
        if (query.startsWith("http://") || query.startsWith("https://")) {
            return resolveTarget(query, isDirectUrl = true)
        }

        // Étape 2 (Priorité N°1) : Matching haute précision SpotDL via YouTube Music (Topic / Official Audio)
        try {
            Log.d(TAG, "Recherche SpotDL Music pour '$query' (durée cible: ${durationMs?.let { it / 1000 }}s)...")
            val candidateIds = SpotDlMatcher.findCandidateVideoIds(query, durationMs, limit = 4)
            for (videoId in candidateIds) {
                try {
                    val stream = resolveWithVideoId(videoId)
                    Log.i(TAG, "Succès SpotDL Matcher vérifié (200 OK) -> https://www.youtube.com/watch?v=$videoId")
                    return stream
                } catch (e: Exception) {
                    Log.w(TAG, "Échec extraction pour $videoId: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Échec SpotDL Matcher: ${e.message}, essai ytsearch direct...")
        }

        // Étape 3 (Fallback) : Recherche classique YouTube
        Log.d(TAG, "Recherche native YouTube pour: $query")
        val ytTarget = "ytsearch1:$query audio"
        return resolveTarget(ytTarget, isYouTube = true)
    }

    private fun resolveTarget(
        target: String,
        isDirectUrl: Boolean = false,
        isYouTube: Boolean = false,
    ): ResolvedStream {
        val response = try {
            val reqWithVision = YoutubeDLRequest(target).apply {
                addOption("-f", "bestaudio[protocol^=http]/bestaudio/best")
                addOption("--no-playlist")
                addOption("--geo-bypass")
                addOption("--extractor-args", "youtube:player_client=visionos,android")
                addOption("--user-agent", CHROME_USER_AGENT)
                addOption("-g")
            }
            YoutubeDL.getInstance().execute(reqWithVision)
        } catch (e: Exception) {
            val fallbackReq = YoutubeDLRequest(target).apply {
                addOption("-f", "bestaudio[protocol^=http]/bestaudio/best")
                addOption("--no-playlist")
                addOption("--geo-bypass")
                addOption("--user-agent", CHROME_USER_AGENT)
                addOption("-g")
            }
            YoutubeDL.getInstance().execute(fallbackReq)
        }

        val output = response.out.trim()

        val streamUrl = output.lines().firstOrNull { it.startsWith("http") }
            ?: throw IOException("Aucun flux trouvé dans la sortie yt-dlp")

        Log.i(TAG, "Stream URL résolue: $streamUrl")

        val sourceName = when {
            isYouTube -> "YouTube Music (Natif)"
            isDirectUrl -> "Lien Direct (Natif)"
            else -> name
        }

        val quality = "Opus/M4A HQ (Local)"

        val headers = mapOf(
            "User-Agent" to CHROME_USER_AGENT,
            "Accept" to "*/*",
            "Sec-Fetch-Mode" to "navigate",
        )

        return ResolvedStream(
            url = streamUrl,
            source = sourceName,
            quality = quality,
            headers = headers,
        )
    }
}
