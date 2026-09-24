package com.spotywoop.kt.playback

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class CachedTrackIds(
    val youtubeId: String? = null,
    val tubidyWatchId: String? = null,
)

/**
 * Cache persistant (JSON local) associant un spotifyTrackId à ses identifiants uniques
 * YouTube (videoId) et Tubidy (watchId).
 * Permet de sauter complètement l'étape de recherche lors des écoutes suivantes (~250ms).
 */
object TrackIdCache {
    private const val TAG = "TrackIdCache"
    private val scope = CoroutineScope(Dispatchers.IO)
    private var cacheFile: File? = null
    private val memoryMap = ConcurrentHashMap<String, CachedTrackIds>()

    fun init(context: Context) {
        if (cacheFile != null) return
        cacheFile = File(context.filesDir, "track_id_cache.json")
        load()
    }

    fun get(spotifyTrackId: String): CachedTrackIds? {
        if (spotifyTrackId.isBlank()) return null
        return memoryMap[spotifyTrackId]
    }

    fun put(
        spotifyTrackId: String,
        youtubeId: String? = null,
        tubidyWatchId: String? = null,
    ) {
        if (spotifyTrackId.isBlank() || (youtubeId == null && tubidyWatchId == null)) return

        val existing = memoryMap[spotifyTrackId]
        val updated = CachedTrackIds(
            youtubeId = youtubeId ?: existing?.youtubeId,
            tubidyWatchId = tubidyWatchId ?: existing?.tubidyWatchId,
        )

        if (existing != updated) {
            memoryMap[spotifyTrackId] = updated
            save()
        }
    }

    private fun load() {
        val file = cacheFile ?: return
        if (!file.exists()) return

        scope.launch {
            try {
                val jsonStr = file.readText()
                val root = JSONObject(jsonStr)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = root.optJSONObject(key)
                    if (obj != null) {
                        memoryMap[key] = CachedTrackIds(
                            youtubeId = obj.optString("yt").takeIf { it.isNotBlank() },
                            tubidyWatchId = obj.optString("tubidy").takeIf { it.isNotBlank() },
                        )
                    }
                }
                Log.d(TAG, "Chargé ${memoryMap.size} entrées d'IDs en cache")
            } catch (e: Exception) {
                Log.w(TAG, "Erreur lecture cache IDs: ${e.message}")
            }
        }
    }

    private fun save() {
        val file = cacheFile ?: return
        scope.launch {
            try {
                val root = JSONObject()
                for ((k, v) in memoryMap) {
                    val obj = JSONObject()
                    v.youtubeId?.let { obj.put("yt", it) }
                    v.tubidyWatchId?.let { obj.put("tubidy", it) }
                    root.put(k, obj)
                }
                file.writeText(root.toString())
            } catch (e: Exception) {
                Log.w(TAG, "Erreur sauvegarde cache IDs: ${e.message}")
            }
        }
    }
}
