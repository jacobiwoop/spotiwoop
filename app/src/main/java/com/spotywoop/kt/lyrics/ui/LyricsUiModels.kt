package com.spotywoop.kt.lyrics.ui

import com.spotywoop.kt.lyrics.LyricsLine
import com.spotywoop.kt.lyrics.Syllable
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Constantes et modèles pour le rendu graphique des paroles synchronisées.
 * Reprend fidèlement toutes les valeurs, règles et équations physiques de AmLyrics.ts.
 */
object LyricsUiConstants {
    const val INSTRUMENTAL_THRESHOLD_MS = 7000L
    const val SEEK_THRESHOLD_MS = 500L
    const val SCROLL_ANIMATION_DURATION_MS = 350L
    const val BACKGROUND_EXIT_DURATION_MS = 450L
    const val BACKGROUND_CARRY_MIN_OVERLAP_MS = 800L
    const val USER_SCROLL_RESUME_DELAY_MS = 5000L

    // Intervalles instrumentaux (3 points qui respirent)
    const val GAP_PULSE_DURATION_MS = 4000L
    const val GAP_ENTRY_FADE_MS = 160L
    const val GAP_ENTRY_SCALE_MS = 400L
    const val GAP_COLLAPSE_LEAD_MS = 500L
    const val GAP_EXIT_LEAD_MS = 350L
    const val GAP_EXIT_TRAIL_MS = 250L
    const val GAP_BREATH_MIN_SCALE = 0.85f
    const val GAP_BREATH_MAX_SCALE = 1.12f
    const val GAP_EXIT_POP_SCALE = 1.2f
    const val GAP_EXIT_POP_PROGRESS = 0.35f

    // Remplissage par dégradé (wipe) et pré-remplissage (pre-wipe)
    const val NEXT_WORD_PRE_WIPE_MAX_GAP_MS = 180L
    const val NEXT_WORD_PRE_WIPE_MIN_DURATION_MS = 80L
    const val NEXT_WORD_PRE_WIPE_MAX_DURATION_MS = 240L
    const val BASE_WIPE_GRADIENT_EM = 0.75f
    const val CJK_WIPE_GRADIENT_EM = 0.3f
    const val LONG_WORD_WIPE_EXTRA_EM = 0.45f
    const val LONG_WORD_WIPE_EXTRA_RATIO = 0.35f
    const val WORD_PRE_WIPE_HANDOFF_LEAD_MS = 100L
    const val WORD_WIPE_LOOKAHEAD_MS = 500L

    // Flou et défilement
    const val NEXT_LINE_BASE_BLUR_EM = 0.02f
    const val NEXT_LINE_UNBLUR_DURATION_MS = 2000L
    const val NORMAL_WORD_SPRING_DURATION_MS = 2000L

    // Styles et dimensions (CSS variables am-lyrics)
    const val FONT_SIZE_BASE_SP = 32f
    const val LINE_SPACING_DP = 25f
    const val BG_VOCAL_SPACING_DP = 15f
    const val BG_VOCAL_FONT_SCALE = 0.65f
    const val INACTIVE_LINE_SCALE = 0.98f
    const val BG_VOCAL_SCALE = 0.96f
    const val TOUCH_SCALE = 0.96f
    const val HIGHLIGHT_RADIUS_DP = 16f
    const val PROGRESSION_FEATHER_DP = 30f
    const val GLOW_RADIUS_DP = 5f
    const val CHAR_RISE_Y_DP = -3f
    const val CHAR_RISE_PEAK_DP = -1.25f
    const val BLUR_AMOUNT_DP = 2.4f
    const val BLUR_AMOUNT_NEAR_DP = 1.2f
    const val SCROLL_PADDING_TOP_RATIO = 0.12f // 12% du conteneur
}

/**
 * Intervalle instrumental entre deux phrases chantées (ou en début de morceau).
 */
data class InstrumentalGap(
    val insertBeforeIndex: Int,
    val gapStart: Long,
    val gapEnd: Long,
) {
    val durationMs: Long get() = max(1L, gapEnd - gapStart)
}

/**
 * Emplacement des chœurs (background vocals) par rapport au chant principal.
 */
enum class BackgroundPlacement {
    BEFORE,
    AFTER,
}

/**
 * Type d'animation de mot / caractères pour les mots longs ou idéogrammes CJK.
 */
enum class CharacterMotionMode {
    NONE,
    RISE,
    EMPHASIS,
}

/**
 * Données préparées pour le rendu et l'animation d'un mot au sein d'une ligne.
 */
data class WordUiData(
    val id: String,
    val syllables: List<Syllable>,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val mode: CharacterMotionMode,
    val isRtl: Boolean,
    val charCount: Int,
    val preWipeDurationMs: Long,
)

/**
 * Données prétraitées d'une ligne de paroles pour un affichage 60 fps performant.
 */
