package com.spotywoop.kt.playback

import kotlin.math.abs

/** Reconnaît le bon enregistrement, avec les règles du serveur (provider/provider.go). */
object SongMatch {
    const val TOLERANCE_SEC = 5

    private val BRACKETS = Regex("""[\(\[].*?[\)\]]""")
    private val ALTERED = listOf(
        "432hz", "432 hz", "528hz", "528 hz", "slowed", "sped up", "speed up", "nightcore", "8d audio", "reverb",
        "remix", "karaoke", "instrumental", "cover", "acoustic", "originally performed", "bootleg", "mashup",
    )

    fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun words(s: String) = normalize(s).split(" ").filter { it.isNotEmpty() }

    /**
     * Bon artiste et bon titre, sans version retouchée : chaque mot du titre trouvé (hors parenthèses : « (Explicit) »)
     * est dans la demande, et chaque mot du titre demandé (hors parenthèses et « - … ») est dans le titre trouvé.
     */
    fun sameRecording(foundTitle: String, foundArtist: String, artists: String?, title: String?): Boolean {
        val query = " " + normalize(listOfNotNull(artists, title).joinToString(" ")) + " "
        val found = words(foundTitle.replace(BRACKETS, " "))
        if (found.isEmpty() || found.any { !query.contains(" $it ") }) return false
        val wanted = words(title.orEmpty().replace(BRACKETS, " ").split(Regex("""\s+[-–—]\s+""")).first())
        val inFound = " " + normalize(foundTitle) + " "
        if (wanted.any { !inFound.contains(" $it ") }) return false
        val lowerTitle = foundTitle.lowercase()
        val lowerQuery = listOfNotNull(artists, title).joinToString(" ").lowercase()
        if (ALTERED.any { lowerTitle.contains(it) && !lowerQuery.contains(it) }) return false
        return words(foundArtist).any { it.length >= 3 && query.contains(" $it ") }
    }

    /** Durée inconnue d'un côté ou de l'autre (0) : on ne peut pas écarter le titre. */
    fun durationMatches(wantedSec: Int, foundSec: Int): Boolean =
        wantedSec <= 0 || foundSec <= 0 || abs(foundSec - wantedSec) <= TOLERANCE_SEC

    /** Les catalogues cherchent mal avec les invités et les parenthèses : premier artiste + titre nu. */
    fun searchTerm(artists: String?, title: String?): String {
        val first = artists.orEmpty().split(",").first().trim()
        return "$first ${title.orEmpty().replace(BRACKETS, " ")}".split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun seconds(durationMs: Long?): Int = durationMs?.takeIf { it > 0 }?.let { ((it + 500) / 1000).toInt() } ?: 0
}
