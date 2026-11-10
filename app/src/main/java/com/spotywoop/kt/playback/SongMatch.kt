package com.spotywoop.kt.playback

import kotlin.math.abs

/** Reconnaît le bon enregistrement, avec les règles du serveur (provider/provider.go). */
object SongMatch {
    const val TOLERANCE_SEC = 5

    private val BRACKETS = Regex("""[\(\[《【「『（〔].*?[\)\]》】」』）〕]""")
    private val ALTERED = listOf(
        "432hz", "432 hz", "528hz", "528 hz", "slowed", "sped up", "speed up", "nightcore", "8d audio", "reverb",
        "remix", "karaoke", "instrumental", "cover", "acoustic", "originally performed", "bootleg", "mashup",
    )

    // Table de translittération phonétique pure (Romaji Hepburn) pour le japonais
    private val DIGRAPHS = mapOf(
        "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo",
        "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho",
        "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho",
        "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo",
        "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo",
        "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo",
        "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo",
        "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
        "じゃ" to "ja", "じゅ" to "ju", "じょ" to "jo",
        "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo",
        "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo",
        "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo",
        "シャ" to "sha", "シュ" to "shu", "ショ" to "sho",
        "チャ" to "cha", "チュ" to "chu", "チョ" to "cho",
        "ニャ" to "nya", "ニュ" to "nyu", "ニョ" to "nyo",
        "ヒャ" to "hya", "ヒュ" to "hyu", "ヒョ" to "hyo",
        "ミャ" to "mya", "ミュ" to "myu", "ミョ" to "myo",
        "リャ" to "rya", "リュ" to "ryu", "リョ" to "ryo",
        "ギャ" to "gya", "ギュ" to "gyu", "ギョ" to "gyo",
        "ジャ" to "ja", "ジュ" to "ju", "ジョ" to "jo",
        "ビャ" to "bya", "ビュ" to "byu", "ビョ" to "byo",
        "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo",
    )

    private val KANA_MAP = mapOf(
        'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
        'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
        'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
        'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
        'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
        'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
        'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
        'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
        'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
        'わ' to "wa", 'ゐ' to "wi", 'ゑ' to "we", 'を' to "o", 'ん' to "n",
        'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
        'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
        'だ' to "da", 'ぢ' to "ji", 'づ' to "zu", 'で' to "de", 'ど' to "do",
        'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
        'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po",
        'ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o",
        'ア' to "a", 'イ' to "i", 'ウ' to "u", 'エ' to "e", 'オ' to "o",
        'カ' to "ka", 'キ' to "ki", 'ク' to "ku", 'ケ' to "ke", 'コ' to "ko",
        'サ' to "sa", 'シ' to "shi", 'ス' to "su", 'セ' to "se", 'ソ' to "so",
        'タ' to "ta", 'チ' to "chi", 'ツ' to "tsu", 'テ' to "te", 'ト' to "to",
        'ナ' to "na", 'ニ' to "ni", 'ヌ' to "nu", 'ネ' to "ne", 'ノ' to "no",
        'ハ' to "ha", 'ヒ' to "hi", 'フ' to "fu", 'ヘ' to "he", 'ホ' to "ho",
        'マ' to "ma", 'ミ' to "mi", 'む' to "mu", 'メ' to "me", 'モ' to "mo",
        'ヤ' to "ya", 'ユ' to "yu", 'ヨ' to "yo",
        'ラ' to "ra", 'リ' to "ri", 'ル' to "ru", 'レ' to "re", 'ロ' to "ro",
        'ワ' to "wa", 'ヰ' to "wi", 'ヱ' to "we", 'ヲ' to "o", 'ン' to "n",
        'ガ' to "ga", 'ギ' to "gi", 'グ' to "gu", 'ゲ' to "ge", 'ゴ' to "go",
        'ザ' to "za", 'ジ' to "ji", 'ズ' to "zu", 'ゼ' to "ze", 'ゾ' to "zo",
        'ダ' to "da", 'ヂ' to "ji", 'ヅ' to "zu", 'デ' to "de", 'ド' to "do",
        'バ' to "ba", 'ビ' to "bi", 'ブ' to "bu", 'べ' to "be", 'ボ' to "bo",
        'パ' to "pa", 'ピ' to "pi", 'プ' to "pu", 'ペ' to "pe", 'ポ' to "po",
        'ヴ' to "vu",
        'ァ' to "a", 'ィ' to "i", 'ゥ' to "u", 'ェ' to "e", 'ォ' to "o",
    )

