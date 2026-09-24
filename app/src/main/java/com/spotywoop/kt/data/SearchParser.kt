package com.spotywoop.kt.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// Accès tolérants au JSON dynamique du GraphQL Spotify (équivalents de getString/getMap/... en Go).
internal fun JsonObject?.obj(key: String): JsonObject = this?.get(key) as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject?.arr(key: String): List<JsonElement> = (this?.get(key) as? JsonArray).orEmpty()
internal fun JsonObject?.str(key: String): String =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
internal fun JsonObject?.num(key: String): Double = (this?.get(key) as? JsonPrimitive)?.doubleOrNull ?: 0.0
internal fun JsonObject?.bool(key: String): Boolean = (this?.get(key) as? JsonPrimitive)?.booleanOrNull ?: false

/** Portage de FilterSearch (spotfetch.go) : réponse searchDesktop → résultats typés. */
object SearchParser {

    fun parse(root: JsonObject): SearchResults {
        val search = root.obj("data").obj("searchV2")
        if (search.isEmpty()) return SearchResults()
        return SearchResults(
            tracks = parseTracks(search),
            albums = parseAlbums(search),
            artists = parseArtists(search),
            playlists = parsePlaylists(search),
        )
    }

    private fun section(search: JsonObject, v2: String, v1: String): List<JsonObject> {
        val data = search.obj(v2).ifEmpty { search.obj(v1) }
        return data.arr("items").filterIsInstance<JsonObject>()
    }

    private fun parseTracks(search: JsonObject) = section(search, "tracksV2", "tracks").mapNotNull { item ->
        val track = if ("item" in item) item.obj("item").obj("data") else item.obj("track")
        val name = track.str("name")
        if (track.isEmpty() || name.isEmpty()) return@mapNotNull null

        var durationMs = track.obj("duration").num("totalMilliseconds")
        if (durationMs == 0.0) durationMs = track.obj("trackDuration").num("totalMilliseconds")
        val album = track.obj("albumOfTrack")

        TrackResult(
            id = idOf(track),
            name = name,
            artists = artistNames(track.obj("artists")),
            album = album.str("name"),
            durationMs = durationMs.toLong(),
            cover = extractCover(album.obj("coverArt")),
            isExplicit = track.obj("contentRating").str("label") == "EXPLICIT",
        )
    }

    private fun parseAlbums(search: JsonObject) = section(search, "albumsV2", "albums").mapNotNull { item ->
        val album = if ("data" in item) item.obj("data") else item.obj("album")
        val name = album.str("name")
        val artists = artistNames(album.obj("artists"))
        if (name.isEmpty() || artists.isEmpty()) return@mapNotNull null
        AlbumResult(
            id = idOf(album),
            name = name,
            artists = artists,
            cover = extractCover(album.obj("coverArt")),
            year = album.obj("date").num("year").toInt().takeIf { it > 0 },
        )
    }

    private fun parseArtists(search: JsonObject) = section(search, "artistsV2", "artists").mapNotNull { item ->
        val artist = if ("data" in item) item.obj("data") else item.obj("artist")
        val name = artist.obj("profile").str("name").ifEmpty { artist.str("name") }
        if (name.isEmpty()) return@mapNotNull null
        ArtistResult(
            id = idOf(artist),
            name = name,
            cover = extractCover(artist.obj("visualIdentity"))
                ?: extractCover(artist.obj("visuals").obj("avatarImage")),
        )
    }

    private fun parsePlaylists(search: JsonObject) = section(search, "playlistsV2", "playlists").mapNotNull { item ->
        val playlist = if ("data" in item) item.obj("data") else item.obj("playlist")
        val name = playlist.str("name")
        if (name.isEmpty()) return@mapNotNull null
        val images = playlist.obj("images").ifEmpty { playlist.obj("imagesV2") }
        val firstSources = (images.arr("items").firstOrNull() as? JsonObject).arr("sources")
        val cover = if (firstSources.isNotEmpty()) {
            extractCover(JsonObject(mapOf("sources" to JsonArray(firstSources))))
        } else {
            null
        } ?: extractCover(images)
        PlaylistResult(
            id = idOf(playlist),
            name = name,
            cover = cover,
            owner = playlist.obj("ownerV2").obj("data").str("name").ifEmpty { null },
        )
    }

    private fun idOf(obj: JsonObject): String =
        obj.str("id").trim().ifEmpty { obj.str("uri").substringAfterLast(':', "").trim() }

    private fun artistNames(artists: JsonObject): String =
        artists.arr("items").filterIsInstance<JsonObject>()
            .map { it.obj("profile").str("name") }
            .joinToString(", ")

    /**
     * Choisit la pochette 640 px si elle existe (clé « medium » dans SpotiFLAC),
     * sinon la plus grande source disponible.
     */
    internal fun extractCover(cover: JsonObject): String? {
        var sources = cover.arr("sources")
        if (sources.isEmpty()) {
            sources = cover.obj("squareCoverImage").obj("image").obj("data").arr("sources")
        }
        val candidates = sources.filterIsInstance<JsonObject>().mapNotNull { src ->
            val url = src.str("url")
            if (url.isEmpty()) return@mapNotNull null
            val width = src.num("width").takeIf { it > 0 } ?: src.num("maxWidth")
            val height = src.num("height").takeIf { it > 0 } ?: src.num("maxHeight")
            if ((width > 64 && height > 64) || (width == 0.0 && height == 0.0)) url to width else null
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.second == 640.0 }?.first
            ?: candidates.maxBy { it.second }.first
    }
}
