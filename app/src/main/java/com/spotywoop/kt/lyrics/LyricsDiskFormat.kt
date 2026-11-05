package com.spotywoop.kt.lyrics

import org.json.JSONArray
import org.json.JSONObject

/** Conversion JSON d'un résultat de cascade, pour rouvrir les paroles hors connexion à l'identique. */
internal object LyricsDiskFormat {

    fun encode(state: LyricsState): String = JSONObject()
        .put("index", state.currentSourceIndex)
        .put("hasFetchedAll", state.hasFetchedAllProviders)
        .put("songwriters", state.songwriters)
        .put("sources", JSONArray(state.availableSources.map(::sourceToJson)))
        .toString()

    /** Null si le contenu est illisible ou sans paroles : la cascade reprend alors normalement. */
    fun decode(text: String): LyricsState? = runCatching {
        val o = JSONObject(text)
        val sources = o.getJSONArray("sources").objects().map(::jsonToSource)
        val index = o.optInt("index").coerceIn(0, (sources.size - 1).coerceAtLeast(0))
        val current = sources.getOrNull(index) ?: return null
        if (current.lines.isEmpty()) return null
        LyricsState(
            lyrics = current.lines,
            currentSource = current,
            availableSources = sources,
            currentSourceIndex = index,
            hasFetchedAllProviders = o.optBoolean("hasFetchedAll"),
            songwriters = o.str("songwriters"),
        )
    }.getOrNull()

    private fun sourceToJson(s: LyricsSourceResult) = JSONObject()
        .put("lines", JSONArray(s.lines.map(::lineToJson)))
        .put("source", s.source)
        .put("ttml", s.originalTTML)
        .put("songwriters", s.songwriters)
        .put("byModel", s.byModel)
        .put("isrc", s.isrc)
        .put("metadata", s.metadata?.let {
            JSONObject()
                .put("title", it.title)
                .put("artist", it.artist)
                .put("album", it.album)
                .put("durationMs", it.durationMs)
                .put("songwriters", it.songwriters)
        })
        .put("contributors", s.contributors?.let { list ->
            JSONArray(list.map {
                JSONObject()
                    .put("username", it.username)
                    .put("url", it.url)
                    .put("avatar", it.avatar)
                    .put("roles", JSONArray(it.roles))
            })
        })

    private fun jsonToSource(o: JSONObject) = LyricsSourceResult(
        lines = o.getJSONArray("lines").objects().map(::jsonToLine),
        source = o.getString("source"),
        originalTTML = o.str("ttml"),
        songwriters = o.str("songwriters"),
        byModel = o.optBoolean("byModel"),
        isrc = o.str("isrc"),
        metadata = o.optJSONObject("metadata")?.let {
            SongMetadata(
                title = it.getString("title"),
                artist = it.getString("artist"),
                album = it.str("album"),
                durationMs = if (it.has("durationMs")) it.getLong("durationMs") else null,
                songwriters = it.str("songwriters"),
            )
        },
        contributors = o.optJSONArray("contributors")?.objects()?.map {
            val roles = it.optJSONArray("roles")
            LyricsContributor(
                username = it.getString("username"),
                url = it.str("url"),
                avatar = it.str("avatar"),
                roles = (0 until (roles?.length() ?: 0)).map { i -> roles!!.getString(i) },
            )
        },
    )

    private fun lineToJson(l: LyricsLine) = JSONObject()
        .put("text", JSONArray(l.text.map(::syllableToJson)))
        .put("background", l.background)
        .put("backgroundText", JSONArray(l.backgroundText.map(::syllableToJson)))
        .put("oppositeTurn", l.oppositeTurn)
        .put("timestamp", l.timestamp)
        .put("endtime", l.endtime)
        .put("isWordSynced", l.isWordSynced)
        .put("alignment", l.alignment)
        .put("songPart", l.songPart)
        .put("romanizedText", l.romanizedText)
        .put("translation", l.translation)
        .put("agentId", l.agentId)
        .put("direction", l.direction)

    private fun jsonToLine(o: JSONObject) = LyricsLine(
        text = o.getJSONArray("text").objects().map(::jsonToSyllable),
        background = o.optBoolean("background"),
        backgroundText = o.getJSONArray("backgroundText").objects().map(::jsonToSyllable),
        oppositeTurn = o.optBoolean("oppositeTurn"),
        timestamp = o.getLong("timestamp"),
        endtime = o.getLong("endtime"),
        isWordSynced = if (o.has("isWordSynced")) o.getBoolean("isWordSynced") else null,
        alignment = o.str("alignment"),
        songPart = o.str("songPart"),
        romanizedText = o.str("romanizedText"),
        translation = o.str("translation"),
        agentId = o.str("agentId"),
        direction = o.str("direction"),
    )

    private fun syllableToJson(s: Syllable) = JSONObject()
        .put("text", s.text)
        .put("part", s.part)
        .put("timestamp", s.timestamp)
        .put("endtime", s.endtime)
        .put("romanizedText", s.romanizedText)
        .put("lineSynced", s.lineSynced)

    private fun jsonToSyllable(o: JSONObject) = Syllable(
        text = o.getString("text"),
        part = o.optBoolean("part"),
        timestamp = o.getLong("timestamp"),
        endtime = o.getLong("endtime"),
        romanizedText = o.str("romanizedText"),
        lineSynced = o.optBoolean("lineSynced"),
    )

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
}
