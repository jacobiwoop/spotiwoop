package com.spotywoop.kt.data

import android.content.Context
import android.os.Environment
import com.spotywoop.kt.playback.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val http = OkHttpClient.Builder()
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

    fun init(context: Context) {
        if (catalogFile != null) return
        catalogFile = File(context.filesDir, "downloads.json")
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "music")
        musicDir = baseDir.also { it.mkdirs() }
        loadCatalog()
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

        _downloadingIds.value = _downloadingIds.value + track.id
        _activeDownloads.value = _activeDownloads.value + (track.id to ActiveDownload(track, 0f, 0L, 0L))

        scope.launch {
            try {
                // 1. Résolution de l'URL audio
                val resolved = StreamResolver.resolve(track.id, track.artists, track.name, track.durationMs)

                // 2. Téléchargement du fichier audio avec suivi de progression
                val ext = if (resolved.quality.contains("FLAC", ignoreCase = true)) "flac" else "mp3"
                val audioFile = File(dir, "${track.id}.$ext")

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
                                _activeDownloads.value = _activeDownloads.value + (track.id to ActiveDownload(track, prog, downloadedBytes, contentLength))
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

                _downloads.value = _downloads.value + dt
                saveCatalog()
            } catch (e: Exception) {
                android.util.Log.e("DownloadManager", "Erreur téléchargement ${track.name}: ${e.message}")
                val partialFile = File(dir, "${track.id}.mp3")
                if (partialFile.exists() && !_downloads.value.any { it.track.id == track.id }) {
                    partialFile.delete()
                }
            } finally {
                _downloadingIds.value = _downloadingIds.value - track.id
                _activeDownloads.value = _activeDownloads.value - track.id
            }
        }
    }

    fun deleteDownload(trackId: String) {
        val dt = _downloads.value.find { it.track.id == trackId } ?: return
        try {
            File(dt.localAudioPath).delete()
            dt.localCoverPath?.let { File(it).delete() }
        } catch (_: Exception) { }

        _downloads.value = _downloads.value.filterNot { it.track.id == trackId }
        saveCatalog()
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
                file.writeText(arr.toString())
            } catch (e: Exception) {
                android.util.Log.e("DownloadManager", "Erreur sauvegarde catalogue: ${e.message}")
            }
        }
    }
}
