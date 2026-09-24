package com.spotywoop.kt.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

        // 2. Moteur Natif Téléphone (youtubedl-android / Seal engine : SoundCloud + YouTube en local direct)
        localYtDlSource?.let { source ->
            if (query.isNotBlank()) {
                try {
                    android.util.Log.d("StreamResolver", "Tentative source: ${source.name} pour '$query' (durée: ${durationMs}ms)")
                    val stream = source.resolveWithQuery(query, durationMs)
                    android.util.Log.i("StreamResolver", "Succès source: ${stream.source} -> ${stream.quality}")
                    cache[spotifyTrackId] = stream
                    _resolved.update { it + (spotifyTrackId to stream) }
                    return stream
                } catch (e: Exception) {
                    android.util.Log.w("StreamResolver", "Échec source ${source.name}: ${e.message}")
                    errors += "${source.name} : ${e.message}"
                }
            }
        }

        // 3. Serveur HQ de secours (si le moteur natif échoue ou est encore en cours d'initialisation)
        if (query.isNotBlank()) {
            try {
                android.util.Log.d("StreamResolver", "Tentative source: ${serverSource.name} pour '$query'")
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

        // 3. Fallback Aperçu Spotify 30s (non mis en cache permanent pour retenter le titre complet au prochain clic)
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

    fun invalidate(spotifyTrackId: String) { cache.remove(spotifyTrackId) }
    fun invalidateAll() { cache.clear() }
}
