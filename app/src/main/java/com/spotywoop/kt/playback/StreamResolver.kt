package com.spotywoop.kt.playback

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * Résout les URLs audio dans l'ordre de priorité :
 *   1. SpotiflacSource (FLAC/DASH) — si session valide et serveur dispo
 *   2. ServerAudioSource (Audio HQ complet) — via serveur Spotiwoop local
 *   3. SpotifyPreviewSource (30 s MP3) — fallback garanti
 */
object StreamResolver {
    private const val TTL_MS = 15 * 60_000L

    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, ResolvedStream>()

    private val _resolved = MutableStateFlow<Map<String, ResolvedStream>>(emptyMap())
    val resolved: StateFlow<Map<String, ResolvedStream>> = _resolved.asStateFlow()

    private var localYtDlSource: LocalYoutubeDlSource? = null
    private val serverSource = ServerAudioSource()
    private val previewSource = SpotifyPreviewSource(http)

    /** Appelé depuis PlaybackService.onCreate() et MainActivity.onCreate() avec le contexte application. */
    fun init(context: Context) {
        val appCtx = context.applicationContext
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

        val errors = mutableListOf<String>()

        // 0. Source Locale Hors-ligne (si le morceau a été téléchargé)
        val downloaded = com.spotywoop.kt.data.DownloadManager.downloads.value.find { it.track.id == spotifyTrackId }
        if (downloaded != null && java.io.File(downloaded.localAudioPath).exists()) {
            val stream = ResolvedStream(
                url = downloaded.localAudioPath,
                source = "Hors-ligne",
                quality = "Fichier Local",
            )
            cache[spotifyTrackId] = stream
            _resolved.update { it + (spotifyTrackId to stream) }
            return stream
        }

        // 1. SpotiFLAC désactivé à la demande de l'utilisateur (priorité au Moteur Natif local)

        val query = if (spotifyTrackId.startsWith("yt:")) {
            "https://www.youtube.com/watch?v=${spotifyTrackId.removePrefix("yt:")}"
        } else {
            listOfNotNull(artist, title).joinToString(" ").trim()
        }

        // 2. Course Concurrente : Tubidy MP3 Direct (< 1s) vs YouTube Natif HQ (Opus/M4A)
        if (query.isNotBlank()) {
            val startMs = System.currentTimeMillis()
            val raceWinner: ResolvedStream? = try {
                runBlocking(Dispatchers.IO) {
                    val deferred = CompletableDeferred<ResolvedStream>()
                    val failedSources = AtomicInteger(0)
                    val totalSources = 2

                    // Couloir 1 : Tubidy Natif (requêtes HTTP directes ultra-rapides)
                    val tubidyJob = launch {
                        try {
                            val stream = TubidySource.resolveWithQuery(query)
                            if (stream != null) {
                                if (deferred.complete(stream)) {
                                    val duration = System.currentTimeMillis() - startMs
                                    android.util.Log.i("StreamResolver", "🏆 Tubidy a gagné la course en ${duration}ms pour '$query' !")
                                }
                            } else {
                                if (failedSources.incrementAndGet() >= totalSources) {
                                    deferred.completeExceptionally(IOException("Toutes les sources de la course ont échoué"))
                                }
                            }
                        } catch (e: Exception) {
                            if (failedSources.incrementAndGet() >= totalSources) {
                                deferred.completeExceptionally(e)
                            }
                        }
                    }

                    // Couloir 2 : YouTube Music Natif
                    val ytJob = launch {
                        try {
                            localYtDlSource?.let { src ->
                                val stream = src.resolveWithQuery(query, durationMs)
                                if (deferred.complete(stream)) {
                                    val duration = System.currentTimeMillis() - startMs
                                    android.util.Log.i("StreamResolver", "🏆 YouTube a gagné la course en ${duration}ms pour '$query' !")
                                }
                            } ?: run {
                                if (failedSources.incrementAndGet() >= totalSources) {
                                    deferred.completeExceptionally(IOException("Moteur YouTube non disponible"))
                                }
                            }
                        } catch (e: Exception) {
                            if (failedSources.incrementAndGet() >= totalSources) {
                                deferred.completeExceptionally(e)
                            }
                        }
                    }

                    try {
                        val winner = deferred.await()
                        tubidyJob.cancel()
                        ytJob.cancel()
                        winner
                    } catch (e: Exception) {
                        tubidyJob.cancel()
                        ytJob.cancel()
                        null
                    }
                }
            } catch (e: Exception) {
                null
            }

            if (raceWinner != null) {
                cache[spotifyTrackId] = raceWinner
                _resolved.update { it + (spotifyTrackId to raceWinner) }
                return raceWinner
            } else {
                errors += "Course Tubidy/YouTube: non résolu"
            }
        }

        // 3. Serveur HQ de secours (si la course échoue ou est hors-ligne)
        if (query.isNotBlank()) {
            try {
                android.util.Log.d("StreamResolver", "Tentative source secours: ${serverSource.name} pour '$query'")
                val stream = serverSource.resolveWithQuery(spotifyTrackId, query)
                android.util.Log.i("StreamResolver", "Succès source: ${serverSource.name} -> ${stream.quality}")
                cache[spotifyTrackId] = stream
                _resolved.update { it + (spotifyTrackId to stream) }
                return stream
            } catch (e: Exception) {
                android.util.Log.w("StreamResolver", "Échec source ${serverSource.name}: ${e.message}")
                errors += "${serverSource.name} : ${e.message}"
            }
        }

        // 4. Fallback Aperçu Spotify 30s
        try {
            android.util.Log.d("StreamResolver", "Tentative source: ${previewSource.name} pour $spotifyTrackId")
            val stream = previewSource.resolve(spotifyTrackId)
            android.util.Log.i("StreamResolver", "Succès source: ${previewSource.name} -> ${stream.quality}")
            _resolved.update { it + (spotifyTrackId to stream) }
            return stream
        } catch (e: Exception) {
            android.util.Log.w("StreamResolver", "Échec source ${previewSource.name}: ${e.message}")
            errors += "${previewSource.name} : ${e.message}"
        }

        throw IOException(errors.joinToString(" | ").ifEmpty { "Aucune source audio disponible" })
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
                android.util.Log.d("StreamResolver", "Pré-chargement anticipé en tâche de fond : '$title' ($spotifyTrackId)")
                resolve(spotifyTrackId, artist, title, durationMs)
                android.util.Log.i("StreamResolver", "Pré-chargement réussi (prêt pour 0ms) : '$title'")
            } catch (e: Exception) {
                android.util.Log.w("StreamResolver", "Échec pré-chargement pour '$title': ${e.message}")
            }
        }
    }

    fun invalidate(spotifyTrackId: String) { cache.remove(spotifyTrackId) }
    fun invalidateAll() { cache.clear() }
}
