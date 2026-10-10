package com.spotywoop.kt.data

import com.spotywoop.kt.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Titre renvoyé par l'API du site (Audiomack ou Spotify), avec ses infos de classement. */
data class ApiTrack(
    val track: TrackResult,
    val rank: Int = 0,
    val change: String = "",
    val plays: Long = 0,
)

/** Vignette d'album ou de playlist. */
data class ApiCard(
    val kind: String,
    val ref: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val explicit: Boolean,
    val plays: Long,
)

data class DiscoverData(
    val trendingSongs: List<ApiTrack>,
    val top: List<ApiTrack>,
    val trendingAlbums: List<ApiCard>,
    val recent: List<ApiTrack>,
)

/**
 * Client de l'API du site Spotiwoop (même projet Vercel que le résolveur) : page Découvrir
 * (Audiomack) et contenu des albums et playlists. Les titres Audiomack gardent leur identifiant
 * "am:<id>", que le résolveur lit directement.
 */
object SpotiwoopApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val base = BuildConfig.ONLINE_RESOLVE_URL.removeSuffix("/").removeSuffix("/resolve")

    suspend fun discover(genre: String, country: String = "BJ"): DiscoverData {
        val o = get("discover", "genre" to genre, "country" to country)
        return DiscoverData(
            trendingSongs = tracks(o.optJSONArray("trendingSongs")),
            top = tracks(o.optJSONArray("top")),
            trendingAlbums = cards(o.optJSONArray("trendingAlbums")),
            recent = tracks(o.optJSONArray("recent")),
        )
    }

    /** Titres d'un album ou d'une playlist ; [ref] vaut "sp:<id>" ou "am:<…>". */
    suspend fun collectionTracks(kind: String, ref: String): List<TrackResult> {
        val route = if (kind == "album") "album" else "playlist"
        return tracks(get(route, "ref" to ref).optJSONArray("tracks")).map { it.track }
    }

    /**
     * Suggestions par titre, comme le site : titres liés Audiomack (compte partagé), sinon radio
     * Spotify de l'artiste principal. Rend une liste vide si l'API n'a rien.
     */
    suspend fun radio(seedId: String, artists: String, title: String, durationMs: Long): List<TrackResult> {
        val o = try {
            get(
                "radio",
                "seed" to "track",
                "am" to if (seedId.startsWith("am:")) seedId.removePrefix("am:") else "",
                "q" to "$artists $title".trim(),
                "duration" to if (durationMs > 0) "${(durationMs + 500) / 1000}" else "",
                "artist" to artists,
            )
        } catch (e: NotFound) {
            return emptyList()
        }
        return tracks(o.optJSONArray("tracks")).map { it.track }
    }

    /**
     * Paroles via l'API (même recherche LRCLIB que le site, avec contrôle de durée, en cache 24 h pour
     * tout le monde). Rend null si l'API répond qu'il n'y en a pas ; lève une exception si elle ne
     * répond pas, pour que l'appelant essaie LRCLIB en direct.
     */
    suspend fun lyrics(artist: String, title: String, album: String?, durationSec: Long): LyricsResult? {
        val o = try {
            get("lyrics", "artist" to artist, "title" to title, "album" to album.orEmpty(), "duration" to if (durationSec > 0) "$durationSec" else "")
        } catch (e: NotFound) {
            return null
        }
        val a = o.optJSONArray("lines") ?: return null
        val lines = (0 until a.length()).map { a.getJSONObject(it).let { l -> LyricLine(l.optLong("t"), l.optString("text")) } }
        return if (lines.isEmpty()) null else LyricsResult(lines, isSynced = o.optBoolean("synced"))
    }

    private class NotFound : IOException("introuvable")

    private suspend fun get(route: String, vararg params: Pair<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val url = "$base/$route".toHttpUrl().newBuilder()
            .apply { params.filter { it.second.isNotBlank() }.forEach { addQueryParameter(it.first, it.second) } }
            .build()
        val request = Request.Builder().url(url).header("X-Api-Key", BuildConfig.ONLINE_RESOLVE_API_KEY).build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 404) throw NotFound()
            if (!resp.isSuccessful) {
                val message = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                throw IOException(message.ifBlank { "Erreur ${resp.code}" })
            }
            JSONObject(body)
        }
    }

    private fun tracks(a: JSONArray?): List<ApiTrack> = (0 until (a?.length() ?: 0)).map { i ->
        val t = a!!.getJSONObject(i)
        ApiTrack(
            track = TrackResult(
                id = t.optString("id"),
                name = t.optString("title"),
                artists = t.optString("artist"),
                album = t.optString("album"),
                durationMs = t.optLong("duration") * 1000,
                cover = t.optString("image").ifBlank { null },
                isExplicit = t.optBoolean("explicit"),
            ),
            rank = t.optInt("rank"),
            change = t.optString("change"),
            plays = t.optLong("plays"),
        )
    }

    private fun cards(a: JSONArray?): List<ApiCard> = (0 until (a?.length() ?: 0)).map { i ->
        val c = a!!.getJSONObject(i)
        ApiCard(
            kind = c.optString("kind"),
            ref = c.optString("ref"),
            title = c.optString("title"),
            subtitle = c.optString("subtitle").ifBlank { null },
            image = c.optString("image").ifBlank { null },
            explicit = c.optBoolean("explicit"),
            plays = c.optLong("plays"),
        )
    }
}
