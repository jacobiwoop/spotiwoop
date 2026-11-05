package com.spotywoop.kt.lyrics

import java.util.Locale

/**
 * Fonctions d'export de paroles au format LRC, texte brut et TTML.
 * Formatage identique aux méthodes de am-lyrics (generateLRC, generatePlain, generateTTML).
 */
object LyricsExport {

    /**
     * Formate un horodatage en millisecondes pour le format LRC (mm:ss.xx).
     */
    fun formatTimestampLRC(ms: Long): String {
        val totalSeconds = ms / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val hundredths = (ms % 1000L) / 10L
        return "%02d:%02d.%02d".format(Locale.US, minutes, seconds, hundredths)
    }

    /**
     * Formate un horodatage en millisecondes pour le format TTML (HH:MM:SS.mmm).
     */
    fun formatTimestampTTML(ms: Long): String {
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        val milliseconds = ms % 1000L
        return "%02d:%02d:%02d.%03d".format(Locale.US, hours, minutes, seconds, milliseconds)
    }

    /**
     * Détermine si les chœurs doivent être affichés avant ou après le texte principal
     * selon leur heure de début respective.
     */
    fun getBackgroundTextPlacement(line: LyricsLine): String {
        if (line.backgroundText.isEmpty() || line.text.isEmpty()) {
            return "after"
        }
        val mainStart = line.text[0].timestamp
        val bgStart = line.backgroundText[0].timestamp
        return if (bgStart < mainStart) "before" else "after"
    }

    /**
     * Génère un fichier LRC horodaté incluant les métadonnées de titre, artiste, album et source.
     */
    fun generateLRC(
        lines: List<LyricsLine>,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        source: String? = null,
    ): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()

        if (!title.isNullOrBlank()) sb.append("[ti:$title]\n")
        if (!artist.isNullOrBlank()) sb.append("[ar:$artist]\n")
        if (!album.isNullOrBlank()) sb.append("[al:$album]\n")
        if (!source.isNullOrBlank()) sb.append("[re:$source]\n")

        for (line in lines) {
            if (line.text.isNotEmpty()) {
                val timestamp = formatTimestampLRC(line.timestamp)
                val lineText = line.text.joinToString("") { it.text }.trim()
                sb.append("[$timestamp]$lineText\n")
            }
        }
        return sb.toString()
    }

    /**
     * Génère le texte brut sans horodatage, en intégrant les chœurs entre parenthèses.
     */
    fun generatePlain(lines: List<LyricsLine>): String {
        if (lines.isEmpty()) return ""
        val result = mutableListOf<String>()

        for (line in lines) {
            val main = line.text.joinToString("") { it.text }.trim()
            val backing = line.backgroundText.joinToString("") { it.text }.trim()
            val background = when {
                backing.isEmpty() -> ""
                backing.startsWith("(") && backing.endsWith(")") -> backing
                else -> "($backing)"
            }

            val placement = getBackgroundTextPlacement(line)
            val lineParts = if (placement == "before") {
                listOf(background, main)
            } else {
                listOf(main, background)
            }.filter { it.isNotEmpty() }

            result.addAll(lineParts)
        }
        return result.joinToString("\n")
    }

    /**
     * Génère un document XML TTML mot par mot complet avec découpage par couplet / refrain (div songPart).
     */
    fun generateTTML(lines: List<LyricsLine>): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:itunes=\"http://music.apple.com/lyrics\">\n")
        sb.append("  <body>\n")

        var currentPart: String? = null

        lines.forEachIndexed { i, line ->
            val part = line.songPart
            if (part != currentPart || i == 0) {
                if (i > 0) {
                    sb.append("    </div>\n")
                }
                currentPart = part
                if (!currentPart.isNullOrBlank()) {
                    sb.append("    <div itunes:song-part=\"$currentPart\">\n")
                } else {
                    sb.append("    <div>\n")
                }
            }

            val begin = formatTimestampTTML(line.timestamp)
            val end = formatTimestampTTML(line.endtime)

            sb.append("      <p begin=\"$begin\" end=\"$end\">\n")
            for (word in line.text) {
                val wBegin = formatTimestampTTML(word.timestamp)
                val wEnd = formatTimestampTTML(word.endtime)
                val text = word.text
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                sb.append("        <span begin=\"$wBegin\" end=\"$wEnd\">$text</span>\n")
            }
            sb.append("      </p>\n")
        }

        if (lines.isNotEmpty()) {
            sb.append("    </div>\n")
        }
        sb.append("  </body>\n")
        sb.append("</tt>")

        return sb.toString()
    }
}
