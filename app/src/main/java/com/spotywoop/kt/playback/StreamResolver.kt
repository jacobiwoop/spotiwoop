package com.spotywoop.kt.playback

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** URL audio réelle d'un titre, avec de quoi l'afficher dans le lecteur. */
data class ResolvedStream(
    val url: String,
    val source: String,
    val quality: String,
    val headers: Map<String, String> = emptyMap(),
    val resolvedAt: Long = System.currentTimeMillis(),
)

/** Une source audio : Spotify ID -> URL lisible par ExoPlayer. Appelée hors thread principal. */
interface StreamSource {
    val name: String
    fun resolve(spotifyTrackId: String): ResolvedStream
}

/** Extrait officiel de 30 s tiré de la page embed Spotify (fallback). */
class SpotifyPreviewSource(private val http: OkHttpClient) : StreamSource {
    override val name = "Aperçu Spotify"

    override fun resolve(spotifyTrackId: String): ResolvedStream {
        val request = Request.Builder()
            .url("https://open.spotify.com/embed/track/$spotifyTrackId")
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val html = http.newCall(request).execute().use { resp ->
            if (resp.code != 200) throw IOException("Page embed : HTTP ${resp.code}")
            resp.body!!.string()
        }
        val url = PREVIEW.find(html)?.value ?: throw IOException("Pas d'aperçu disponible pour ce titre")
        return ResolvedStream(url = url, source = name, quality = "MP3 · 30 s")
    }

    private companion object {
        val PREVIEW = Regex("""https://p\.scdn\.co/mp3-preview/[a-zA-Z0-9]+""")
    }
}

/**
 * Résout les URLs audio :
 *   0. fichier téléchargé (hors-ligne) ;
 *   1. course entre online-resolve (Audiomack + Tubidy, côté serveur) et yt-dlp sur le téléphone :
 *      le premier lien obtenu gagne ;
 *   2. aperçu Spotify de 30 s en dernier recours.
 */
object StreamResolver {
    private const val TAG = "StreamResolver"
    private const val TTL_MS = 15 * 60_000L
    private const val RACE_TIMEOUT_MS = 25_000L

