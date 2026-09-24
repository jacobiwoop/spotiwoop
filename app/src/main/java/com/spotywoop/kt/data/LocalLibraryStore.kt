package com.spotywoop.kt.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Gestionnaire local des Favoris (Likes) et Playlists de l'utilisateur.
 * Sauvegarde automatique dans un fichier JSON local (100% fonctionnel hors-ligne).
 */
object LocalLibraryStore {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var file: File? = null

    private val _likedTracks = MutableStateFlow<List<TrackResult>>(emptyList())
    val likedTracks: StateFlow<List<TrackResult>> = _likedTracks.asStateFlow()

    private val _playlists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    val playlists: StateFlow<List<UserPlaylist>> = _playlists.asStateFlow()

    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "user_library.json")
        load()
    }

    fun isLiked(trackId: String): Boolean {
        return _likedTracks.value.any { it.id == trackId }
    }

    fun toggleLike(track: TrackResult): Boolean {
        val current = _likedTracks.value.toMutableList()
        val index = current.indexOfFirst { it.id == track.id }
        val nowLiked = if (index >= 0) {
            current.removeAt(index)
            false
        } else {
            current.add(0, track)
            true
        }
        _likedTracks.value = current
        save()
        return nowLiked
    }

    fun createPlaylist(name: String): UserPlaylist {
        val newPlaylist = UserPlaylist(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Ma Playlist" },
            createdAt = System.currentTimeMillis(),
            tracks = emptyList(),
        )
        val current = _playlists.value.toMutableList()
        current.add(0, newPlaylist)
        _playlists.value = current
        save()
        return newPlaylist
    }

    fun deletePlaylist(playlistId: String) {
        val current = _playlists.value.toMutableList()
        current.removeAll { it.id == playlistId }
        _playlists.value = current
        save()
    }

    fun addTrackToPlaylist(playlistId: String, track: TrackResult): Boolean {
        val current = _playlists.value.toMutableList()
        val index = current.indexOfFirst { it.id == playlistId }
        if (index < 0) return false

        val pl = current[index]
        if (pl.tracks.any { it.id == track.id }) return false // déjà présent

        val updatedTracks = pl.tracks + track
        val updatedPl = pl.copy(
            tracks = updatedTracks,
            cover = pl.cover ?: track.cover,
        )
        current[index] = updatedPl
        _playlists.value = current
        save()
        return true
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        val current = _playlists.value.toMutableList()
        val index = current.indexOfFirst { it.id == playlistId }
        if (index < 0) return

        val pl = current[index]
        val updatedTracks = pl.tracks.filterNot { it.id == trackId }
        current[index] = pl.copy(tracks = updatedTracks)
        _playlists.value = current
        save()
    }

    fun getPlaylist(playlistId: String): UserPlaylist? {
        return _playlists.value.find { it.id == playlistId }
    }

    private fun load() {
        val target = file ?: return
        if (!target.exists()) return

        try {
            val content = target.readText()
            val root = JSONObject(content)

            // Charger les titres likés
            val likedArray = root.optJSONArray("liked") ?: JSONArray()
            val loadedLiked = mutableListOf<TrackResult>()
            for (i in 0 until likedArray.length()) {
                val obj = likedArray.getJSONObject(i)
                loadedLiked.add(jsonToTrack(obj))
            }
            _likedTracks.value = loadedLiked

            // Charger les playlists
            val plArray = root.optJSONArray("playlists") ?: JSONArray()
            val loadedPlaylists = mutableListOf<UserPlaylist>()
            for (i in 0 until plArray.length()) {
                val plObj = plArray.getJSONObject(i)
                val trArray = plObj.optJSONArray("tracks") ?: JSONArray()
                val trList = mutableListOf<TrackResult>()
                for (j in 0 until trArray.length()) {
                    trList.add(jsonToTrack(trArray.getJSONObject(j)))
                }
                loadedPlaylists.add(
                    UserPlaylist(
                        id = plObj.optString("id", UUID.randomUUID().toString()),
                        name = plObj.optString("name", "Playlist"),
                        cover = plObj.optString("cover").takeIf { it.isNotBlank() },
                        createdAt = plObj.optLong("createdAt", System.currentTimeMillis()),
                        tracks = trList,
                    )
                )
            }
            _playlists.value = loadedPlaylists
        } catch (e: Exception) {
            android.util.Log.e("LocalLibraryStore", "Erreur chargement bibliothèque: ${e.message}")
        }
    }

    private fun save() {
        val target = file ?: return
        scope.launch {
            try {
                val root = JSONObject()

                val likedArray = JSONArray()
                for (t in _likedTracks.value) {
                    likedArray.put(trackToJson(t))
                }
                root.put("liked", likedArray)

                val plArray = JSONArray()
                for (p in _playlists.value) {
                    val pObj = JSONObject()
                    pObj.put("id", p.id)
                    pObj.put("name", p.name)
                    pObj.put("cover", p.cover ?: "")
                    pObj.put("createdAt", p.createdAt)

                    val tracksArray = JSONArray()
                    for (t in p.tracks) {
                        tracksArray.put(trackToJson(t))
                    }
                    pObj.put("tracks", tracksArray)
                    plArray.put(pObj)
                }
                root.put("playlists", plArray)

                target.writeText(root.toString())
            } catch (e: Exception) {
                android.util.Log.e("LocalLibraryStore", "Erreur sauvegarde bibliothèque: ${e.message}")
            }
        }
    }

    fun trackToJson(t: TrackResult): JSONObject {
        return JSONObject().apply {
            put("id", t.id)
            put("name", t.name)
            put("artists", t.artists)
            put("album", t.album)
            put("durationMs", t.durationMs)
            put("cover", t.cover ?: "")
            put("isExplicit", t.isExplicit)
        }
    }

    fun jsonToTrack(obj: JSONObject): TrackResult {
        return TrackResult(
            id = obj.getString("id"),
            name = obj.getString("name"),
            artists = obj.optString("artists", ""),
            album = obj.optString("album", ""),
            durationMs = obj.optLong("durationMs", 0L),
            cover = obj.optString("cover").takeIf { it.isNotBlank() },
            isExplicit = obj.optBoolean("isExplicit", false),
        )
    }
}
