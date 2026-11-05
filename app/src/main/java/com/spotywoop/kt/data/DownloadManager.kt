package com.spotywoop.kt.data

import android.content.Context
import android.os.Environment
import com.spotywoop.kt.playback.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ActiveDownload(
    val track: TrackResult,
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
)

/**
 * Gestionnaire des téléchargements hors-ligne de morceaux.
 */
object DownloadManager {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val http = OkHttpClient.Builder().addInterceptor(com.spotywoop.kt.data.AppLog.Http)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var catalogFile: File? = null
    private var musicDir: File? = null

    private val _downloads = MutableStateFlow<List<DownloadedTrack>>(emptyList())
    val downloads: StateFlow<List<DownloadedTrack>> = _downloads.asStateFlow()

    private val _downloadingIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingIds: StateFlow<Set<String>> = _downloadingIds.asStateFlow()

    private val _activeDownloads = MutableStateFlow<Map<String, ActiveDownload>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, ActiveDownload>> = _activeDownloads.asStateFlow()

    /** Titres dont le dernier téléchargement a échoué. */
    private val _failedIds = MutableStateFlow<Set<String>>(emptySet())
    val failedIds: StateFlow<Set<String>> = _failedIds.asStateFlow()

    private val _collections = MutableStateFlow<List<SavedCollection>>(emptyList())
    val collections: StateFlow<List<SavedCollection>> = _collections.asStateFlow()

    private var collectionsFile: File? = null

    // Un album entier ne doit pas ouvrir 20 connexions à la fois.
    private val slots = Semaphore(3)

