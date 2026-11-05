package com.spotywoop.kt.lyrics

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Analyseurs de paroles pour tous les formats supportés par am-lyrics :
 * - TTML (Apple Music, lrc.red, Unison, BiniLyrics)
 * - JSON lrc.red (vocal, chœurs, mot par mot)
 * - Spicy Lyrics (Syllable, Line, Static)
 * - KPoe / LyricsPlus (v2 lyrics/get)
 * - Sous-titres LRC ([mm:ss.xx] / [mm:ss.xxx])
 * - Genius et Unison
 */
object LyricsParsers {

    /**
     * Parse un horodatage TTML en millisecondes Long.
     * Prend en charge les unités (ms, s, m, h) ainsi que les formats sexagésimaux (HH:MM:SS.mmm, MM:SS.mmm, SS.mmm).
     */
    fun parseTTMLTime(value: String?, fallback: Long = 0L): Long {
        if (value.isNullOrBlank()) return fallback
        val normalized = value.trim().lowercase()

        val unitRegex = Regex("""^(-?\d+(?:\.\d+)?)(ms|h|m|s)$""")
        val unitMatch = unitRegex.matchEntire(normalized)
        if (unitMatch != null) {
            val amount = unitMatch.groupValues[1].toDoubleOrNull() ?: return fallback
            val unit = unitMatch.groupValues[2]
            val multiplier = when (unit) {
                "ms" -> 1.0
                "s" -> 1000.0
                "m" -> 60_000.0
                "h" -> 3_600_000.0
                else -> 1000.0
            }
            return maxOf(0L, Math.round(amount * multiplier))
        }

        val parts = normalized.split(':').map { it.toDoubleOrNull() }
        if (parts.any { it == null || !it.isFinite() }) return fallback

        val seconds = when (parts.size) {
            3 -> parts[0]!! * 3600.0 + parts[1]!! * 60.0 + parts[2]!!
            2 -> parts[0]!! * 60.0 + parts[1]!!
            1 -> parts[0]!!
            else -> return fallback
        }
        return maxOf(0L, Math.round(seconds * 1000.0))
    }

    /**
     * Détermine si une langue s'écrit de droite à gauche (RTL).
     */
    fun isRightToLeftLanguage(language: String?): Boolean {
        if (language.isNullOrBlank()) return false
        val primary = language.lowercase().split('-', '_')[0]
        return primary in setOf("ar", "dv", "fa", "he", "ku", "ps", "ur", "yi")
    }

    /**
     * Calcule l'alignement des lignes (gauche 'start' ou droite 'end') pour les duos et chœurs.
     * Règles identiques à AmLyrics.calculateLineAlignments :
     * - v1000 -> group ('start')
     * - v2000 -> other ('end' initial)
     * - autre -> person (alterne à chaque changement de chanteur)
     * - si >= 85% à droite, inverse tout.
     */
    fun calculateLineAlignments(
        lineSingers: List<String?>,
        agentTypes: Map<String, String>,
    ): List<String?> {
        val lineSideAssignments = MutableList<String?>(lineSingers.size) { null }
        var currentSideIsLeft = true
        var lastPersonSingerId: String? = null
        var rightCount = 0
        var totalCount = 0

        lineSingers.forEachIndexed { index, singerId ->
            var sideClass: String? = null
            if (singerId != null) {
                var type = agentTypes[singerId]
                if (type == null) {
                    type = when (singerId) {
                        "v1000" -> "group"
                        "v2000" -> "other"
                        else -> "person"
                    }
                }

                if (type == "group") {
                    sideClass = "start"
                } else {
                    if (lastPersonSingerId == null) {
                        currentSideIsLeft = (type != "other")
                    } else if (singerId != lastPersonSingerId) {
                        currentSideIsLeft = !currentSideIsLeft
                    }
                    sideClass = if (currentSideIsLeft) "start" else "end"
                    lastPersonSingerId = singerId
                }
            }

            if (sideClass != null) {
                totalCount += 1
                if (sideClass == "end") rightCount += 1
            }
            lineSideAssignments[index] = sideClass
        }

        if (totalCount > 0 && Math.round((rightCount.toDouble() / totalCount) * 100.0) >= 85) {
            for (i in 0 until lineSideAssignments.size) {
                lineSideAssignments[i] = when (lineSideAssignments[i]) {
                    "start" -> "end"
                    "end" -> "start"
                    else -> null
                }
            }
        }

        return lineSideAssignments
    }

