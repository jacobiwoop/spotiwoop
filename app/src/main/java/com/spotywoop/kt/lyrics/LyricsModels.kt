package com.spotywoop.kt.lyrics

/**
 * Modèle de données pour les paroles synchronisées (mot par mot et ligne par ligne).
 * Portage fidèle de LyricsSources.ts et AmLyrics.ts (temps en millisecondes Long).
 */

/**
 * Une syllabe ou segment de mot avec ses horodatages précis.
 */
data class Syllable(
    val text: String,
    val part: Boolean,
    val timestamp: Long,
    val endtime: Long,
    var romanizedText: String? = null,
    val lineSynced: Boolean = false,
)

/**
 * Une ligne de paroles, composée de syllabes principales et optionnellement de chœurs (background).
 */
data class LyricsLine(
    val text: List<Syllable>,
    val background: Boolean = false,
    val backgroundText: List<Syllable> = emptyList(),
    val oppositeTurn: Boolean = false,
    val timestamp: Long,
    val endtime: Long,
    val isWordSynced: Boolean? = null,
    val alignment: String? = null, // "start" | "end"
    val songPart: String? = null,
    var romanizedText: String? = null,
    var translation: String? = null,
    val agentId: String? = null,
    val direction: String? = null, // "ltr" | "rtl"
)

/**
 * Contributeur ayant participé à la transcription ou synchronisation des paroles.
 */
data class LyricsContributor(
    val username: String,
    val url: String? = null,
    val avatar: String? = null,
    val roles: List<String> = emptyList(),
)

/**
 * Métadonnées du morceau exploitées par la cascade de recherche.
 */
data class SongMetadata(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val songwriters: String? = null,
)

/**
 * Résultat complet fourni par une source de paroles.
 */
data class LyricsSourceResult(
    val lines: List<LyricsLine>,
    val source: String,
    val originalTTML: String? = null,
    val songwriters: String? = null,
    val byModel: Boolean = false,
    val isrc: String? = null,
    val metadata: SongMetadata? = null,
    val contributors: List<LyricsContributor>? = null,
)

/**
 * Résultat issu de la recherche du catalogue (LyricsPlus / KPoe).
 */
data class SongCatalogResult(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val songwriters: String? = null,
    val isrc: String? = null,
    val appleMusicId: String? = null,
    val spotifyId: String? = null,
)

/**
 * Métadonnées résolues après consultation du catalogue ou de la requête brute.
 */
data class ResolvedMetadata(
    val metadata: SongMetadata?,
    val appleId: String? = null,
    val catalogIsrc: String? = null,
    val spotifyId: String? = null,
)

/**
 * Découpage d'une chaîne de recherche brute en titre et artiste.
 */
data class ParsedQueryMetadata(
    var title: String? = null,
    var artist: String? = null,
    var album: String? = null,
)

/**
 * État exposé par le dépôt de paroles pendant et après la cascade.
 */
data class LyricsState(
    val lyrics: List<LyricsLine>? = null,
    val currentSource: LyricsSourceResult? = null,
    val availableSources: List<LyricsSourceResult> = emptyList(),
    val currentSourceIndex: Int = 0,
    val isLoading: Boolean = false,
    val isFetchingAlternatives: Boolean = false,
    val hasFetchedAllProviders: Boolean = false,
    val songwriters: String? = null,
)

/**
 * Conversion d'une valeur en secondes (Double, Number ou String) en millisecondes Long.
 */
fun secondsToMs(value: Any?, fallback: Long = 0L): Long {
    val d = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    } ?: return fallback
    if (!d.isFinite()) return fallback
    return maxOf(0L, Math.round(d * 1000.0))
}

/**
 * Conversion souple d'un horodatage numérique (secondes à virgule ou ms entières).
 */
fun toMilliseconds(value: Any?, fallback: Long = 0L): Long {
    val d = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    } ?: return fallback
    if (!d.isFinite()) return fallback
    val isInt = (d == Math.floor(d))
    return if (isInt) maxOf(0L, d.toLong()) else maxOf(0L, Math.round(d * 1000.0))
}
