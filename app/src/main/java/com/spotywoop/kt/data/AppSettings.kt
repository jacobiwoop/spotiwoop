package com.spotywoop.kt.data

import android.content.Context
import com.spotywoop.kt.playback.SongMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Modes d'affichage des titres et artistes contenant des caractères asiatiques (japonais, chinois, coréen).
 */
enum class TransliterationMode(val id: String, val label: String, val example: String) {
    /** Option B (Par défaut) : Romaji d'abord, original entre parenthèses */
    ROMAJI_FIRST("romaji_first", "Romaji (Original)", "Burubado (ブルーバード)"),

    /** Option A : Original d'abord, Romaji entre parenthèses */
    ORIGINAL_FIRST("original_first", "Original (Romaji)", "ブルーバード (Burubado)"),

    /** Option C : Romaji seul */
    ROMAJI_ONLY("romaji_only", "Romaji seul", "Burubado"),

    /** Désactivé : conserve le texte original */
    OFF("off", "Original seul (Désactivé)", "ブルーバード"),
}

object AppSettings {
    private const val PREFS_NAME = "spotywoop_app_settings"
    private const val KEY_TRANSLIT_MODE = "transliteration_mode"

    private val _transliterationMode = MutableStateFlow(TransliterationMode.ROMAJI_FIRST)
    val transliterationMode: StateFlow<TransliterationMode> = _transliterationMode.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_TRANSLIT_MODE, TransliterationMode.ROMAJI_FIRST.id)
        _transliterationMode.value = TransliterationMode.values().firstOrNull { it.id == saved }
            ?: TransliterationMode.ROMAJI_FIRST
    }

    fun setTransliterationMode(context: Context, mode: TransliterationMode) {
        _transliterationMode.value = mode
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TRANSLIT_MODE, mode.id)
            .apply()
    }

    /**
     * Formate un texte (titre ou artiste) pour l'affichage selon le mode configuré.
     * Si le texte ne contient aucun caractère CJK, il est retourné tel quel.
     */
    fun formatDisplay(rawText: String?, mode: TransliterationMode = _transliterationMode.value): String {
        if (rawText.isNullOrBlank() || !SongMatch.containsCjk(rawText) || mode == TransliterationMode.OFF) {
            return rawText.orEmpty()
        }

        val romaji = SongMatch.transliterate(rawText).trim()
        if (romaji.equals(rawText, ignoreCase = true) || romaji.isBlank()) {
            return rawText
        }

        // Capitaliser proprement le premier caractère
        val cleanRomaji = romaji.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        return when (mode) {
            TransliterationMode.ROMAJI_FIRST -> "$cleanRomaji ($rawText)"
            TransliterationMode.ORIGINAL_FIRST -> "$rawText ($cleanRomaji)"
            TransliterationMode.ROMAJI_ONLY -> cleanRomaji
            TransliterationMode.OFF -> rawText
        }
    }
}