data class LineUiData(
    val index: Int,
    val line: LyricsLine,
    val mainWords: List<WordUiData>,
    val backgroundWords: List<WordUiData>,
    val backgroundPlacement: BackgroundPlacement,
    val isOppositeAgent: Boolean,
    val isRtl: Boolean,
    val isLineSyncedOnly: Boolean,
)

/**
 * Détermine si la ligne est chantée par l'agent opposé (duo aligné à droite).
 */
fun isOppositeAgent(line: LyricsLine): Boolean {
    return line.alignment == "end" || (line.alignment == null && line.oppositeTurn)
}

/**
 * Détecte si la ligne utilise une écriture de droite à gauche (hébreu, arabe, etc.).
 */
fun isLineRtl(line: LyricsLine): Boolean {
    if (line.direction == "rtl") return true
    val fullText = (line.text + line.backgroundText).joinToString("") { it.text }
    return Regex("""[\u0590-\u08ff]""").containsMatchIn(fullText)
}

/**
 * Placement des chœurs : avant ou après le texte principal selon l'horodatage initial.
 */
fun getBackgroundTextPlacement(line: LyricsLine): BackgroundPlacement {
    if (line.backgroundText.isEmpty() || line.text.isEmpty()) return BackgroundPlacement.AFTER
    return if (line.backgroundText[0].timestamp < line.text[0].timestamp) {
        BackgroundPlacement.BEFORE
    } else {
        BackgroundPlacement.AFTER
    }
}

/**
 * Détermine le mode d'animation des caractères (elevation, ressort, lueur).
 */
fun characterMotionMode(text: String, durationMs: Long, background: Boolean = false): CharacterMotionMode {
    if (Regex("""[\u0590-\u08ff]""").containsMatchIn(text)) return CharacterMotionMode.NONE
    val trimmed = text.trim()
    val count = trimmed.codePointCount(0, trimmed.length)
    if (count == 0) return CharacterMotionMode.NONE
    if (Regex("""[\u3400-\u4dbf\u4e00-\u9fff\u3040-\u30ff]""").containsMatchIn(text)) {
        return if (count > 1) CharacterMotionMode.RISE else CharacterMotionMode.NONE
    }
    return if (!background && durationMs >= 1000L && count <= 7) {
        CharacterMotionMode.EMPHASIS
    } else {
        CharacterMotionMode.NONE
    }
}

/**
 * Calcule l'instant précis où le surlignage d'une ligne s'achève.
 * Portage exact de `computeLineHighlightEndTime` de AmLyrics.ts.
 */
fun computeLineHighlightEndTime(lyrics: List<LyricsLine>, index: Int): Long {
    if (index !in lyrics.indices) return 0L
    val line = lyrics[index]
    val backgroundEnd = line.backgroundText.maxOfOrNull { it.endtime } ?: line.timestamp
    val rawEnd = maxOf(line.endtime, backgroundEnd, line.timestamp)
    val nextLine = lyrics.getOrNull(index + 1)
    if (nextLine == null || nextLine.timestamp <= line.timestamp) {
        return if (rawEnd > line.timestamp) rawEnd + 200L else rawEnd
    }

    val leadEnd = line.text.maxOfOrNull { it.endtime } ?: line.endtime
    if (backgroundEnd > maxOf(leadEnd, nextLine.timestamp)) {
        return maxOf(leadEnd, nextLine.timestamp)
    }

    if (rawEnd > line.timestamp) {
        if (nextLine.timestamp < rawEnd) {
            return rawEnd
        }
        val gapToNext = nextLine.timestamp - rawEnd
        if (gapToNext >= LyricsUiConstants.INSTRUMENTAL_THRESHOLD_MS) {
            return rawEnd
        }
    }

    return nextLine.timestamp
}

/**
 * Identifie les indices des lignes actives au temps [timeMs].
 * Gère correctement les chevauchements et duos simultanés.
 */
fun findActiveLineIndices(lyrics: List<LyricsLine>, timeMs: Long): List<Int> {
    if (lyrics.isEmpty()) return emptyList()
    val active = mutableListOf<Int>()
    for (i in lyrics.indices) {
        val line = lyrics[i]
        val highlightEnd = computeLineHighlightEndTime(lyrics, i)
        if (line.timestamp > timeMs) {
            break
        }
        if (timeMs >= line.timestamp && timeMs < highlightEnd) {
            active.add(i)
        }
    }
    return active
}

/**
 * Détecte tous les passages instrumentaux (pauses >= 7 s) du morceau.
 */