    fun containsCjk(s: String): Boolean {
        for (ch in s) {
            val code = ch.code
            if (code in 0x4E00..0x9FFF || // Hanzi / Kanji
                code in 0x3040..0x309F || // Hiragana
                code in 0x30A0..0x30FF || // Katakana
                code in 0xAC00..0xD7AF || // Hangul
                code in 0x3000..0x303F    // CJK Symbols & punctuation
            ) {
                return true
            }
        }
        return false
    }

    private fun transliterateKanaPure(s: String): String {
        val sb = StringBuilder(s.length * 2)
        var i = 0
        while (i < s.length) {
            if (i + 1 < s.length) {
                val pair = s.substring(i, i + 2)
                val dig = DIGRAPHS[pair]
                if (dig != null) {
                    sb.append(dig)
                    i += 2
                    continue
                }
            }
            val ch = s[i]
            if (ch == 'っ' || ch == 'ッ') {
                if (i + 1 < s.length) {
                    val next = s[i + 1]
                    val nextRom = KANA_MAP[next] ?: ""
                    if (nextRom.isNotEmpty()) {
                        sb.append(if (nextRom.startsWith("c")) 't' else nextRom[0])
                    }
                }
                i++
                continue
            }
            if (ch == 'ー') {
                i++
                continue
            }
            val rom = KANA_MAP[ch]
            if (rom != null) {
                sb.append(rom)
            } else {
                sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    private val icuTransliterator: Any? by lazy {
        try {
            android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII")
        } catch (_: Throwable) {
            null
        }
    }

    fun transliterate(s: String): String {
        val pure = transliterateKanaPure(s)
        return try {
            (icuTransliterator as? android.icu.text.Transliterator)?.transliterate(pure) ?: pure
        } catch (_: Throwable) {
            pure
        }
    }

    fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun words(s: String) = normalize(s).split(" ").filter { it.isNotEmpty() }

    /**
     * Bon artiste et bon titre, sans version retouchée.
     * Pour les titres CJK (japonais/chinois), on tolère les sous-titres d'anime et on translittère l'artiste.
     */
    fun sameRecording(foundTitle: String, foundArtist: String, artists: String?, title: String?): Boolean {
        val query = " " + normalize(listOfNotNull(artists, title).joinToString(" ")) + " "
        val cleanFoundTitle = foundTitle.replace(BRACKETS, " ")
        val found = words(cleanFoundTitle)
        if (found.isEmpty()) return false

        val wanted = words(title.orEmpty().replace(BRACKETS, " ").split(Regex("""\s*[-–—]\s*""")).first())
        val inFound = " " + normalize(foundTitle) + " "
        var hasWanted = wanted.isNotEmpty() && wanted.all { inFound.contains(" $it ") }

        // Si le titre recherché n'est pas trouvé directement, essayer avec la translittération
        if (!hasWanted && wanted.isNotEmpty()) {
            val tFound = " " + normalize(transliterate(foundTitle)) + " "
            val tWanted = wanted.map { normalize(transliterate(it)) }
            hasWanted = tWanted.all { tFound.contains(" $it ") || tFound.contains(it) }
        }
        if (!hasWanted) return false

        // Pour les titres occidentaux, chaque mot trouvé doit figurer dans la requête.
        // Pour CJK, on tolère les sous-titres d'anime / drama ajoutés par les catalogues chinois/japonais.
        val isCjk = containsCjk(foundTitle) || containsCjk(listOfNotNull(artists, title).joinToString(" "))
        if (!isCjk) {
            if (found.any { !query.contains(" $it ") }) return false
        }

        val lowerTitle = foundTitle.lowercase()
        val lowerQuery = listOfNotNull(artists, title).joinToString(" ").lowercase()
        if (ALTERED.any { lowerTitle.contains(it) && !lowerQuery.contains(it) }) return false

        // Correspondance de l'artiste : directe ou translittérée
        val artistMatchesDirect = words(foundArtist).any { it.length >= 3 && query.contains(" $it ") }
        if (artistMatchesDirect) return true

        val tFoundArtist = transliterate(foundArtist)
        val tQuery = " " + normalize(transliterate(listOfNotNull(artists, title).joinToString(" "))) + " "
        return words(tFoundArtist).any { it.length >= 3 && (tQuery.contains(" $it ") || tQuery.contains(it)) }
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
