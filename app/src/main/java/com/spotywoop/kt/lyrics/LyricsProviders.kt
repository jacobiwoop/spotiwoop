package com.spotywoop.kt.lyrics

import com.spotywoop.kt.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Fournisseurs de paroles réseau de am-lyrics :
 * - lrc.red
 * - BiniLyrics
 * - Spicy Lyrics (avec token Spotify)
 * - Unison
 * - LyricsPlus / YouLyPlus (miroirs KPoe)
 * - LRCLIB
 * - Genius
 */
object LyricsProviders {

    private const val TAG = "LyricsProviders"
    private const val VERSION = "1.8.8"
    private const val SPICY_LYRICS_CLIENT_KEY = "sl_pk_8nNuJf0L-xFUv8L5uU-pfin69WU4j3IzzV2-T3GPWKY"
    private const val SPOTIFY_TOKEN_URL = "https://flxgjwpkztgpdywkgckk.supabase.co/functions/v1/spotify-token"
    private const val GENIUS_WORKER_URL = "https://fetch-genius.samidy.workers.dev/"
    private const val DEFAULT_KPOE_SOURCE_ORDER = "apple,lyricsplus,musixmatch,spotify,qq,deezer,musixmatch-word"

    private val KPOE_SERVERS = listOf(
        "https://lyricsplus.binimum.org",
        "https://lyricsplus-seven.vercel.app",
        "https://lyricsplus.prjktla.workers.dev",
        "https://lyrics-plus-backend.vercel.app",
    )

    private const val FETCH_TIMEOUT_MS = 8_000L
    private const val LRC_RED_TIMEOUT_MS = 3_000L