fun findAllInstrumentalGaps(lyrics: List<LyricsLine>): List<InstrumentalGap> {
    if (lyrics.isEmpty()) return emptyList()
    val gaps = mutableListOf<InstrumentalGap>()

    // Pause d'intro avant le premier chant
    val first = lyrics[0]
    if (first.timestamp >= LyricsUiConstants.INSTRUMENTAL_THRESHOLD_MS) {
        gaps.add(InstrumentalGap(insertBeforeIndex = 0, gapStart = 0L, gapEnd = first.timestamp))
    }

    // Pauses inter-lignes
    for (i in 0 until lyrics.size - 1) {
        val curr = lyrics[i]
        val next = lyrics[i + 1]
        val gapStart = curr.endtime
        val gapEnd = next.timestamp
        if (gapEnd - gapStart >= LyricsUiConstants.INSTRUMENTAL_THRESHOLD_MS) {
            gaps.add(InstrumentalGap(insertBeforeIndex = i + 1, gapStart = gapStart, gapEnd = gapEnd))
        }
    }

    return gaps
}

/**
 * Recherche le passage instrumental englobant [timeMs], le cas échéant.
 */
fun findInstrumentalGapAt(lyrics: List<LyricsLine>, timeMs: Long): InstrumentalGap? {
    val gaps = findAllInstrumentalGaps(lyrics)
    return gaps.firstOrNull { timeMs in it.gapStart until it.gapEnd }
}

/**
 * Équation de réponse indicielle d'un ressort critique (AmLyrics.ts springProgress).
 */
fun springProgress(timeSec: Float, responseSec: Float): Float {
    if (timeSec <= 0f) return 0f
    val phase = (2f * Math.PI.toFloat() * timeSec) / maxOf(0.001f, responseSec)
    return 1f - (1f + phase) * exp(-phase)
}

/**
 * Échantillonne la position et vitesse d'un oscillateur amorti (sampleSpring de YouLyPlus).
 */
fun sampleSpring(
    position: Float,
    velocity: Float,
    timeSec: Float,
    frequency: Float,
    damping: Float = 0.9f * frequency,
): Pair<Float, Float> {
    val omega = sqrt(maxOf(0.0001f, frequency * frequency - damping * damping))
    val decay = exp(-damping * timeSec)
    val cosVal = cos(omega * timeSec)
    val sinVal = sin(omega * timeSec)
    val pos = decay * (position * cosVal + ((velocity + damping * position) / omega) * sinVal)
    val vel = decay * (velocity * cosVal - ((damping * velocity + frequency * frequency * position) / omega) * sinVal)
    return Pair(pos, vel)
}

/**
 * Regroupe les syllabes consécutives en mots selon les séparateurs d'espaces.
 */
fun groupSyllablesIntoWords(
    syllables: List<Syllable>,
    linePrefix: String,
    background: Boolean = false,
): List<WordUiData> {
    if (syllables.isEmpty()) return emptyList()

    val wordGroups = mutableListOf<List<Syllable>>()
    var currentGroup = mutableListOf<Syllable>()

    syllables.forEachIndexed { index, syllable ->
        currentGroup.add(syllable)
        val next = syllables.getOrNull(index + 1)
        if (next == null || syllable.text.endsWith(" ") || next.text.startsWith(" ")) {
            wordGroups.add(currentGroup)
            currentGroup = mutableListOf()
        }
    }

    return wordGroups.mapIndexed { wordIndex, group ->
        val text = group.joinToString("") { it.text }
        val start = group.minOf { it.timestamp }
        val end = group.maxOf { it.endtime }
        val duration = maxOf(1L, end - start)
        val charCount = text.replace(Regex("""\s"""), "").codePointCount(0, text.replace(Regex("""\s"""), "").length)
        val mode = characterMotionMode(text, duration, background)
        val isRtl = Regex("""[\u0590-\u08ff]""").containsMatchIn(text)
        val preWipeDuration = ((64 + charCount * 9).toLong())
            .coerceIn(LyricsUiConstants.NEXT_WORD_PRE_WIPE_MIN_DURATION_MS, LyricsUiConstants.NEXT_WORD_PRE_WIPE_MAX_DURATION_MS)

        WordUiData(
            id = "$linePrefix-word-$wordIndex-$start",
            syllables = group,
            text = text,
            startMs = start,
            endMs = end,
            durationMs = duration,
            mode = mode,
            isRtl = isRtl,
            charCount = charCount,
            preWipeDurationMs = preWipeDuration,
        )
    }
}

/**
 * Prépare la structure de données d'affichage pour une ligne.
 */
fun buildLineUiData(line: LyricsLine, index: Int, allLyrics: List<LyricsLine>): LineUiData {
    val mainWords = groupSyllablesIntoWords(line.text, "line-$index", background = false)
    val bgWords = groupSyllablesIntoWords(line.backgroundText, "line-$index-bg", background = true)
    val placement = getBackgroundTextPlacement(line)
    val isEnd = isOppositeAgent(line)
    val isRtl = isLineRtl(line)
    val lineSyncedOnly = line.isWordSynced == false || line.text.all { it.lineSynced }

    return LineUiData(
        index = index,
        line = line,
        mainWords = mainWords,
        backgroundWords = bgWords,
        backgroundPlacement = placement,
        isOppositeAgent = isEnd,
        isRtl = isRtl,
        isLineSyncedOnly = lineSyncedOnly,
    )
}