    private val http = OkHttpClient.Builder().addInterceptor(com.spotywoop.kt.data.AppLog.Http).callTimeout(20, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, ResolvedStream>()

    private val _resolved = MutableStateFlow<Map<String, ResolvedStream>>(emptyMap())
    val resolved: StateFlow<Map<String, ResolvedStream>> = _resolved.asStateFlow()

    private var localYtDlSource: LocalYoutubeDlSource? = null
    private val previewSource = SpotifyPreviewSource(http)

    /** Appelé depuis PlaybackService.onCreate() et MainActivity.onCreate() avec le contexte application. */
    fun init(context: Context) {
        val appCtx = context.applicationContext
        TrackIdCache.init(appCtx)
        localYtDlSource = LocalYoutubeDlSource(appCtx)
        // Pré-initialise youtubedl-android en tâche de fond pour ne pas bloquer le démarrage
        Thread {
            LocalYoutubeDlSource.ensureInitialized(appCtx)
        }.start()
    }

    fun resolve(
        spotifyTrackId: String,
        artist: String? = null,
        title: String? = null,
        durationMs: Long? = null,
    ): ResolvedStream {
        cache[spotifyTrackId]
            ?.takeIf { System.currentTimeMillis() - it.resolvedAt < TTL_MS }
            ?.let { return it }

        // 0. Fichier téléchargé
        val downloaded = com.spotywoop.kt.data.DownloadManager.downloads.value.find { it.track.id == spotifyTrackId }
        if (downloaded != null && java.io.File(downloaded.localAudioPath).exists()) {
            return remember(spotifyTrackId, ResolvedStream(url = downloaded.localAudioPath, source = "Hors-ligne", quality = "Fichier Local"))
        }

        val textQuery = listOfNotNull(artist, title).joinToString(" ").trim()
        val ytVideoId = spotifyTrackId.takeIf { it.startsWith("yt:") }?.removePrefix("yt:")
        val knownYtId = TrackIdCache.get(spotifyTrackId)?.youtubeId ?: ytVideoId

        // 1. Course : le premier lien obtenu gagne, les autres couloirs sont abandonnés.
        val lanes = buildList<Pair<String, () -> ResolvedStream?>> {
            if (textQuery.isNotBlank()) add("online-resolve" to { OnlineResolveSource.resolve(artist, title, durationMs, spotifyTrackId) })
            // Tubidy depuis le téléphone : concourt avec le serveur (utile si Vercel est lent ou en panne).
            if (textQuery.isNotBlank()) add("tubidy-local" to { TubidySource.resolve(artist, title, durationMs) })
            localYtDlSource?.let { yt ->
                when {
                    knownYtId != null -> add("yt-dlp (id)" to { yt.resolveWithVideoId(knownYtId) })
                    textQuery.isNotBlank() -> add("yt-dlp" to { yt.resolveWithQuery(textQuery, durationMs, artist, title) })
                }
            }
        }
        val start = System.currentTimeMillis()
        val winner = race(lanes)
        if (winner != null) {
            android.util.Log.i(TAG, "🏆 ${winner.source} en ${System.currentTimeMillis() - start} ms pour '$textQuery'")
            winner.headers["X-YouTube-Id"]?.let { TrackIdCache.put(spotifyTrackId, youtubeId = it) }
            return remember(spotifyTrackId, winner)
        }

        // 2. Aperçu Spotify 30 s
        try {
            val stream = previewSource.resolve(spotifyTrackId)
            _resolved.update { it + (spotifyTrackId to stream) }
            return stream
        } catch (e: Exception) {
            throw IOException("Aucune source audio pour '$textQuery' (course : aucun lien ; aperçu : ${e.message})")
        }
    }

    /**
     * Lance les couloirs en parallèle et rend le premier lien obtenu, ou null si tous échouent
     * ou si le délai expire. Les couloirs tournent hors de la portée de l'appelant : le gagnant est
     * rendu tout de suite, sans attendre les perdants (yt-dlp ne s'interrompt pas en cours d'appel).
     */
    private fun race(lanes: List<Pair<String, () -> ResolvedStream?>>): ResolvedStream? {
        if (lanes.isEmpty()) return null
        val winner = CompletableDeferred<ResolvedStream?>()
        val pending = AtomicInteger(lanes.size)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        for ((name, lane) in lanes) {
            scope.launch {
                val stream = try {
                    lane().also { if (it == null) android.util.Log.d(TAG, "Couloir $name : aucun lien") }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Couloir $name : ${e.message}")
                    null
                }
                if (stream != null) winner.complete(stream)
                if (pending.decrementAndGet() == 0) winner.complete(null)
            }
        }
        return try {
            runBlocking { withTimeoutOrNull(RACE_TIMEOUT_MS) { winner.await() } }
        } finally {
            scope.cancel()
        }
    }

    private fun remember(trackId: String, stream: ResolvedStream): ResolvedStream {
        cache[trackId] = stream
        _resolved.update { it + (trackId to stream) }
        return stream
    }

    /**
     * Pré-charge discrètement en tâche de fond le morceau suivant dans le cache.
     * Dès que l'utilisateur ou ExoPlayer passera à ce morceau, la lecture démarrera en 0 ms.
     */
    fun prefetch(
        spotifyTrackId: String,
        artist: String? = null,
        title: String? = null,
        durationMs: Long? = null,
    ) {
        if (spotifyTrackId.isBlank() || cache.containsKey(spotifyTrackId)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                android.util.Log.d(TAG, "Pré-chargement anticipé en tâche de fond : '$title' ($spotifyTrackId)")
                resolve(spotifyTrackId, artist, title, durationMs)
                android.util.Log.i(TAG, "Pré-chargement réussi (prêt pour 0ms) : '$title'")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Échec pré-chargement pour '$title': ${e.message}")
            }
        }
    }

    fun invalidate(spotifyTrackId: String) { cache.remove(spotifyTrackId) }
    fun invalidateAll() { cache.clear() }
}
