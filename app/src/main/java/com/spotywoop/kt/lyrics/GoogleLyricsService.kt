package com.spotywoop.kt.lyrics

import com.spotywoop.kt.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Service de traduction et de romanisation des paroles utilisant l'API Google Translate (non officielle).
 * Portage fidèle de GoogleService.ts et des méthodes applyTranslation / applyRomanization d'AmLyrics.ts.
 */
object GoogleLyricsService {

    private const val TAG = "GoogleLyricsService"
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 1000L
    private const val FETCH_TIMEOUT_MS = 6000L
    private const val BATCH_SIZE_CHARS = 1500

    private val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AppLog.Http)
        .callTimeout(8, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

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

    /**
     * Vérifie si le texte est composé uniquement de caractères latins et signes de ponctuation courants.
     */
    fun isPurelyLatinScript(text: String): Boolean {
        for (i in 0 until text.length) {
            val codePoint = text.codePointAt(i)
            if (codePoint > 0x024F) return false
        }
        return true
    }

    /**
     * Traduit une liste de lignes de texte vers la langue cible (par défaut anglais).
     * Regroupe les lignes par lots de 1 500 caractères maximum.
     */
    suspend fun translate(
        texts: List<String>,
        targetLang: String = "en",
    ): List<String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()

        val nonEmptyIndices = mutableListOf<Int>()
        val textsToTranslate = mutableListOf<String>()

        texts.forEachIndexed { i, t ->
            if (t.isNotBlank()) {
                nonEmptyIndices.add(i)
                textsToTranslate.add(t)
            }
        }

        if (textsToTranslate.isEmpty()) return@withContext texts

        val translatedResults = MutableList(textsToTranslate.size) { "" }

        suspend fun processBatch(batch: List<String>, indices: List<Int>) {
            if (batch.isEmpty()) return
            val joinedText = batch.joinToString("\n")
            var attempt = 0
            var success = false

            while (attempt < MAX_RETRIES && !success) {
                try {
                    withTimeout(FETCH_TIMEOUT_MS) {
                        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=${urlEncode(joinedText)}"
                        val req = Request.Builder().url(url).build()
                        val resp = http.newCall(req).execute()
                        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")

                        val data = JSONArray(resp.body?.string().orEmpty())
                        val segments = data.optJSONArray(0) ?: JSONArray()
                        val fullTranslation = StringBuilder()
                        for (si in 0 until segments.length()) {
                            val seg = segments.optJSONArray(si)
                            fullTranslation.append(seg?.optString(0).orEmpty())
                        }

                        val lines = fullTranslation.toString().split('\n')
                        indices.forEachIndexed { i, originalIdx ->
                            translatedResults[originalIdx] = if (i < lines.size) lines[i] else batch[i]
                        }
                        success = true
                    }
                } catch (e: Exception) {
                    attempt++
                    if (attempt < MAX_RETRIES) {
                        delay(RETRY_DELAY_MS * (1 shl (attempt - 1)))
                    } else {
                        indices.forEachIndexed { i, originalIdx ->
                            translatedResults[originalIdx] = batch[i]
                        }
                    }
                }
            }
        }

        var currentBatch = mutableListOf<String>()
        var currentBatchIndices = mutableListOf<Int>()
        var currentBatchLength = 0

        for (i in textsToTranslate.indices) {
            val text = textsToTranslate[i]
            if (currentBatchLength + text.length > BATCH_SIZE_CHARS) {
                processBatch(currentBatch, currentBatchIndices)
                currentBatch = mutableListOf()
                currentBatchIndices = mutableListOf()
                currentBatchLength = 0
            }
            currentBatch.add(text)
            currentBatchIndices.add(i)
            currentBatchLength += text.length
        }
        if (currentBatch.isNotEmpty()) {
            processBatch(currentBatch, currentBatchIndices)
        }

        val finalArray = texts.toMutableList()
        nonEmptyIndices.forEachIndexed { mappedIdx, realIdx ->
            finalArray[realIdx] = translatedResults[mappedIdx]
        }
        finalArray
    }

    /**
     * Romanise une liste de textes non latins.
     */
    suspend fun romanizeTexts(texts: List<String>): List<String> = withContext(Dispatchers.IO) {
        val contextText = texts.joinToString(" ")
        if (isPurelyLatinScript(contextText)) return@withContext texts

        val romanizedTexts = mutableListOf<String>()
        for (text in texts) {
            if (text.isBlank() || isPurelyLatinScript(text)) {
                romanizedTexts.add(text)
            } else {
                var attempt = 0
                var success = false
                var romanizedResult = text

                while (attempt < MAX_RETRIES && !success) {
                    try {
                        withTimeout(FETCH_TIMEOUT_MS) {
                            val romanizeUrl = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=rm&q=${urlEncode(text)}"
                            val req = Request.Builder().url(romanizeUrl).build()
                            val resp = http.newCall(req).execute()
                            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")

                            val data = JSONArray(resp.body?.string().orEmpty())
                            val candidate = data.optJSONArray(0)?.optJSONArray(0)?.optString(3)
                            romanizedResult = if (!candidate.isNullOrBlank()) candidate else text
                            success = true
                        }
                    } catch (_: Exception) {
                        attempt++
                        if (attempt < MAX_RETRIES) {
                            delay(RETRY_DELAY_MS * (1 shl (attempt - 1)))
                        }
                    }
                }
                romanizedTexts.add(romanizedResult)
            }
        }
        romanizedTexts
    }

    /**
     * Romanisation pour paroles synchronisées mot par mot.
     */
    suspend fun romanizeWordSynced(lines: List<LyricsLine>): List<LyricsLine> = withContext(Dispatchers.IO) {
        lines.map { line ->
            if (line.text.isEmpty() || !line.romanizedText.isNullOrBlank()) {
                line
            } else {
                val fullText = line.text.joinToString("") { it.text }
                val romanized = romanizeTexts(listOf(fullText)).firstOrNull().orEmpty()
                line.copy(romanizedText = romanized.takeIf { it.isNotBlank() })
            }
        }
    }

    /**
     * Romanisation pour paroles synchronisées ligne par ligne.
     */
    suspend fun romanizeLineSynced(lines: List<LyricsLine>): List<LyricsLine> = withContext(Dispatchers.IO) {
        val linesToRomanize = lines.map { line ->
            if (!line.romanizedText.isNullOrBlank()) ""
            else line.text.joinToString("") { it.text }
        }
        val romanizedLines = romanizeTexts(linesToRomanize)
        lines.mapIndexed { index, line ->
            val r = romanizedLines.getOrNull(index)?.takeIf { it.isNotBlank() }
            if (r != null && line.romanizedText.isNullOrBlank()) line.copy(romanizedText = r) else line
        }
    }

    /**
     * Romanise l'ensemble des lignes selon leur type de synchronisation.
     */
    suspend fun romanize(lines: List<LyricsLine>): List<LyricsLine> {
        if (lines.isEmpty()) return emptyList()
        val isWordSynced = lines.any { l -> l.isWordSynced != false && l.text.size > 1 }
        return if (isWordSynced) romanizeWordSynced(lines) else romanizeLineSynced(lines)
    }

    /**
     * Applique la traduction aux lignes sans traduction existante.
     */
    suspend fun applyTranslation(lines: List<LyricsLine>, targetLang: String = "en"): List<LyricsLine> {
        val textToTranslate = lines.map { line ->
            if (!line.translation.isNullOrBlank()) ""
            else line.text.joinToString("") { it.text }
        }
        if (textToTranslate.all { it.isBlank() }) return lines

        val translations = translate(textToTranslate, targetLang)
        return lines.mapIndexed { index, line ->
            if (!line.translation.isNullOrBlank()) line
            else line.copy(translation = translations.getOrNull(index)?.takeIf { it.isNotBlank() })
        }
    }

    /**
     * Applique la romanisation aux lignes sans romanisation existante.
     */
    suspend fun applyRomanization(lines: List<LyricsLine>): List<LyricsLine> {
        val needsRomanization = lines.any { line ->
            line.romanizedText.isNullOrBlank() && line.text.none { !it.romanizedText.isNullOrBlank() }
        }
        return if (needsRomanization) romanize(lines) else lines
    }
}