    fun init(context: Context) {
        if (catalogFile != null) return
        catalogFile = File(context.filesDir, "downloads.json")
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "music")
        musicDir = baseDir.also { it.mkdirs() }
        loadCatalog()
        collectionsFile = File(context.filesDir, "download_collections.json")
        loadCollections()
    }

    private val AUDIO_EXTENSIONS = listOf("mp3", "m4a", "flac", "opus", "webm")

    /** Extension du vrai format : celle de l'URL (.m4a Audiomack, .mp3 Tubidy), sinon d'après la qualité. */
    private fun audioExtension(stream: com.spotywoop.kt.playback.ResolvedStream): String {
        val fromUrl = android.net.Uri.parse(stream.url).lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        return when {
            fromUrl in AUDIO_EXTENSIONS -> fromUrl!!
            stream.quality.contains("FLAC", ignoreCase = true) -> "flac"
            else -> "mp3"
        }
    }

    fun isDownloaded(trackId: String): Boolean {
        return _downloads.value.any { it.track.id == trackId }
    }

    fun isDownloading(trackId: String): Boolean {
        return _downloadingIds.value.contains(trackId)
    }

    fun downloadTrack(track: TrackResult) {
        if (isDownloaded(track.id) || isDownloading(track.id)) return
        val dir = musicDir ?: return

        _downloadingIds.update { it + track.id }
        _activeDownloads.update { it + (track.id to ActiveDownload(track, 0f, 0L, 0L)) }
        _failedIds.update { it - track.id }

        scope.launch {
            try {
                slots.withPermit {
                // 1. Résolution de l'URL audio
                val resolved = StreamResolver.resolve(track.id, track.artists, track.name, track.durationMs)

                // 2. Téléchargement du fichier audio avec suivi de progression
                val audioFile = File(dir, "${track.id}.${audioExtension(resolved)}")

                val reqBuilder = Request.Builder().url(resolved.url)
                resolved.headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                val req = reqBuilder.build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw java.io.IOException("Empty body")

                    val contentLength = body.contentLength()
                    var downloadedBytes = 0L
                    val buffer = ByteArray(16384)
                    val inputStream = body.byteStream()
                    var lastEmitTime = 0L

                    FileOutputStream(audioFile).use { out ->
                        var read: Int
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            downloadedBytes += read
                            val nowMs = System.currentTimeMillis()
                            if (contentLength > 0 && nowMs - lastEmitTime > 60) {
                                lastEmitTime = nowMs
                                val prog = (downloadedBytes.toFloat() / contentLength).coerceIn(0f, 1f)
                                _activeDownloads.update { it + (track.id to ActiveDownload(track, prog, downloadedBytes, contentLength)) }
                            }
                        }
                    }
                }

                // 3. Téléchargement de la pochette si disponible
                var localCoverPath: String? = null
                if (!track.cover.isNullOrBlank()) {
                    val coverFile = File(dir, "${track.id}_cover.jpg")
                    try {
                        val cReq = Request.Builder().url(track.cover).build()
                        http.newCall(cReq).execute().use { cResp ->
                            if (cResp.isSuccessful && cResp.body != null) {
                                FileOutputStream(coverFile).use { out ->
                                    cResp.body!!.byteStream().copyTo(out)
                                }
                                localCoverPath = coverFile.absolutePath
                            }
                        }
                    } catch (_: Exception) { }
                }

                val dt = DownloadedTrack(
                    track = track,
                    localAudioPath = audioFile.absolutePath,
                    localCoverPath = localCoverPath,
                    downloadedAt = System.currentTimeMillis(),
                    fileSize = audioFile.length(),
                )

                _downloads.update { it + dt }
                saveCatalog()
                OfflineCache.saveForTrack(track)
                }
            } catch (e: Exception) {
                android.util.Log.e("DownloadManager", "Erreur téléchargement ${track.name}: ${e.message}")
                _failedIds.update { it + track.id }
                if (!_downloads.value.any { it.track.id == track.id }) {
                    AUDIO_EXTENSIONS.map { File(dir, "${track.id}.$it") }.filter { it.exists() }.forEach { it.delete() }
                }
            } finally {
                _downloadingIds.update { it - track.id }
                _activeDownloads.update { it - track.id }
            }
        }
    }

    fun deleteDownload(trackId: String) {
        val dt = _downloads.value.find { it.track.id == trackId } ?: return
        try {
            File(dt.localAudioPath).delete()
            dt.localCoverPath?.let { File(it).delete() }
        } catch (_: Exception) { }

        _downloads.update { list -> list.filterNot { it.track.id == trackId } }
        saveCatalog()
    }

    /** Enregistre la collection et télécharge ce qui manque : les titres déjà présents ne sont pas repris. */
    fun downloadCollection(collection: SavedCollection) {
        val kept = _collections.value.find { it.key == collection.key }
        val saved = if (kept != null) collection.copy(savedAt = kept.savedAt) else collection
        _collections.update { list -> listOf(saved) + list.filterNot { it.key == saved.key } }
        saveCollections()
        saved.tracks.forEach(::downloadTrack)
    }

    /** Une playlist téléchargée suit la playlist : un titre ajouté se télécharge aussi. */
    fun syncUserCollection(key: String, tracks: List<TrackResult>) {
        val saved = _collections.value.find { it.key == key } ?: return
        downloadCollection(saved.copy(tracks = tracks, image = saved.image ?: tracks.firstOrNull()?.cover))
    }

    /** Oublie la collection et supprime ses titres, sauf ceux qu'une autre collection téléchargée contient. */
    fun deleteCollection(key: String) {
        val saved = _collections.value.find { it.key == key } ?: return
        val others = _collections.value.filterNot { it.key == key }
        val kept = others.flatMap { c -> c.tracks.map { it.id } }.toSet()
        _collections.value = others
        saveCollections()
        saved.tracks.filterNot { it.id in kept }.forEach { deleteDownload(it.id) }
        _failedIds.update { it - saved.tracks.map { t -> t.id }.toSet() }
    }

    fun findCollection(kind: String, ref: String, title: String): SavedCollection? =
        _collections.value.find { it.key == "$kind:$ref" }
            ?: _collections.value.find { it.kind == kind && it.title.equals(title, ignoreCase = true) }

    /** Pochette à afficher : le fichier local s'il existe, pour rester visible hors connexion. */
    fun coverFor(track: TrackResult): Any? =
        _downloads.value.find { it.track.id == track.id }?.localCoverPath?.let { File(it) } ?: track.cover

    private fun loadCollections() {
        val file = collectionsFile ?: return
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            _collections.value = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val tracks = o.getJSONArray("tracks")
                SavedCollection(
                    key = o.getString("key"),
                    kind = o.getString("kind"),
                    ref = o.optString("ref"),
                    title = o.getString("title"),
                    subtitle = o.optString("subtitle").takeIf { it.isNotBlank() },
                    image = o.optString("image").takeIf { it.isNotBlank() },
                    tracks = (0 until tracks.length()).map { LocalLibraryStore.jsonToTrack(tracks.getJSONObject(it)) },
                    savedAt = o.optLong("savedAt", 0L),
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("DownloadManager", "Erreur chargement collections: ${e.message}")
        }
    }

    private fun saveCollections() {
        val file = collectionsFile ?: return
        scope.launch {
            try {
                val arr = JSONArray()
                for (c in _collections.value) {
                    arr.put(
                        JSONObject()
                            .put("key", c.key)
                            .put("kind", c.kind)
                            .put("ref", c.ref)
                            .put("title", c.title)
                            .put("subtitle", c.subtitle ?: "")
                            .put("image", c.image ?: "")
                            .put("savedAt", c.savedAt)
                            .put("tracks", JSONArray().apply { c.tracks.forEach { put(LocalLibraryStore.trackToJson(it)) } })
                    )
                }
                synchronized(file) { file.writeText(arr.toString()) }
            } catch (e: Exception) {
                android.util.Log.e("DownloadManager", "Erreur sauvegarde collections: ${e.message}")
            }
        }
    }

    private fun loadCatalog() {
        val file = catalogFile ?: return
        if (!file.exists()) return

        try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<DownloadedTrack>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val trackObj = obj.getJSONObject("track")
                val tr = LocalLibraryStore.jsonToTrack(trackObj)
                val audioPath = obj.getString("audioPath")
                val coverPath = obj.optString("coverPath").takeIf { it.isNotBlank() }

                if (File(audioPath).exists()) {
                    list.add(
                        DownloadedTrack(
                            track = tr,
                            localAudioPath = audioPath,
                            localCoverPath = coverPath,
                            downloadedAt = obj.optLong("downloadedAt", 0L),
                            fileSize = obj.optLong("fileSize", 0L),
                        )
                    )
                }
            }
            _downloads.value = list
        } catch (e: Exception) {
            android.util.Log.e("DownloadManager", "Erreur chargement catalogue: ${e.message}")
        }
    }

    private fun saveCatalog() {
        val file = catalogFile ?: return
        scope.launch {
            try {
                val arr = JSONArray()
                for (d in _downloads.value) {
                    val obj = JSONObject()
                    obj.put("track", LocalLibraryStore.trackToJson(d.track))
                    obj.put("audioPath", d.localAudioPath)
                    obj.put("coverPath", d.localCoverPath ?: "")
                    obj.put("downloadedAt", d.downloadedAt)
                    obj.put("fileSize", d.fileSize)
                    arr.put(obj)
                }
                synchronized(file) { file.writeText(arr.toString()) }
            } catch (e: Exception) {
                android.util.Log.e("DownloadManager", "Erreur sauvegarde catalogue: ${e.message}")
            }
        }
    }
}