    /**
     * Résultat de l'analyse TTML.
     */
    data class TTMLParseResult(
        val lines: List<LyricsLine>,
        val songwriters: String? = null,
    )

    /**
     * Analyse un document TTML en utilisant XmlPullParser ou le parseur DOM standard.
     */
    fun parseTTML(ttmlString: String): TTMLParseResult? {
        if (ttmlString.isBlank()) return null
        // 1. Tente d'abord l'analyseur XmlPull si l'environnement Android est actif
        val pullResult = runCatching { parseTTMLWithPull(ttmlString) }
        if (pullResult.isSuccess && pullResult.getOrNull() != null) {
            return pullResult.getOrNull()
        }
        // 2. Repli sur le DOM standard (identique au DOMParser de la référence TypeScript)
        return runCatching { parseTTMLDom(ttmlString) }.getOrNull()
    }

    /**
     * Implémentation TTML via XmlPullParser d'Android.
     */
    fun parseTTMLWithPull(ttmlString: String): TTMLParseResult? {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(ttmlString))

        // Si XmlPullParserFactory réussit, on délègue au parseur DOM pour la résolution
        // exacte de l'arbre et des nœuds frères de translitération, ou on exécute l'arbre.
        return parseTTMLDom(ttmlString)
    }

    /**
     * Implémentation TTML complète via DOM, fidèle ligne par ligne à AmLyrics.parseTTML.
     */
    fun parseTTMLDom(ttmlString: String): TTMLParseResult? {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(ttmlString.toByteArray(Charsets.UTF_8)))

        val translations = mutableMapOf<String, String>()
        data class TranslitEntry(val text: String, val syllabus: List<Map<String, Any>>? = null)
        val transliterations = mutableMapOf<String, TranslitEntry>()
        val agentMap = mutableMapOf<String, String>()

        val root = doc.documentElement
        val documentLanguage = getAttr(root, "xml:lang", "lang")

        // ttm:agent
        val agentNodes = doc.getElementsByTagName("ttm:agent")
        for (i in 0 until agentNodes.length) {
            val a = agentNodes.item(i) as? Element ?: continue
            val id = getAttr(a, "xml:id", "id")
            val type = getAttr(a, "type")
            if (id != null && type != null) {
                agentMap[id] = type
            }
        }
        if (agentMap.isEmpty()) {
            val fallbackAgents = doc.getElementsByTagName("agent")
            for (i in 0 until fallbackAgents.length) {
                val a = fallbackAgents.item(i) as? Element ?: continue
                val id = getAttr(a, "xml:id", "id")
                val type = getAttr(a, "type")
                if (id != null && type != null) agentMap[id] = type
            }
        }

        // songwriters
        var songwriters: String? = null
        val swNodes = doc.getElementsByTagName("songwriter")
        if (swNodes.length > 0) {
            val names = mutableListOf<String>()
            for (i in 0 until swNodes.length) {
                val t = swNodes.item(i).textContent?.trim()
                if (!t.isNullOrEmpty()) names.add(t)
            }
            if (names.isNotEmpty()) {
                songwriters = names.joinToString(", ")
            }
        }

        // translations
        val transNodes = doc.getElementsByTagName("translation")
        for (i in 0 until transNodes.length) {
            val tElem = transNodes.item(i) as? Element ?: continue
            val textNodes = tElem.getElementsByTagName("text")
            for (j in 0 until textNodes.length) {
                val textNode = textNodes.item(j) as? Element ?: continue
                val key = getAttr(textNode, "for")
                val content = textNode.textContent
                if (key != null && content != null) {
                    translations[key] = content
                }
            }
        }

        // transliterations
        val translitNodes = doc.getElementsByTagName("transliteration")
        for (i in 0 until translitNodes.length) {
            val trElem = translitNodes.item(i) as? Element ?: continue
            val textNodes = trElem.getElementsByTagName("text")
            for (j in 0 until textNodes.length) {
                val textNode = textNodes.item(j) as? Element ?: continue
                val key = getAttr(textNode, "for") ?: continue
                val spans = mutableListOf<Element>()
                val allSpans = textNode.getElementsByTagName("span")
                for (k in 0 until allSpans.length) {
                    val s = allSpans.item(k) as? Element ?: continue
                    if (getAttr(s, "begin") != null) spans.add(s)
                }

                if (spans.isNotEmpty()) {
                    val syls = mutableListOf<Map<String, Any>>()
                    var fullText = ""
                    for (span in spans) {
                        val begin = getAttr(span, "begin")
                        val end = getAttr(span, "end")
                        var spanText = span.textContent ?: ""
                        val next = span.nextSibling
                        if (next != null && next.nodeType == Node.TEXT_NODE && (next.textContent ?: "").startsWith(" ") && !spanText.endsWith(" ")) {
                            spanText += " "
                        }
                        if (spanText.trim().isEmpty()) continue
                        val bMs = parseTTMLTime(begin)
                        val eMs = parseTTMLTime(end)
                        syls.add(mapOf("time" to bMs, "duration" to (eMs - bMs), "text" to spanText))
                        fullText += spanText
                    }
                    transliterations[key] = TranslitEntry(fullText.trim(), syls)
                } else if (!textNode.textContent.isNullOrBlank()) {
                    transliterations[key] = TranslitEntry(textNode.textContent.trim().replace(Regex("""\s+"""), " "))
                }
            }
        }

        val lines = mutableListOf<LyricsLine>()
        val pNodes = doc.getElementsByTagName("p")

        for (i in 0 until pNodes.length) {
            val p = pNodes.item(i) as? Element ?: continue
            val key = getAttr(p, "lrc:key", "itunes:key", "key")
            val beginMs = parseTTMLTime(getAttr(p, "begin"))
            val endMs = parseTTMLTime(getAttr(p, "end"), beginMs)
            val agentId = getAttr(p, "ttm:agent", "agent")
            val lineLanguage = getAttr(p, "xml:lang", "lang") ?: documentLanguage

            var songPart: String? = null
            var parent = p.parentNode
            while (parent != null) {
                if (parent is Element && parent.tagName.lowercase().endsWith("div")) {
                    songPart = getAttr(parent, "lrc:songPart", "itunes:songPart", "itunes:song-part", "songPart", "song-part")
                    if (songPart != null) break
                }
                parent = parent.parentNode
            }

            val mainSyllables = mutableListOf<Syllable>()
            val bgSyllables = mutableListOf<Syllable>()

            val allSpans = p.getElementsByTagName("span")
            var hasWordLevelSync = false
            for (j in 0 until allSpans.length) {
                val s = allSpans.item(j) as? Element ?: continue
                val role = getAttr(s, "ttm:role", "role")
                val parentRole = (s.parentNode as? Element)?.let { getAttr(it, "ttm:role", "role") }
                val isBg = (role == "x-bg" || parentRole == "x-bg")
                if (!isBg && getAttr(s, "begin") != null && getAttr(s, "end") != null) {
                    hasWordLevelSync = true
                    break
                }
            }

            if (allSpans.length > 0) {
                for (j in 0 until allSpans.length) {
                    val span = allSpans.item(j) as? Element ?: continue
                    val role = getAttr(span, "ttm:role", "role")

                    // Chœur avec sous-spans
                    if (role == "x-bg") {
                        val bgInner = span.getElementsByTagName("span")
                        for (k in 0 until bgInner.length) {
                            val bgSpan = bgInner.item(k) as? Element ?: continue
                            var bgText = bgSpan.textContent ?: ""
                            val nextNode = bgSpan.nextSibling
                            if (nextNode != null && nextNode.nodeType == Node.TEXT_NODE &&
                                (nextNode.textContent ?: "").startsWith(" ") && !bgText.endsWith(" ")
                            ) {
                                bgText += " "
                            }
                            val bgTimestamp = parseTTMLTime(getAttr(bgSpan, "begin"), beginMs)
                            val bgEndtime = maxOf(bgTimestamp, parseTTMLTime(getAttr(bgSpan, "end"), endMs))
                            bgSyllables.add(
                                Syllable(
                                    text = bgText,
                                    timestamp = bgTimestamp,
                                    endtime = bgEndtime,
                                    part = !bgText.endsWith(" "),
                                ),
                            )
                        }
                        continue
                    }

                    // Ignorer les spans enfants déjà traités dans un conteneur x-bg
                    val parentElem = span.parentNode as? Element
                    if (parentElem != null && getAttr(parentElem, "ttm:role", "role") == "x-bg") {
                        continue
                    }

                    var text = span.textContent ?: ""
                    val nextNode = span.nextSibling
                    if (nextNode != null && nextNode.nodeType == Node.TEXT_NODE &&
                        (nextNode.textContent ?: "").startsWith(" ") && !text.endsWith(" ")
                    ) {
                        text += " "
                    }
                    val syllableTimestamp = parseTTMLTime(getAttr(span, "begin"), beginMs)
                    val syllableEndtime = maxOf(syllableTimestamp, parseTTMLTime(getAttr(span, "end"), endMs))
                    mainSyllables.add(
                        Syllable(
                            text = text,
                            timestamp = syllableTimestamp,
                            endtime = syllableEndtime,
                            part = !text.endsWith(" "),
                        ),
                    )
                }
            }

            // Si aucune syllabe mot par mot, ligne entière synchronisée
            if (mainSyllables.isEmpty()) {
                val primaryLineText = StringBuilder()
                val childNodes = p.childNodes
                for (ci in 0 until childNodes.length) {
                    val node = childNodes.item(ci)
                    if (node is Element && getAttr(node, "ttm:role", "role") == "x-bg") continue
                    primaryLineText.append(node.textContent ?: "")
                }
                mainSyllables.add(
                    Syllable(
                        text = primaryLineText.toString().trim(),
                        timestamp = beginMs,
                        endtime = endMs,
                        part = false,
                        lineSynced = true,
                    ),
                )
            }

            // Distribution de la romanisation / translitération aux syllabes
            val lineTranslit = if (key != null) transliterations[key] else null
            if (lineTranslit != null && mainSyllables.size > 1 && allSpans.length > 0) {
                if (lineTranslit.syllabus != null && lineTranslit.syllabus.size == mainSyllables.size) {
                    mainSyllables.forEachIndexed { mapIdx, syl ->
                        syl.romanizedText = lineTranslit.syllabus[mapIdx]["text"] as? String
                    }
                } else {
                    val romanWords = lineTranslit.text.split(Regex("""\s+""")).filter { it.isNotEmpty() }
                    val syllableGroups = mutableListOf<MutableList<Int>>()
                    for (si in 0 until mainSyllables.size) {
                        if (mainSyllables[si].part && syllableGroups.isNotEmpty()) {
                            syllableGroups.last().add(si)
                        } else {
                            syllableGroups.add(mutableListOf(si))
                        }
                    }

                    val fullLine = mainSyllables.joinToString("") { it.text }
                    val isCJK = Regex("""[\u4e00-\u9fff\u3040-\u309f\u30a0-\u30ff\uac00-\ud7af]""").containsMatchIn(fullLine)

                    if (romanWords.size == syllableGroups.size) {
                        syllableGroups.forEachIndexed { gi, group ->
                            mainSyllables[group[0]].romanizedText = romanWords[gi]
                        }
                    } else if (romanWords.size == mainSyllables.size) {
                        mainSyllables.forEachIndexed { mapIdx, syl ->
                            syl.romanizedText = romanWords[mapIdx]
                        }
                    } else if (isCJK) {
                        var romanIdx = 0
                        for (group in syllableGroups) {
                            val syl = mainSyllables[group[0]]
                            val sylText = group.joinToString("") { mainSyllables[it].text }
                            val validChars = Regex("""[\u4e00-\u9fff\u3040-\u309f\u30a0-\u30ff\uac00-\ud7afA-Za-z0-9]""").findAll(sylText).toList()
                            val needed = validChars.size
                            if (needed > 0 && romanIdx < romanWords.size) {
                                val endIdx = minOf(romanWords.size, romanIdx + needed)
                                syl.romanizedText = romanWords.subList(romanIdx, endIdx).joinToString(" ")
                                romanIdx += needed
                            }
                        }
                    }
                }
            }

            val resolvedBeginMs = if (getAttr(p, "begin") != null) beginMs else mainSyllables.minOfOrNull { it.timestamp } ?: beginMs
            val allEnds = mutableListOf(endMs, resolvedBeginMs)
            allEnds.addAll(mainSyllables.map { it.endtime })
            allEnds.addAll(bgSyllables.map { it.endtime })
            val resolvedEndMs = allEnds.maxOrNull() ?: endMs

            val isRtl = getAttr(p, "dir") == "rtl" || isRightToLeftLanguage(lineLanguage)

            lines.add(
                LyricsLine(
                    text = mainSyllables,
                    background = bgSyllables.isNotEmpty(),
                    backgroundText = bgSyllables,
                    timestamp = resolvedBeginMs,
                    endtime = resolvedEndMs,
                    isWordSynced = hasWordLevelSync,
                    songPart = songPart,
                    translation = if (key != null) translations[key] else null,
                    romanizedText = lineTranslit?.text,
                    oppositeTurn = false,
                    agentId = agentId,
                    direction = if (isRtl) "rtl" else null,
                ),
            )
        }

        val sortedLines = lines.mapIndexed { idx, line -> Pair(line, idx) }
            .sortedWith(compareBy({ it.first.timestamp }, { it.second }))
            .map { it.first }

        val alignments = calculateLineAlignments(
            sortedLines.map { it.agentId },
            agentMap,
        )

        val alignedLines = sortedLines.mapIndexed { index, line ->
            val align = alignments.getOrNull(index)
            line.copy(
                alignment = align,
                oppositeTurn = (align == "end"),
            )
        }

        return TTMLParseResult(lines = alignedLines, songwriters = songwriters)
    }

    /**
     * Analyse la réponse JSON de lrc.red (vocal et mot par mot).
     * Portage fidèle de AmLyrics.parseLrcRedJson.
     */
    fun parseLrcRedJson(song: JSONObject): List<LyricsLine> {
        val lyricsObj = song.optJSONObject("lyrics") ?: return emptyList()
        val entries = lyricsObj.optJSONArray("lines") ?: return emptyList()
        val syncType = lyricsObj.optString("sync")

        val result = mutableListOf<LyricsLine>()
        for (i in 0 until entries.length()) {
            val line = entries.optJSONObject(i) ?: continue
            val rawText = line.optString("text")
            if (rawText.isBlank()) continue

            val timestamp = secondsToMs(line.opt("begin"))
            val endtime = secondsToMs(line.opt("end"), timestamp)
            val wordsArray = line.optJSONArray("words")
            val wordSynced = syncType == "word" && wordsArray != null && wordsArray.length() > 0

            val textSyllables: List<Syllable> = if (wordSynced) {
                val syls = mutableListOf<Syllable>()
                for (wIdx in 0 until wordsArray.length()) {
                    val wordGroup = wordsArray.optJSONArray(wIdx) ?: continue
                    for (sIdx in 0 until wordGroup.length()) {
                        val sylObj = wordGroup.optJSONObject(sIdx) ?: continue
                        val part = sIdx < wordGroup.length() - 1
                        val space = if (!part && wIdx < wordsArray.length() - 1) " " else ""
                        val sylText = sylObj.optString("text") + space
                        syls.add(
                            Syllable(
                                text = sylText,
                                part = part,
                                timestamp = secondsToMs(sylObj.opt("begin"), timestamp),
                                endtime = secondsToMs(sylObj.opt("end"), endtime),
                            ),
                        )
                    }
                }
                syls
            } else {
                listOf(
                    Syllable(
                        text = rawText,
                        part = false,
                        timestamp = timestamp,
                        endtime = endtime,
                        lineSynced = true,
                    ),
                )
            }

            if (textSyllables.isNotEmpty()) {
                val voice = line.optString("voice")
                val partName = line.optString("part").takeIf { it.isNotBlank() }
                result.add(
                    LyricsLine(
                        text = textSyllables,
                        background = false,
                        backgroundText = emptyList(),
                        oppositeTurn = (voice == "b"),
                        agentId = voice.takeIf { it.isNotBlank() },
                        songPart = partName,
                        timestamp = timestamp,
                        endtime = endtime,
                        isWordSynced = wordSynced,
                    ),
                )
            }
        }
        return result
    }

    /**
     * Parse les contributeurs de Spicy Lyrics (UploadAttribution).
     */
    private fun parseSpicyContributors(attribution: JSONObject?): List<LyricsContributor> {
        if (attribution == null) return emptyList()
        val people = mutableMapOf<String, LyricsContributor>()
        val keys = attribution.keys()
        while (keys.hasNext()) {
            val role = keys.next()
            val value = attribution.opt(role)
            val entries = when (value) {
                is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
                is JSONObject -> listOf(value)
                else -> emptyList()
            }
            for (person in entries) {
                val username = person.optString("username").trim()
                if (username.isEmpty()) continue
                val url = person.optString("url").takeIf { it.startsWith("http") }
                val avatar = person.optString("avatar").takeIf { it.startsWith("http") }
                val personId = person.optString("id").takeIf { it.isNotEmpty() }
                val key = personId ?: url ?: username
                val existing = people[key]
                if (existing != null) {
                    if (role !in existing.roles) {
                        people[key] = existing.copy(roles = existing.roles + role)
                    }
                } else {
                    people[key] = LyricsContributor(
                        username = username,
                        url = url,
                        avatar = avatar,
                        roles = listOf(role),
                    )
                }
            }
        }
        return people.values.toList()
    }

    /**
     * Analyse la réponse de Spicy Lyrics.
     * Portage fidèle de parseSpicyLyrics dans LyricsSources.ts.
     */
    fun parseSpicyLyrics(payload: JSONObject?): LyricsSourceResult? {
        if (payload == null) return null
        val body = payload.optJSONObject("Body") ?: return null
        val type = body.optString("Type")
        if (type !in listOf("Syllable", "Line", "Static")) return null

        val wordSynced = (type == "Syllable")
        val untimed = (type == "Static")
        val content = if (untimed) body.optJSONArray("Lines") else body.optJSONArray("Content")
        if (content == null) return null

        fun parseSyllables(group: JSONObject?): List<Syllable> {
            val sylArray = group?.optJSONArray("Syllables") ?: return emptyList()
            val valid = mutableListOf<JSONObject>()
            for (i in 0 until sylArray.length()) {
                val s = sylArray.optJSONObject(i) ?: continue
                if (s.optString("Text").isNotEmpty()) valid.add(s)
            }
            return valid.mapIndexed { index, s ->
                val isPart = s.optBoolean("IsPartOfWord")
                val space = if (!isPart && index < valid.size - 1) " " else ""
                val text = s.optString("Text") + space
                val translit = s.optString("TransliteratedText").takeIf { it.isNotEmpty() }
                Syllable(
                    text = text,
                    part = isPart,
                    timestamp = secondsToMs(s.opt("StartTime"), secondsToMs(group.opt("StartTime"))),
                    endtime = secondsToMs(s.opt("EndTime"), secondsToMs(group.opt("EndTime"))),
                    romanizedText = translit?.let { it + space },
                )
            }
        }

        val lines = mutableListOf<LyricsLine>()
        for (i in 0 until content.length()) {
            val entry = content.optJSONObject(i) ?: continue
            if (!untimed && entry.optString("Type") != "Vocal") continue
            val lead = if (wordSynced) entry.optJSONObject("Lead") else entry
            if (lead == null) continue

            var text: List<Syllable> = emptyList()
            if (wordSynced) {
                text = parseSyllables(lead)
            } else {
                val leadText = lead.optString("Text")
                if (leadText.isNotBlank()) {
                    text = listOf(
                        Syllable(
                            text = leadText,
                            part = false,
                            timestamp = if (untimed) 0L else secondsToMs(lead.opt("StartTime")),
                            endtime = if (untimed) 0L else secondsToMs(lead.opt("EndTime")),
                            lineSynced = true,
                        ),
                    )
                }
            }
            if (text.isEmpty()) continue

            val bgArray = entry.optJSONArray("Background")
            val bgGroups = mutableListOf<List<Syllable>>()
            if (bgArray != null) {
                for (bIdx in 0 until bgArray.length()) {
                    val g = parseSyllables(bgArray.optJSONObject(bIdx))
                    if (g.isNotEmpty()) bgGroups.add(g)
                }
            }
            val backgroundText = mutableListOf<Syllable>()
            bgGroups.forEachIndexed { index, group ->
                if (index > 0 && backgroundText.isNotEmpty()) {
                    val prev = backgroundText.last()
                    backgroundText[backgroundText.size - 1] = prev.copy(
                        text = prev.text + " ",
                        part = false,
                    )
                }
                backgroundText.addAll(group)
            }

            val timestamp = if (untimed) 0L else secondsToMs(lead.opt("StartTime"), text.minOfOrNull { it.timestamp } ?: 0L)
            val endtime = if (untimed) 0L else secondsToMs(lead.opt("EndTime"), text.maxOfOrNull { it.endtime } ?: 0L)
            val isOpposite = entry.optBoolean("OppositeAligned")

            lines.add(
                LyricsLine(
                    text = text,
                    background = backgroundText.isNotEmpty(),
                    backgroundText = backgroundText,
                    oppositeTurn = isOpposite,
                    alignment = if (isOpposite) "end" else "start",
                    timestamp = timestamp,
                    endtime = maxOf(timestamp, endtime),
                    isWordSynced = wordSynced,
                    romanizedText = lead.optString("TransliteratedText").takeIf { it.isNotEmpty() },
                    translation = lead.optString("TranslatedText").takeIf { it.isNotEmpty() },
                ),
            )
        }

        if (lines.isEmpty()) return null

        val catalogSource = body.optString("source")
        val catalogue = when (catalogSource) {
            "apple_music" -> " (Apple Music)"
            "spotify" -> " (Spotify)"
            else -> ""
        }

        val swArray = body.optJSONArray("SongWriters")
        val songwriters = if (swArray != null) {
            (0 until swArray.length()).mapNotNull { swArray.optString(it).takeIf { s -> s.isNotBlank() } }.joinToString(", ").takeIf { it.isNotEmpty() }
        } else null

        val contributors = if (catalogSource == "spicy_lyrics") {
            parseSpicyContributors(body.optJSONObject("UploadAttribution"))
        } else null

        return LyricsSourceResult(
            lines = lines,
            source = "Spicy Lyrics$catalogue",
            songwriters = songwriters,
            contributors = contributors,
        )
    }

    /**
     * Analyse les paroles KPoe / LyricsPlus (format v2).
     * Portage fidèle de AmLyrics.convertKPoeLyrics.
     */
    fun convertKPoeLyrics(payload: JSONObject?): List<LyricsLine>? {
        if (payload == null) return null

        val rawLyrics = payload.optJSONArray("lyrics")
            ?: payload.optJSONObject("data")?.optJSONArray("lyrics")
            ?: payload.optJSONArray("data")
            ?: return null

        if (rawLyrics.length() == 0) return null

        val sanitizedEntries = (0 until rawLyrics.length()).mapNotNull { rawLyrics.optJSONObject(it) }
        if (sanitizedEntries.isEmpty()) return null

        val type = payload.optString("type")
        val isLineType = type.equals("Line", ignoreCase = true)

        val agentTypes = mutableMapOf<String, String>()
        val agentsObj = payload.optJSONObject("metadata")?.optJSONObject("agents")
        if (agentsObj != null) {
            val keys = agentsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val a = agentsObj.optJSONObject(key) ?: continue
                val mappedKey = a.optString("alias").takeIf { it.isNotEmpty() } ?: key
                val agentType = a.optString("type")
                if (agentType.isNotEmpty()) agentTypes[mappedKey] = agentType
            }
        }

        val lineSingers = sanitizedEntries.map { it.optJSONObject("element")?.optString("singer") }
        val alignments = calculateLineAlignments(lineSingers, agentTypes)

        val lines = mutableListOf<LyricsLine>()
        sanitizedEntries.forEachIndexed { i, entry ->
            val alignment = alignments.getOrNull(i)
            val lineText = entry.optString("text")
            val lineStart = toMilliseconds(entry.opt("time"))
            val lineDuration = toMilliseconds(entry.opt("duration"))
            val explicitEnd = toMilliseconds(entry.opt("endTime"))
            val lineEnd = if (explicitEnd > 0L) explicitEnd else lineStart + lineDuration

            val syllabusArray = entry.optJSONArray("syllabus") ?: entry.optJSONArray("words")
            val mainSyllables = mutableListOf<Syllable>()
            val backgroundSyllables = mutableListOf<Syllable>()

            if (!isLineType && syllabusArray != null && syllabusArray.length() > 0) {
                for (sIdx in 0 until syllabusArray.length()) {
                    val syl = syllabusArray.optJSONObject(sIdx) ?: continue
                    val sylStart = toMilliseconds(syl.opt("time"), lineStart)
                    val sylDuration = toMilliseconds(syl.opt("duration"))
                    val sylEnd = if (sylDuration == 0L && syllabusArray.length() == 1) lineEnd else sylStart + sylDuration

                    val syllable = Syllable(
                        text = syl.optString("text"),
                        part = syl.optBoolean("part"),
                        timestamp = sylStart,
                        endtime = sylEnd,
                    )
                    if (syl.optBoolean("isBackground")) {
                        backgroundSyllables.add(syllable)
                    } else {
                        mainSyllables.add(syllable)
                    }
                }
            }

            if (mainSyllables.isEmpty() && lineText.isNotEmpty()) {
                mainSyllables.add(
                    Syllable(
                        text = lineText,
                        part = false,
                        timestamp = lineStart,
                        endtime = maxOf(lineStart, lineEnd),
                        lineSynced = isLineType,
                    ),
                )
            }

            val hasWordSync = !isLineType && syllabusArray != null && syllabusArray.length() > 0 &&
                (mainSyllables.isNotEmpty() || backgroundSyllables.isNotEmpty())

            val transliteration = entry.optJSONObject("transliteration")
            var romanizedTextFromPayload: String? = null
            if (transliteration != null) {
                romanizedTextFromPayload = transliteration.optString("text").takeIf { it.isNotEmpty() }
                val transSyls = transliteration.optJSONArray("syllabus")
                if (transSyls != null && transSyls.length() == mainSyllables.size) {
                    for (tIdx in 0 until transSyls.length()) {
                        val s = transSyls.optJSONObject(tIdx) ?: continue
                        mainSyllables[tIdx].romanizedText = s.optString("text").takeIf { it.isNotEmpty() }
                    }
                }
            }

            val translationText = entry.optJSONObject("translation")?.optString("text")?.takeIf { it.isNotEmpty() }

            val elem = entry.opt("element")
            val isOpposite = alignment == "end" || when (elem) {
                is JSONArray -> (0 until elem.length()).any { elem.optString(it) in listOf("opposite", "right") }
                is String -> elem in listOf("opposite", "right")
                else -> false
            }

            lines.add(
                LyricsLine(
                    text = mainSyllables,
                    background = backgroundSyllables.isNotEmpty(),
                    backgroundText = backgroundSyllables,
                    oppositeTurn = isOpposite,
                    timestamp = lineStart,
                    endtime = lineEnd,
                    isWordSynced = if (isLineType) false else hasWordSync,
                    alignment = alignment,
                    songPart = entry.optJSONObject("element")?.optString("songPart")?.takeIf { it.isNotEmpty() },
                    romanizedText = romanizedTextFromPayload,
                    translation = translationText,
                ),
            )
        }

        return lines
    }

    /**
     * Analyse les sous-titres au format LRC standard ([mm:ss.xx] ou [mm:ss.xxx]).
     * Portage fidèle de AmLyrics.parseLrcSubtitles.
     */
    fun parseLrcSubtitles(lrc: String?): List<LyricsLine> {
        if (lrc.isNullOrBlank()) return emptyList()

        val parsed = mutableListOf<Pair<Long, String>>()
        val lrcRegex = Regex("""^\[(\d{1,3}):(\d{2})\.(\d{2,3})\]\s?(.*)$""")

        lrc.lineSequence().forEach { line ->
            val match = lrcRegex.matchEntire(line.trim()) ?: return@forEach
            val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
            val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
            val fracStr = match.groupValues[3]
            var centiseconds = fracStr.toLongOrNull() ?: 0L
            if (fracStr.length == 3) {
                centiseconds = Math.round(centiseconds / 10.0)
            }
            val timestamp = (minutes * 60 + seconds) * 1000L + centiseconds * 10L
            val text = match.groupValues[4]
            parsed.add(Pair(timestamp, text))
        }

        val lines = mutableListOf<LyricsLine>()
        for (i in 0 until parsed.size) {
            val (timestamp, text) = parsed[i]
            val endtime = if (i + 1 < parsed.size) parsed[i + 1].first else timestamp + 5000L
            if (text.isBlank()) continue

            lines.add(
                LyricsLine(
                    text = listOf(
                        Syllable(
                            text = text,
                            part = false,
                            timestamp = timestamp,
                            endtime = endtime,
                            lineSynced = true,
                        ),
                    ),
                    background = false,
                    backgroundText = emptyList(),
                    oppositeTurn = false,
                    timestamp = timestamp,
                    endtime = endtime,
                    isWordSynced = false,
                ),
            )
        }
        return lines
    }

    /**
     * Utilitaire pour extraire un attribut en ignorant la casse et le préfixe de namespace éventuel.
     */
    private fun getAttr(elem: Element, vararg names: String): String? {
        for (name in names) {
            if (elem.hasAttribute(name)) {
                val v = elem.getAttribute(name)
                if (v.isNotEmpty()) return v
            }
        }
        val attrs = elem.attributes
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i)
            val localName = attr.nodeName.substringAfter(':')
            for (name in names) {
                val targetLocal = name.substringAfter(':')
                if (localName.equals(targetLocal, ignoreCase = true) && attr.nodeValue.isNotEmpty()) {
                    return attr.nodeValue
                }
            }
        }
        return null
    }
}
