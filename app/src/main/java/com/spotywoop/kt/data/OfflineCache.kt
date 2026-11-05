package com.spotywoop.kt.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Copie sur disque des données affichées (fiches artiste, albums, paroles), pour les retrouver
 * hors connexion. Rangée dans filesDir : Android ne la vide pas tout seul, contrairement à cacheDir.
 */
object OfflineCache {
    private const val MAX_BYTES = 200L * 1024 * 1024

    private var dir: File? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val savedArtists = ConcurrentHashMap.newKeySet<String>()

    fun init(context: Context) {
        if (dir != null) return
        val d = File(context.filesDir, "offline_data").apply { mkdirs() }
        dir = d
        scope.launch { trim(d) }
    }

    fun read(key: String): String? {
        val f = fileFor(key) ?: return null
        if (!f.exists()) return null
        return runCatching {
            f.setLastModified(System.currentTimeMillis())
            f.readText()
        }.getOrNull()
    }

    fun write(key: String, value: String) {
        val f = fileFor(key) ?: return
        runCatching {
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(value)
            tmp.renameTo(f)
        }
    }

    /** Enregistre d'avance ce que le lecteur affiche pour un titre téléchargé, même jamais écouté. */
    fun saveForTrack(track: TrackResult) {
        scope.launch { save(track) }
    }

    /** Rattrape au démarrage les téléchargements encore sans données, par exemple faits hors connexion. */
    fun saveMissing(tracks: List<TrackResult>) {
        scope.launch {
            tracks.filter { read(savedKey(it)) == null }.forEach { save(it) }
        }
    }

    private suspend fun save(track: TrackResult) {
        runCatching {
            LyricsClient.fetchLyrics(track.artists, track.name, track.album, (track.durationMs + 500) / 1000)
        }
        val artistsSaved = track.artists.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { name ->
            !savedArtists.add(name) || runCatching { SpotiwoopApi.artist("name:$name") }.isSuccess.also { ok ->
                if (!ok) savedArtists.remove(name)
            }
        }.all { it }
        if (track.album.isNotBlank()) {
            runCatching {
                SpotiwoopApi.findAlbumRef(track.album, track.artists)?.let { SpotiwoopApi.collection("album", it) }
            }
        }
        if (artistsSaved) write(savedKey(track), "1")
    }

    private fun savedKey(track: TrackResult) = "saved:${track.id}"

    private fun fileFor(key: String): File? {
        val d = dir ?: return null
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(d, hash)
    }

    /** Au-delà de la limite, retire les entrées lues le moins récemment. */
    private fun trim(d: File) {
        val files = d.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_BYTES) break
            total -= f.length()
            f.delete()
        }
    }
}