    private val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AppLog.Http)
        .callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private var spotifyTokenValue: String? = null
    private var spotifyTokenExpiresAt: Long = 0L
    private val spotifyMutex = Mutex()

    private fun logI(msg: String) {
        try {
            android.util.Log.i(TAG, msg)
        } catch (_: Throwable) {
            println("[$TAG] $msg")
        }
    }

    private fun logW(msg: String) {
        try {
            android.util.Log.w(TAG, msg)
        } catch (_: Throwable) {
            println("[$TAG] WARN: $msg")
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private val htmlEntity = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|quot|apos|lt|gt|nbsp);""")

    /** Décode les entités HTML que Genius laisse dans le texte (« I&#x27;ll », « &amp; »). */
    internal fun decodeHtmlEntities(s: String): String = htmlEntity.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"
            "quot" -> "\""
            "apos" -> "'"
            "lt" -> "<"
            "gt" -> ">"
            "nbsp" -> " "
            else -> {
                val code = if (e.startsWith("#x")) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else m.value
            }
        }
    }

    private val bracketed = Regex("""\s*[(\[][^)\]]*[)\]]""")

    private fun normalizeName(s: String): String = s.lowercase().trim().split(Regex("""\s+""")).joinToString(" ")

    // Titre sans « (feat. …) » / « [Remix] » ni la partie « - … » (version, ou artiste quand il y figure).
    private fun cleanTitle(title: String, artist: String): String {
        var t = bracketed.replace(title, "").trim()
        val i = t.indexOf(" - ")
        if (i >= 0) {
            val before = t.substring(0, i)
            val after = t.substring(i + 3)
            val first = artist.split(",").first().trim().lowercase()
            t = if (first.isNotEmpty() && before.lowercase().contains(first) && !after.lowercase().contains(first)) after else before
        }
        return t.trim()
    }

    /**
     * Le morceau rendu par Genius est-il le nôtre ? Le service rend le plus proche qu'il trouve, parfois d'un
     * autre artiste (« BANDIT » de Vano Baby → « Bendito » de Pailita). Même règle que le serveur du site.
     */
    internal fun sameSong(foundArtist: String, foundTitle: String, artist: String, title: String): Boolean {
        val want = normalizeName(artist.split(",").first())
        val got = normalizeName(foundArtist)
        if (want.isEmpty() || got.isEmpty() || !(got.contains(want) || want.contains(got))) return false
        val t = normalizeName(cleanTitle(foundTitle, foundArtist))
        val w = normalizeName(cleanTitle(title, artist))
        return t.isNotEmpty() && (t == w || t.contains(w) || w.contains(t))
    }

    /**
     * Récupère et met en cache un jeton d'accès Spotify temporaire.
     * Partagé entre les requêtes de recherche Spicy Lyrics.
     */
    suspend fun getSpotifyToken(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        spotifyMutex.withLock {
            if (spotifyTokenValue != null && spotifyTokenExpiresAt > now) {
                return@withLock spotifyTokenValue
            }

            val started = System.currentTimeMillis()
            try {
                val req = Request.Builder()
                    .url(SPOTIFY_TOKEN_URL)
                    .post("".toRequestBody("application/json".toMediaType()))
                    .build()

                val resp = http.newCall(req).execute()
                if (!resp.isSuccessful) {
                    logW("Spotify token : échec HTTP ${resp.code}")
                    return@withLock null
                }
                val bodyStr = resp.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val token = json.optString("access_token")
                val expiresInSec = json.optLong("expires_in", 0L)
                if (token.isBlank() || expiresInSec <= 0) return@withLock null

                val lifetimeMs = expiresInSec * 1000L
                spotifyTokenValue = token
                spotifyTokenExpiresAt = started + lifetimeMs - minOf(30_000L, (lifetimeMs * 0.1).toLong())
                logI("Spotify token renouvelé (expire dans ${expiresInSec}s)")
                spotifyTokenValue
            } catch (e: Exception) {
                logW("Spotify token : erreur ${e.message}")
                null
            }
        }
    }

    /**
     * Découpe une chaîne de requête brute en titre et artiste potentiels.
     */
    fun parseQueryMetadata(rawQuery: String?): ParsedQueryMetadata? {
        val trimmed = rawQuery?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val hyphenSplit = trimmed.split(Regex("""\s[-–—]\s"""))
        if (hyphenSplit.size >= 2) {
            val titleCandidate = hyphenSplit[0].trim()
            val artistCandidate = hyphenSplit.drop(1).joinToString(" - ").trim()
            if (titleCandidate.isNotEmpty() && artistCandidate.isNotEmpty()) {
                return ParsedQueryMetadata(title = titleCandidate, artist = artistCandidate)
            }
        }

        val bySplit = trimmed.split(Regex("""\s+[bB]y\s+"""))
        if (bySplit.size == 2) {
            val titleCandidate = bySplit[0].trim()
            val artistCandidate = bySplit[1].trim()
            if (titleCandidate.isNotEmpty() && artistCandidate.isNotEmpty()) {
                return ParsedQueryMetadata(title = titleCandidate, artist = artistCandidate)
            }
        }

        return null
    }

    /**
     * Recherche dans le catalogue LyricsPlus (KPoe / v1/songlist/search).
     */
    suspend fun searchLyricsPlusCatalog(searchTerm: String): SongCatalogResult? = withContext(Dispatchers.IO) {
        val trimmed = searchTerm.trim()
        if (trimmed.isEmpty()) return@withContext null

        for (base in KPOE_SERVERS) {
            val normalizedBase = base.trimEnd('/')
            val url = "$normalizedBase/v1/songlist/search?q=${urlEncode(trimmed)}"
            val started = System.currentTimeMillis()
            try {
                val req = Request.Builder().url(url).build()
                val resp = http.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val resultsList = mutableListOf<JSONObject>()
                    if (body.startsWith("[")) {
                        val arr = JSONArray(body)
                        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { resultsList.add(it) }
                    } else {
                        val obj = JSONObject(body)
                        val arr = obj.optJSONArray("results")
                        if (arr != null) {
                            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { resultsList.add(it) }
                        }
                    }

                    if (resultsList.isNotEmpty()) {
                        val primary = resultsList.firstOrNull { it.optJSONObject("id")?.has("appleMusic") == true } ?: resultsList[0]
                        val idObj = primary.optJSONObject("id")
                        val durationMs = primary.optDouble("durationMs").takeIf { it > 0 }?.toLong()
                        logI("Catalogue LyricsPlus : trouvé sur $base en ${System.currentTimeMillis() - started} ms")
                        return@withContext SongCatalogResult(
                            title = primary.optString("title").takeIf { it.isNotBlank() },
                            artist = primary.optString("artist").takeIf { it.isNotBlank() },
                            album = primary.optString("album").takeIf { it.isNotBlank() },
                            durationMs = durationMs,
                            songwriters = primary.optString("songwriters").takeIf { it.isNotBlank() },
                            isrc = primary.optString("isrc").takeIf { it.isNotBlank() },
                            appleMusicId = idObj?.optString("appleMusic")?.takeIf { it.isNotBlank() },
                            spotifyId = idObj?.optString("spotify")?.takeIf { it.isNotBlank() },
                        )
                    }
                }
            } catch (_: Exception) {
                // Continue vers le serveur suivant
            }
        }
        null
    }

    /**
     * Résout les métadonnées manquantes en interrogeant la requête brute et le catalogue.
     */
    suspend fun resolveSongMetadata(
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        durationMs: Long? = null,
        isrc: String? = null,
        query: String? = null,
        spotifyId: String? = null,
        musicId: String? = null,
        fallback: SongMetadata? = null,
    ): ResolvedMetadata = withContext(Dispatchers.IO) {
        var resolvedTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: fallback?.title?.trim().orEmpty()
        var resolvedArtist = artist?.trim()?.takeIf { it.isNotEmpty() } ?: fallback?.artist?.trim().orEmpty()
        var resolvedAlbum = album?.trim()?.takeIf { it.isNotEmpty() } ?: fallback?.album?.trim()
        var resolvedDuration = durationMs?.takeIf { it > 0 } ?: fallback?.durationMs?.takeIf { it > 0 }
        var resolvedSongwriters = fallback?.songwriters?.trim()

        var appleId = musicId
        var catalogIsrc = isrc
        var resolvedSpotifyId = spotifyId?.trim()?.takeIf { it.isNotEmpty() }

        if (!query.isNullOrBlank() && (resolvedTitle.isEmpty() || resolvedArtist.isEmpty() || resolvedAlbum == null)) {
            val parsed = parseQueryMetadata(query)
            if (parsed != null) {
                if (resolvedTitle.isEmpty() && !parsed.title.isNullOrBlank()) resolvedTitle = parsed.title!!
                if (resolvedArtist.isEmpty() && !parsed.artist.isNullOrBlank()) resolvedArtist = parsed.artist!!
                if (resolvedAlbum == null && !parsed.album.isNullOrBlank()) resolvedAlbum = parsed.album
            }
        }

        if (!query.isNullOrBlank() && (resolvedTitle.isEmpty() || resolvedArtist.isEmpty())) {
            val catalog = searchLyricsPlusCatalog(query)
            if (catalog != null) {
                if (resolvedTitle.isEmpty() && !catalog.title.isNullOrBlank()) resolvedTitle = catalog.title
                if (resolvedArtist.isEmpty() && !catalog.artist.isNullOrBlank()) resolvedArtist = catalog.artist
                if (resolvedAlbum == null && !catalog.album.isNullOrBlank()) resolvedAlbum = catalog.album
                if (resolvedSongwriters == null && !catalog.songwriters.isNullOrBlank()) resolvedSongwriters = catalog.songwriters
                if (resolvedDuration == null && catalog.durationMs != null) resolvedDuration = catalog.durationMs
                if (appleId == null && !catalog.appleMusicId.isNullOrBlank()) appleId = catalog.appleMusicId
                if (catalogIsrc == null && !catalog.isrc.isNullOrBlank()) catalogIsrc = catalog.isrc
                if (resolvedSpotifyId == null && !catalog.spotifyId.isNullOrBlank()) resolvedSpotifyId = catalog.spotifyId
            }
        }

        val metadata = if (resolvedTitle.isNotEmpty() && resolvedArtist.isNotEmpty()) {
            SongMetadata(
                title = resolvedTitle,
                artist = resolvedArtist,
                album = resolvedAlbum,
                durationMs = resolvedDuration,
                songwriters = resolvedSongwriters,
            )
        } else null

        ResolvedMetadata(
            metadata = metadata,
            appleId = appleId,
            catalogIsrc = catalogIsrc,
            spotifyId = resolvedSpotifyId,
        )
    }

    /**
     * Récupère les paroles depuis lrc.red (/match.json, /search.json puis /s/<ISRC>.song.json).
     */
    suspend fun fetchLyricsFromLrcRed(
        title: String,
        artist: String,
        isrc: String? = null,
        metadata: SongMetadata? = null,
        searchQuery: String = "",
    ): LyricsSourceResult? = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()

        suspend fun fetchSong(trackIsrc: String): LyricsSourceResult? = runCatching {
            withTimeout(LRC_RED_TIMEOUT_MS) {
                val url = "https://lrc.red/s/${urlEncode(trackIsrc)}.song.json"
                val req = Request.Builder().url(url).build()
                val resp = http.newCall(req).execute()
                if (!resp.isSuccessful) return@withTimeout null
                val bodyStr = resp.body?.string().orEmpty()
                val song = JSONObject(bodyStr)

                val originalTTML = song.optString("source").takeIf { it.isNotBlank() }
                val parsed = if (originalTTML != null) LyricsParsers.parseTTML(originalTTML) else null
                val lines = if (parsed != null && parsed.lines.isNotEmpty()) {
                    parsed.lines
                } else {
                    LyricsParsers.parseLrcRedJson(song)
                }
                if (lines.isEmpty()) return@withTimeout null

                val songTitle = song.optString("title")
                val songArtist = song.optString("artist")
                val songMetadata = if (songTitle.isNotBlank() && songArtist.isNotBlank()) {
                    SongMetadata(
                        title = songTitle,
                        artist = songArtist,
                        album = song.optString("album").takeIf { it.isNotBlank() },
                        durationMs = secondsToMs(song.opt("duration")).takeIf { it > 0 },
                    )
                } else null

                val swArray = song.optJSONArray("songwriters")
                val songwriters = if (swArray != null) {
                    (0 until swArray.length()).mapNotNull { swArray.optString(it).takeIf { s -> s.isNotBlank() } }.joinToString(", ")
                } else parsed?.songwriters

                LyricsSourceResult(
                    lines = lines,
                    source = "lrc.red",
                    originalTTML = originalTTML,
                    byModel = song.optBoolean("byModel", false),
                    isrc = trackIsrc,
                    metadata = songMetadata,
                    songwriters = songwriters,
                )
            }
        }.getOrNull()

        if (!isrc.isNullOrBlank()) {
            val r = fetchSong(isrc)
            if (r != null) {
                logI("lrc.red : trouvé via ISRC '$isrc' (${r.lines.size} lignes) en ${System.currentTimeMillis() - started} ms")
                return@withContext r
            }
        }

        val url: String = if (title.isNotBlank() && artist.isNotBlank()) {
            val sb = StringBuilder("https://lrc.red/match.json?")
                .append("track=").append(urlEncode(title))
                .append("&artist=").append(urlEncode(artist))
            if (!metadata?.album.isNullOrBlank()) sb.append("&album=").append(urlEncode(metadata.album))
            val dur = metadata?.durationMs ?: 0L
            if (dur > 0) sb.append("&duration=").append(Math.round(dur / 1000.0))
            sb.toString()
        } else if (searchQuery.isNotBlank()) {
            "https://lrc.red/search.json?q=${urlEncode(searchQuery)}"
        } else {
            return@withContext null
        }

        val hitResult = runCatching {
            withTimeout(LRC_RED_TIMEOUT_MS) {
                val req = Request.Builder().url(url).build()
                val resp = http.newCall(req).execute()
                if (!resp.isSuccessful) return@withTimeout null
                val payload = JSONObject(resp.body?.string().orEmpty())
                val hits = payload.optJSONArray("hits")
                val firstHit = hits?.optJSONObject(0)
                val hitIsrc = firstHit?.optString("isrc")?.trim()
                if (!hitIsrc.isNullOrEmpty()) fetchSong(hitIsrc) else null
            }
        }.getOrNull()

        if (hitResult != null) {
            logI("lrc.red : trouvé (${hitResult.lines.size} lignes) en ${System.currentTimeMillis() - started} ms")
        } else {
            logI("lrc.red : vide en ${System.currentTimeMillis() - started} ms")
        }
        hitResult
    }

    /**
     * Récupère les paroles depuis BiniLyrics (API de cache TTML).
     */
    suspend fun fetchLyricsFromBiniLyrics(
        title: String,
        artist: String,
        isrc: String? = null,
        metadata: SongMetadata? = null,
    ): LyricsSourceResult? = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (title.isBlank() && artist.isBlank() && isrc.isNullOrBlank()) return@withContext null

        try {
            withTimeout(FETCH_TIMEOUT_MS) {
                var cacheData: JSONObject? = null

                if (!isrc.isNullOrBlank()) {
                    runCatching {
                        val isrcUrl = "https://lyrics-api.binimum.org/?isrc=${urlEncode(isrc)}"
                        val resp = http.newCall(Request.Builder().url(isrcUrl).build()).execute()
                        if (resp.isSuccessful) {
                            val data = JSONObject(resp.body?.string().orEmpty())
                            val resArr = data.optJSONArray("results")
                            if (resArr != null && resArr.length() > 0) cacheData = data
                        }
                    }
                }

                if (cacheData == null && title.isNotBlank() && artist.isNotBlank()) {
                    val sb = StringBuilder("https://lyrics-api.binimum.org/?")
                        .append("track=").append(urlEncode(title))
                        .append("&artist=").append(urlEncode(artist))
                    if (!metadata?.album.isNullOrBlank()) sb.append("&album=").append(urlEncode(metadata.album))
                    val dur = metadata?.durationMs ?: 0L
                    if (dur > 0) sb.append("&duration=").append(Math.round(dur / 1000.0))

                    val resp = http.newCall(Request.Builder().url(sb.toString()).build()).execute()
                    if (resp.isSuccessful) {
                        cacheData = JSONObject(resp.body?.string().orEmpty())
                    }
                }

                val resultsArr = cacheData?.optJSONArray("results")
                if (resultsArr != null && resultsArr.length() > 0) {
                    val first = resultsArr.optJSONObject(0)
                    val lyricsUrl = first?.optString("lyricsUrl")
                    if (!lyricsUrl.isNullOrBlank()) {
                        val ttmlResp = http.newCall(Request.Builder().url(lyricsUrl).build()).execute()
                        if (ttmlResp.isSuccessful) {
                            val ttmlText = ttmlResp.body?.string().orEmpty()
                            val parseResult = LyricsParsers.parseTTML(ttmlText)
                            if (parseResult != null && parseResult.lines.isNotEmpty()) {
                                logI("BiniLyrics : trouvé (${parseResult.lines.size} lignes) en ${System.currentTimeMillis() - started} ms")
                                return@withTimeout LyricsSourceResult(
                                    lines = parseResult.lines,
                                    source = "BiniLyrics",
                                    originalTTML = ttmlText,
                                    songwriters = parseResult.songwriters,
                                )
                            }
                        }
                    }
                }
                logI("BiniLyrics : vide en ${System.currentTimeMillis() - started} ms")
                null
            }
        } catch (e: Exception) {
            logW("BiniLyrics : erreur ${e.message} en ${System.currentTimeMillis() - started} ms")
            null
        }
    }

    /**
     * Récupère les paroles depuis Spicy Lyrics.
     */
    suspend fun fetchLyricsFromSpicyLyrics(
        spotifyId: String? = null,
        isrc: String? = null,
        metadata: SongMetadata? = null,
    ): LyricsSourceResult? = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            withTimeout(FETCH_TIMEOUT_MS) {
                var trackId = spotifyId?.trim()

                if (trackId.isNullOrBlank()) {
                    var searchTerm = if (!isrc.isNullOrBlank()) "isrc:$isrc" else ""
                    if (searchTerm.isEmpty() && metadata != null && metadata.title.isNotBlank() && metadata.artist.isNotBlank()) {
                        searchTerm = "track:${metadata.title} artist:${metadata.artist}"
                    }
                    if (searchTerm.isEmpty()) return@withTimeout null

                    val searchUrl = StringBuilder("https://api.spotify.com/v1/search?")
                        .append("q=").append(urlEncode(searchTerm))
                        .append("&type=track")
                        .append("&limit=").append(if (!isrc.isNullOrBlank()) "1" else "5")
                        .toString()

                    var token = getSpotifyToken() ?: return@withTimeout null
                    var lookupReq = Request.Builder()
                        .url(searchUrl)
                        .header("Authorization", "Bearer $token")
                        .build()
                    var lookupResp = http.newCall(lookupReq).execute()

                    if (lookupResp.code == 401) {
                        spotifyMutex.withLock {
                            if (spotifyTokenValue == token) spotifyTokenValue = null
                        }
                        token = getSpotifyToken() ?: return@withTimeout null
                        lookupReq = Request.Builder()
                            .url(searchUrl)
                            .header("Authorization", "Bearer $token")
                            .build()
                        lookupResp = http.newCall(lookupReq).execute()
                    }

                    if (!lookupResp.isSuccessful) return@withTimeout null
                    val searchResult = JSONObject(lookupResp.body?.string().orEmpty())
                    val items = searchResult.optJSONObject("tracks")?.optJSONArray("items") ?: JSONArray()
                    val tracks = (0 until items.length()).mapNotNull { items.optJSONObject(it) }

                    fun normalize(s: String): String =
                        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
                            .lowercase()
                            .replace(Regex("""\s+"""), " ")
                            .trim()

                    val match = if (!isrc.isNullOrBlank()) {
                        tracks.firstOrNull { t ->
                            val extIsrc = t.optJSONObject("external_ids")?.optString("isrc")
                            extIsrc.isNullOrEmpty() || extIsrc.equals(isrc, ignoreCase = true)
                        }
                    } else if (metadata != null) {
                        val metaTitleNorm = normalize(metadata.title)
                        val metaArtistNorm = normalize(metadata.artist)
                        tracks.firstOrNull { t ->
                            val trackNameNorm = normalize(t.optString("name"))
                            val artistMatch = (t.optJSONArray("artists") ?: JSONArray()).let { arr ->
                                (0 until arr.length()).any { aIdx ->
                                    normalize(arr.optJSONObject(aIdx)?.optString("name").orEmpty()) == metaArtistNorm
                                }
                            }
                            val durationMatch = metadata.durationMs == null ||
                                Math.abs(t.optLong("duration_ms") - metadata.durationMs) <= 3000L
                            trackNameNorm == metaTitleNorm && artistMatch && durationMatch
                        }
                    } else null

                    trackId = match?.optString("id")
                }

                if (trackId.isNullOrBlank() || !Regex("""^[A-Za-z0-9]{22}$""").matches(trackId)) {
                    return@withTimeout null
                }

                val spicyUrl = "https://api.spicylyrics.org/v1/lyrics/${urlEncode(trackId)}"
                val spicyReq = Request.Builder()
                    .url(spicyUrl)
                    .header("Authorization", "Bearer $SPICY_LYRICS_CLIENT_KEY")
                    .build()

                val spicyResp = http.newCall(spicyReq).execute()
                if (!spicyResp.isSuccessful) {
                    logI("Spicy Lyrics : échec HTTP ${spicyResp.code} en ${System.currentTimeMillis() - started} ms")
                    return@withTimeout null
                }
                val spicyJson = JSONObject(spicyResp.body?.string().orEmpty())
                val result = LyricsParsers.parseSpicyLyrics(spicyJson)
                if (result != null) {
                    logI("Spicy Lyrics : trouvé (${result.lines.size} lignes) en ${System.currentTimeMillis() - started} ms")
                } else {
                    logI("Spicy Lyrics : vide en ${System.currentTimeMillis() - started} ms")
                }
                result
            }
        } catch (e: Exception) {
            logW("Spicy Lyrics : erreur ${e.message} en ${System.currentTimeMillis() - started} ms")
            null
        }
    }

    /**
     * Récupère les paroles depuis Unison (format ttml ou lrc).
     */
    suspend fun fetchLyricsFromUnison(metadata: SongMetadata): LyricsSourceResult? = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val title = metadata.title.trim()
        val artist = metadata.artist.trim()
        if (title.isEmpty() || artist.isEmpty()) return@withContext null

        try {
            withTimeout(FETCH_TIMEOUT_MS) {
                val sb = StringBuilder("https://unison.boidu.dev/lyrics?")
                    .append("song=").append(urlEncode(title))
                    .append("&artist=").append(urlEncode(artist))
                if (!metadata.album.isNullOrBlank()) sb.append("&album=").append(urlEncode(metadata.album))
                val dur = metadata.durationMs ?: 0L
                if (dur > 0) sb.append("&duration=").append(Math.round(dur / 1000.0))

                val req = Request.Builder().url(sb.toString()).build()
                val resp = http.newCall(req).execute()
                if (!resp.isSuccessful) return@withTimeout null

                val json = JSONObject(resp.body?.string().orEmpty())
                if (!json.optBoolean("success", false)) return@withTimeout null
                val data = json.optJSONObject("data") ?: return@withTimeout null
                val lyricsText = data.optString("lyrics")
                if (lyricsText.isBlank()) return@withTimeout null

                val format = data.optString("format", "lrc")
                val syncType = data.optString("syncType", "linesync")

                if (format == "ttml") {
                    val parsed = LyricsParsers.parseTTML(lyricsText)
                    if (parsed != null && parsed.lines.isNotEmpty()) {
                        logI("Unison : trouvé TTML (${parsed.lines.size} lignes) en ${System.currentTimeMillis() - started} ms")
                        return@withTimeout LyricsSourceResult(
                            lines = parsed.lines,
                            source = "Unison",
                            songwriters = parsed.songwriters,
                        )
                    }
                }

                if (format == "lrc") {
                    if (syncType == "plain") {
                        val plainLines = lyricsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        if (plainLines.isNotEmpty()) {
                            val lines = plainLines.map { text ->
                                LyricsLine(
                                    text = listOf(Syllable(text = text, part = false, timestamp = 0L, endtime = 0L)),
                                    background = false,
                                    backgroundText = emptyList(),
                                    oppositeTurn = false,
                                    timestamp = 0L,
                                    endtime = 0L,
                                    isWordSynced = false,
                                )
                            }
                            logI("Unison : trouvé non-synchronisé (${lines.size} lignes) en ${System.currentTimeMillis() - started} ms")
                            return@withTimeout LyricsSourceResult(lines = lines, source = "Unison (unsynced)")
                        }
                    } else {
                        val lines = LyricsParsers.parseLrcSubtitles(lyricsText)
                        if (lines.isNotEmpty()) {
                            logI("Unison : trouvé LRC (${lines.size} lignes) en ${System.currentTimeMillis() - started} ms")
                            return@withTimeout LyricsSourceResult(lines = lines, source = "Unison")
                        }
                    }
                }
                logI("Unison : vide en ${System.currentTimeMillis() - started} ms")
                null
            }
        } catch (e: Exception) {
            logW("Unison : erreur ${e.message} en ${System.currentTimeMillis() - started} ms")
            null
        }
    }

    /**
     * Récupère les paroles depuis LyricsPlus / YouLyPlus à travers ses miroirs de serveurs.
     */
    suspend fun fetchLyricsFromYouLyPlus(
        title: String,
        artist: String,
        isrc: String? = null,
        metadata: SongMetadata? = null,
        skipBiniCache: Boolean = false,
    ): List<LyricsSourceResult> = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (title.isBlank() && artist.isBlank() && isrc.isNullOrBlank()) return@withContext emptyList()

        val allResults = mutableListOf<LyricsSourceResult>()

        if (!skipBiniCache) {
            val bini = fetchLyricsFromBiniLyrics(title, artist, isrc, metadata)
            if (bini != null) {
                allResults.add(bini)
                if (!bini.byModel && hasWordSyncedLyrics(bini.lines)) {
                    return@withContext allResults
                }
            }
        }

        val params = StringBuilder()
        if (title.isNotBlank()) params.append("title=").append(urlEncode(title)).append("&")
        if (artist.isNotBlank()) params.append("artist=").append(urlEncode(artist)).append("&")
        if (!isrc.isNullOrBlank()) params.append("isrc=").append(urlEncode(isrc)).append("&")
        if (!metadata?.album.isNullOrBlank()) params.append("album=").append(urlEncode(metadata.album)).append("&")
        val dur = metadata?.durationMs ?: 0L
        if (dur > 0) params.append("duration=").append(Math.round(dur / 1000.0)).append("&")
        params.append("source=").append(urlEncode(DEFAULT_KPOE_SOURCE_ORDER))
        val queryString = params.toString()

        val shuffledServers = KPOE_SERVERS.shuffled().take(3)
        for (base in shuffledServers) {
            val normalizedBase = base.trimEnd('/')
            val url = "$normalizedBase/v2/lyrics/get?$queryString"
            try {
                withTimeout(FETCH_TIMEOUT_MS) {
                    val req = Request.Builder().url(url).build()
                    val resp = http.newCall(req).execute()
                    if (resp.isSuccessful) {
                        val payload = JSONObject(resp.body?.string().orEmpty())
                        val lines = LyricsParsers.convertKPoeLyrics(payload)
                        if (lines != null && lines.isNotEmpty()) {
                            val sourceLabel = payload.optJSONObject("metadata")?.optString("source")?.takeIf { it.isNotBlank() }
                                ?: payload.optJSONObject("metadata")?.optString("provider")?.takeIf { it.isNotBlank() }
                                ?: "LyricsPlus (KPoe)"

                            val result = LyricsSourceResult(lines = lines, source = sourceLabel)
                            allResults.add(result)

                            val rank = getRankForCollected(sourceLabel)
                            if (rank == 1.0 && hasWordSyncedLyrics(lines)) {
                                return@withTimeout
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore et essaie le miroir suivant
            }
        }

        // Si aucun résultat Apple synchronisé n'a été trouvé, requête de repli explicite
        val hasHighRankResult = allResults.any { r ->
            getRankForCollected(r.source) <= 2.0 && !r.byModel && hasWordSyncedLyrics(r.lines)
        }
        if (!hasHighRankResult) {
            runCatching {
                withTimeout(FETCH_TIMEOUT_MS) {
                    val fallbackUrl = "https://lyricsplus.binimum.org/v2/lyrics/get?$queryString"
                    val resp = http.newCall(Request.Builder().url(fallbackUrl).build()).execute()
                    if (resp.isSuccessful) {
                        val payload = JSONObject(resp.body?.string().orEmpty())
                        val lines = LyricsParsers.convertKPoeLyrics(payload)
                        if (lines != null && lines.isNotEmpty() && hasWordSyncedLyrics(lines)) {
                            val sourceLabel = payload.optJSONObject("metadata")?.optString("source")?.takeIf { it.isNotBlank() }
                                ?: payload.optJSONObject("metadata")?.optString("provider")?.takeIf { it.isNotBlank() }
                                ?: "LyricsPlus (KPoe)"
                            allResults.add(LyricsSourceResult(lines = lines, source = sourceLabel))
                        }
                    }
                }
            }
        }

        logI("LyricsPlus : ${allResults.size} sources collectées en ${System.currentTimeMillis() - started} ms")
        mergeAndSortSources(allResults)
    }

    /**
     * Récupère les paroles depuis LRCLIB (recherche avec titre et artiste).
     */
    suspend fun fetchLyricsFromLrclib(metadata: SongMetadata): LyricsSourceResult? = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val title = metadata.title.trim()
        val artist = metadata.artist.trim()
        if (title.isEmpty() || artist.isEmpty()) return@withContext null

        try {
            withTimeout(FETCH_TIMEOUT_MS) {
                val searchQuery = "$artist $title"
                val url = "https://lrclib.net/api/search?q=${urlEncode(searchQuery)}"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "apple-music-web-components/$VERSION")
                    .build()

                val resp = http.newCall(req).execute()
                if (!resp.isSuccessful) return@withTimeout null

                val bodyStr = resp.body?.string().orEmpty()
                val resultsArr = JSONArray(bodyStr)
                if (resultsArr.length() == 0) return@withTimeout null

                val resultsList = (0 until resultsArr.length()).mapNotNull { resultsArr.optJSONObject(it) }
                val withSynced = resultsList.firstOrNull { it.optString("syncedLyrics").isNotBlank() }
                val best = withSynced ?: resultsList[0]

                val synced = best.optString("syncedLyrics")
                if (synced.isNotBlank()) {
                    val lines = LyricsParsers.parseLrcSubtitles(synced)
                    if (lines.isNotEmpty()) {
                        logI("LRCLIB : trouvé synchronisé (${lines.size} lignes) en ${System.currentTimeMillis() - started} ms")
                        return@withTimeout LyricsSourceResult(lines = lines, source = "LRCLIB")
                    }
                }

                val plain = best.optString("plainLyrics")
                if (plain.isNotBlank()) {
                    val plainLines = plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (plainLines.isNotEmpty()) {
                        val lines = plainLines.map { text ->
                            LyricsLine(
                                text = listOf(Syllable(text = text, part = false, timestamp = 0L, endtime = 0L)),
                                background = false,
                                backgroundText = emptyList(),
                                oppositeTurn = false,
                                timestamp = 0L,
                                endtime = 0L,
                                isWordSynced = false,
                            )
                        }
                        logI("LRCLIB : trouvé non-synchronisé (${lines.size} lignes) en ${System.currentTimeMillis() - started} ms")
                        return@withTimeout LyricsSourceResult(lines = lines, source = "LRCLIB (unsynced)")
                    }
                }
                logI("LRCLIB : vide en ${System.currentTimeMillis() - started} ms")
                null
            }
        } catch (e: Exception) {
            logW("LRCLIB : erreur ${e.message} en ${System.currentTimeMillis() - started} ms")
            null
        }
    }

    /**
     * Récupère les paroles brutes depuis Genius (via le worker de proxy).
     */
    suspend fun fetchLyricsFromGenius(metadata: SongMetadata): LyricsSourceResult? = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val title = metadata.title.trim()
        val artist = metadata.artist.trim()
        if (title.isEmpty() || artist.isEmpty()) return@withContext null

        try {
            withTimeout(FETCH_TIMEOUT_MS) {
                val url = "$GENIUS_WORKER_URL?title=${urlEncode(title)}&artist=${urlEncode(artist)}"
                val req = Request.Builder().url(url).build()
                val resp = http.newCall(req).execute()
                if (!resp.isSuccessful) return@withTimeout null

                val json = JSONObject(resp.body?.string().orEmpty())
                val rawLyrics = decodeHtmlEntities(json.optString("lyrics"))
                if (rawLyrics.isBlank()) return@withTimeout null
                val foundArtist = decodeHtmlEntities(json.optString("artist"))
                val foundTitle = decodeHtmlEntities(json.optString("title"))
                if (!sameSong(foundArtist, foundTitle, artist, title)) {
                    logI("Genius : autre morceau ($foundArtist - $foundTitle), ignoré")
                    return@withTimeout null
                }

                val plainLines = rawLyrics.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("[") }

                if (plainLines.isNotEmpty()) {
                    val lines = plainLines.map { text ->
                        LyricsLine(
                            text = listOf(Syllable(text = text, part = false, timestamp = 0L, endtime = 0L)),
                            background = false,
                            backgroundText = emptyList(),
                            oppositeTurn = false,
                            timestamp = 0L,
                            endtime = 0L,
                            isWordSynced = false,
                        )
                    }
                    logI("Genius : trouvé (${lines.size} lignes) en ${System.currentTimeMillis() - started} ms")
                    return@withTimeout LyricsSourceResult(lines = lines, source = "Genius")
                }
                logI("Genius : vide en ${System.currentTimeMillis() - started} ms")
                null
            }
        } catch (e: Exception) {
            logW("Genius : erreur ${e.message} en ${System.currentTimeMillis() - started} ms")
            null
        }
    }

    // --- Fonctions de classement et de tri des sources ---

    /**
     * Détermine si les lignes comportent des paroles synchronisées mot par mot.
     */
    fun hasWordSyncedLyrics(lines: List<LyricsLine>): Boolean {
        return lines.any { line ->
            line.text.any { s -> s.text.trim().isNotEmpty() && (s.timestamp > 0 || s.endtime > 0) } &&
                (line.isWordSynced ?: (line.text.size > 1 && !line.text.all { it.lineSynced }))
        }
    }

    /**
     * Rang de synchronisation : 0 pour mot par mot, 1 pour ligne par ligne, 2 pour texte brut.
     */
    fun getTimingRank(lines: List<LyricsLine>): Int {
        if (hasWordSyncedLyrics(lines)) return 0
        return if (lines.any { line -> line.text.any { s -> s.text.trim().isNotEmpty() } && (line.timestamp > 0 || line.endtime > 0) }) 1 else 2
    }

    /**
     * Rang intrinsèque de chaque source de données selon le barème am-lyrics.
     */
    fun getRankForCollected(sourceLabel: String): Double {
        val lower = sourceLabel.trim().lowercase()
        if (lower == "lrc.red") return 0.0
        if (lower.startsWith("spicy lyrics")) return 2.5
        if (lower.contains("apple")) return 1.0
        if (lower.contains("bini")) return 2.0
        if (lower.contains("unison")) return 3.0
        if (lower.contains("qq") || lower.contains("lyricsplus")) return 4.0
        if (lower.contains("musixmatch")) return 5.0
        if (lower.contains("lrclib")) return 6.0
        if (lower.contains("genius")) return 8.0
        return 7.0
    }

    /**
     * Compare deux sources : mot > ligne > brut, puis humain > modèle IA, puis rang de la source.
     */
    fun compareSources(a: LyricsSourceResult, b: LyricsSourceResult): Int {
        val timingDiff = getTimingRank(a.lines) - getTimingRank(b.lines)
        if (timingDiff != 0) return timingDiff

        val modelDiff = (if (a.byModel) 1 else 0) - (if (b.byModel) 1 else 0)
        if (modelDiff != 0) return modelDiff

        return getRankForCollected(a.source).compareTo(getRankForCollected(b.source))
    }

    fun getDisplaySourceLabel(sourceLabel: String): String {
        return if (sourceLabel.lowercase().contains("lyricsplus")) "QQ" else sourceLabel
    }

    fun getSourceKey(sourceLabel: String?): String {
        val lower = sourceLabel.orEmpty().trim().lowercase()
        if (lower.isEmpty()) return ""
        if (lower.contains("lyricsplus") || lower == "qq") return "qq"
        return lower.replace(Regex("""\s+"""), " ")
    }

    /**
     * Fusionne les sources collectées, élimine les doublons par clé de source en gardant la meilleure,
     * et trie la liste finale selon les critères de priorité.
     */
    fun mergeAndSortSources(collectedSources: List<LyricsSourceResult>): List<LyricsSourceResult> {
        val uniqueMap = mutableMapOf<String, LyricsSourceResult>()

        for (source in collectedSources) {
            if (!source.lines.any { line -> line.text.any { s -> s.text.trim().isNotEmpty() } }) continue
            val key = getSourceKey(source.source)
            val previous = uniqueMap[key]
            if (previous == null || compareSources(source, previous) < 0) {
                uniqueMap[key] = source.copy(source = getDisplaySourceLabel(source.source))
            }
        }

        return uniqueMap.values.sortedWith(::compareSources)
    }
}
