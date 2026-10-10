package com.spotywoop.kt.playback

import android.text.Html
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Tubidy depuis le téléphone, même stratégie que le serveur online-resolve (tubidy/client.go) :
 * miroir duesouth.co.za, défi Anubis, recherche, filtres (durée Spotify ±5 s, versions modifiées,
 * invités cités d'abord), formats → play, puis contrôle de la durée réelle du MP3.
 * C'est un couloir de la course : il concourt avec le serveur et yt-dlp.
 */
object TubidySource {
    private const val TAG = "TubidyLocal"
    private const val BASE = "https://duesouth.co.za"
    private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
    private const val ANUBIS_PASS = "/.within.website/x/cmd/anubis/api/pass-challenge"
    private const val TOLERANCE_SEC = 5
    private const val MAX_CANDIDATES = 3

    private val ANUBIS_RE = Regex("""<script id="anubis_challenge" type="application/json">([^<]+)</script>""")
    private val CARD_RE = Regex("""class="item container""")
    private val CARD_TITLE_RE = Regex("""<a href="((?:https?://[^"/]+)?/download/[^"]+/video/[^"/?#]+)" class="title video-link" title="([^"]*)"""")
    private val CARD_DURATION_RE = Regex("""<time datetime="(PT[0-9HMS]+)"""")
    private val ISO_DURATION_RE = Regex("""^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$""")
    private val VIDEO_PAYLOAD_RE = Regex("""App\.video\(['"]([^'"]+)['"]\)""")
    private val TITLE_RE = Regex("""<title>([^<]*)</title>""")

    // Versions qui ne sont pas l'enregistrement original, souvent à la même durée (liste du serveur).
    private val ALTERED = listOf(
        "432hz", "432 hz", "528hz", "528 hz", "slowed", "sped up", "speed up", "nightcore", "8d audio", "reverb",
        "remix", "karaoke", "instrumental", "cover", "acoustic", "originally performed", "bootleg", "mashup",
    )

    /** Cookies en mémoire (Anubis, XSRF, session Laravel), partagés par toutes les résolutions. */
    private val cookies = object : CookieJar {
        private val store = mutableMapOf<String, Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { store["${it.domain}|${it.name}"] = it }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store.values.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
        @Synchronized fun value(name: String): String? = store.values.firstOrNull { it.name == name }?.value
        @Synchronized fun valueMatching(name: (String) -> Boolean): String? = store.values.firstOrNull { name(it.name) }?.value
    }

    private val http = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val noFollow = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val anubisLock = Any()

    private data class Candidate(val title: String, val page: String, val durationSec: Int)

    /**
     * @param artists artistes Spotify, principal en premier ; les invités départagent deux versions de même durée
     * @return null si aucun résultat ne convient (le couloir perd la course sans bloquer les autres)
     */
    fun resolve(artists: String?, title: String?, durationMs: Long?): ResolvedStream? {
        val query = listOfNotNull(artists, title).joinToString(" ").trim()
        if (query.isBlank()) return null
        val durationSec = durationMs?.takeIf { it > 0 }?.let { ((it + 500) / 1000).toInt() } ?: 0
        val featured = artists.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.drop(1)

        val searchUrl = "$BASE/search?q=" + URLEncoder.encode(query, "UTF-8")
        val results = parseSearch(page(searchUrl, null))
        if (results.isEmpty()) {
            Log.i(TAG, "Aucun résultat pour '$query'")
            return null
        }
        // Mots du titre (sans parenthèses ni « - … ») : sans eux, c'est une autre chanson à la même durée
        // (« All The Way » pris pour « BURN (feat. Ava Max) »).
        val titleWords = coreTitle(title.orEmpty()).split(" ").filter { it.isNotEmpty() }
        val kept = results
            .filterNot { isAltered(it.title, query) }
            .filter { c -> " ${normalize(c.title)} ".let { t -> titleWords.all { t.contains(" $it ") } } }
            .filter { durationSec <= 0 || it.durationSec == 0 || abs(it.durationSec - durationSec) <= TOLERANCE_SEC }
            .sortedBy { if (it.durationSec == 0) 1 else 0 } // durée inconnue en dernier, comme le serveur
        // Invités cités d'abord ; s'il y en a, eux seuls (yt-dlp prendra le relais plutôt que l'original).
        val preferred = if (featured.isEmpty()) emptyList() else kept.filter { c -> featured.all { artistMatches(it, c.title) } }
        val candidates = preferred.ifEmpty { kept }
        if (candidates.isEmpty()) {
            Log.i(TAG, "'$query' : ${results.size} résultats, aucun à ${durationSec}s ± $TOLERANCE_SEC")
            return null
        }

        var lastError: Exception? = null
        for (c in candidates.take(MAX_CANDIDATES)) {
            try {
                val streamUrl = resolvePage(c.page, searchUrl)
                val fileSec = fileDuration(streamUrl)
                if (durationSec > 0 && fileSec > 0 && abs(fileSec - durationSec) > TOLERANCE_SEC) {
                    throw IOException("durée du fichier $fileSec s au lieu de $durationSec s")
                }
                Log.i(TAG, "'$query' -> ${c.title} (${if (fileSec > 0) fileSec else c.durationSec} s)")
                return ResolvedStream(url = streamUrl, source = "Tubidy (local)", quality = "MP3 128 kbit/s")
            } catch (e: Exception) {
                Log.d(TAG, "'${c.title}' écarté : ${e.message}")
                lastError = e
            }
        }
        throw IOException("Tubidy local : ${lastError?.message}")
    }

    private fun parseSearch(body: String): List<Candidate> {
        val seen = mutableSetOf<String>()
        return CARD_RE.split(body).drop(1).mapNotNull { card ->
            val m = CARD_TITLE_RE.find(card) ?: return@mapNotNull null
            val page = absUrl(unescape(m.groupValues[1]))
            if (!seen.add(page)) return@mapNotNull null
            val dur = CARD_DURATION_RE.find(card)?.groupValues?.get(1)?.let(::isoDuration) ?: 0
            Candidate(unescape(m.groupValues[2]), page, dur)
        }
    }

    /** Page du titre → payload App.video → /api/video/formats (MP3) → /api/video/play → .mp3 direct. */
    private fun resolvePage(pageUrl: String, referer: String): String {
        val body = page(pageUrl, referer)
        val payload = VIDEO_PAYLOAD_RE.find(body)?.groupValues?.get(1) ?: throw IOException("payload App.video absent")
        val formats = api("/api/video/formats", pageUrl, payload)
        if (formats.optString("status") == "ERROR") {
            val code = formats.optJSONObject("error")?.optString("code")
            throw IOException(if (code == "-4") "vidéo source indisponible" else "formats en erreur (code $code)")
        }
        val list = formats.optJSONArray("formats") ?: throw IOException("aucun format")
        val mp3 = (0 until list.length()).map { list.getJSONObject(it) }
            .firstOrNull { it.optString("label").uppercase().contains("MP3") } ?: throw IOException("aucun format MP3")
        val link = api("/api/video/play", pageUrl, mp3.optString("payload")).optString("link")
        if (link.isBlank()) throw IOException("pas de lien dans /api/video/play")
        return followOnce(link)
    }

    /** GET HTML ; passe le défi Anubis s'il apparaît, puis rejoue la requête. */
    private fun page(url: String, referer: String?): String {
        repeat(3) {
            val authBefore = anubisAuth()
            val (body, finalUrl) = get(url, referer)
            val challenge = ANUBIS_RE.find(body) ?: return body
            passAnubis(unescape(challenge.groupValues[1]), finalUrl, authBefore)
        }
        throw IOException("Anubis redemande un défi après validation")
    }

    private fun get(url: String, referer: String?): Pair<String, String> {
        val req = Request.Builder().url(url).headers(referer)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8").build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code >= 400 && !ANUBIS_RE.containsMatchIn(body)) throw IOException("HTTP ${resp.code} sur ${resp.request.url.encodedPath}")
            return body to resp.request.url.toString()
        }
    }

    /** Cookie d'Anubis en cours (le nom contient « anubis »), pour savoir si un autre fil l'a déjà renouvelé. */
    private fun anubisAuth(): String? = cookies.valueMatching { it.contains("anubis", ignoreCase = true) }

    private fun passAnubis(raw: String, pageUrl: String, authBefore: String?) = synchronized(anubisLock) {
        // Plusieurs résolutions tournent en même temps (titre en cours, pré-chargement) : si une autre a
        // passé le défi pendant qu'on attendait, on rejoue simplement la requête avec son cookie.
        if (anubisAuth() != authBefore) return@synchronized
        val json = JSONObject(raw)
        val algorithm = json.getJSONObject("rules").optString("algorithm")
        if (algorithm != "fast" && algorithm != "slow") throw IOException("algorithme Anubis '$algorithm' non géré")
        val difficulty = json.getJSONObject("rules").optInt("difficulty")
        val challenge = json.getJSONObject("challenge")
        val started = System.currentTimeMillis()
        val (nonce, hash) = solveAnubis(challenge.optString("randomData"), difficulty)
        val url = (BASE + ANUBIS_PASS).toHttpUrl().newBuilder()
            .addQueryParameter("id", challenge.optString("id"))
            .addQueryParameter("response", hash)
            .addQueryParameter("nonce", nonce.toString())
            .addQueryParameter("redir", pageUrl)
            .addQueryParameter("elapsedTime", (System.currentTimeMillis() - started + 100).toString())
            .build().toString()
        val (body, _) = get(url, pageUrl)
        if (ANUBIS_RE.containsMatchIn(body)) throw IOException("Anubis a refusé la preuve de travail")
        Log.d(TAG, "Défi Anubis passé (difficulté $difficulty, nonce $nonce)")
    }

    /** Plus petit nonce tel que sha256(randomData + nonce) commence par [difficulty] zéros hexadécimaux. */
    private fun solveAnubis(randomData: String, difficulty: Int): Pair<Int, String> {
        require(difficulty in 0..8) { "difficulté Anubis $difficulty hors limites" }
        val prefix = "0".repeat(difficulty)
        val sha = MessageDigest.getInstance("SHA-256")
        var nonce = 0
        while (true) {
            val hash = sha.digest((randomData + nonce).toByteArray()).joinToString("") { "%02x".format(it) }
            if (hash.startsWith(prefix)) return nonce to hash
            nonce++
        }
    }

    /** Route Laravel avec le jeton XSRF lu dans le cookie, comme axios dans le navigateur. */
    private fun api(path: String, referer: String, payload: String): JSONObject {
        repeat(2) {
            val authBefore = anubisAuth()
            val text = apiOnce(path, referer, payload)
            val challenge = ANUBIS_RE.find(text)
            if (challenge == null) {
                return runCatching { JSONObject(text) }.getOrElse { throw IOException("$path : réponse non JSON") }
            }
            // Anubis peut aussi intercepter l'API : on passe le défi depuis la page, puis on réessaie.
            passAnubis(unescape(challenge.groupValues[1]), referer, authBefore)
        }
        throw IOException("$path : Anubis redemande un défi")
    }

    private fun apiOnce(path: String, referer: String, payload: String): String {
        val body = JSONObject().put("payload", payload).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(BASE + path).post(body).headers(referer)
            .header("Accept", "application/json, text/plain, */*")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", BASE)
            .apply { cookies.value("XSRF-TOKEN")?.let { header("X-XSRF-TOKEN", URLDecoder.decode(it, "UTF-8")) } }
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful && !ANUBIS_RE.containsMatchIn(text)) throw IOException("$path : HTTP ${resp.code}")
            return text
        }
    }

    /** Cible de la redirection p.php → .mp3, ou le lien d'origine si elle échoue. */
    private fun followOnce(link: String): String = runCatching {
        noFollow.newCall(Request.Builder().url(link).header("User-Agent", USER_AGENT).build()).execute().use { resp ->
            resp.header("Location")?.let { resp.request.url.resolve(it)?.toString() } ?: link
        }
    }.getOrDefault(link)

    /** Durée du MP3 par son en-tête Xing/Info (8 premiers Ko), sinon par la taille en 128 kbit/s ; 0 si inconnue. */
    private fun fileDuration(url: String): Int = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Range", "bytes=0-8191").build()
        noFollow.newCall(req).execute().use { resp ->
            if (resp.code != 206 && resp.code != 200) return 0
            val head = resp.body?.source()?.let { src -> src.request(8192); src.buffer.readByteArray(minOf(8192L, src.buffer.size)) } ?: return 0
            mp3HeaderDuration(head).takeIf { it > 0 }
                ?: resp.header("Content-Range")?.substringAfterLast("/")?.toLongOrNull()?.let { (it * 8 / 128_000).toInt() }
                ?: 0
        }
    }.getOrDefault(0)

    private fun mp3HeaderDuration(b: ByteArray): Int {
        fun u(i: Int) = b[i].toInt() and 0xff
        var off = 0
        if (b.size >= 10 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) {
            off = 10 + ((u(6) and 0x7f) shl 21 or ((u(7) and 0x7f) shl 14) or ((u(8) and 0x7f) shl 7) or (u(9) and 0x7f))
        }
        while (off + 4 <= b.size && !(u(off) == 0xff && (u(off + 1) and 0xe0) == 0xe0)) off++
        if (off + 4 > b.size) return 0
        val version = (u(off + 1) shr 3) and 0x03 // 3 : MPEG-1, 2 : MPEG-2, 0 : MPEG-2.5
        val idx = (u(off + 2) shr 2) and 0x03
        val rates = when (version) { 3 -> intArrayOf(44100, 48000, 32000); 2 -> intArrayOf(22050, 24000, 16000); 0 -> intArrayOf(11025, 12000, 8000); else -> return 0 }
        if (idx == 3) return 0
        val samplesPerFrame = if (version == 3) 1152 else 576
        val frame = b.copyOfRange(off, minOf(off + 200, b.size))
        val text = String(frame, Charsets.ISO_8859_1)
        for (tag in listOf("Xing", "Info")) {
            val i = text.indexOf(tag)
            if (i < 0 || i + 12 > frame.size) continue
            fun f(k: Int) = frame[i + k].toLong() and 0xff
            if ((f(7) and 1L) == 0L) return 0
            val frames = (f(8) shl 24) or (f(9) shl 16) or (f(10) shl 8) or f(11)
            return (frames * samplesPerFrame / rates[idx]).toInt()
        }
        return 0
    }

    private fun coreTitle(title: String): String =
        normalize(title.replace(Regex("""[\(\[].*?[\)\]]"""), "").split(Regex("""\s+[-–—]\s+""")).first())

    private fun isAltered(title: String, query: String): Boolean {
        val t = title.lowercase()
        val q = query.lowercase()
        return ALTERED.any { t.contains(it) && !q.contains(it) }
    }

    /** Vrai si un mot significatif (3 lettres et plus) de l'artiste figure dans le titre. */
    private fun artistMatches(artist: String, title: String): Boolean {
        val t = " ${normalize(title)} "
        return normalize(artist).split(" ").any { it.length >= 3 && t.contains(" $it ") }
    }

    private fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun isoDuration(s: String): Int {
        val m = ISO_DURATION_RE.find(s) ?: return 0
        fun n(i: Int) = m.groupValues[i].toIntOrNull() ?: 0
        return n(1) * 3600 + n(2) * 60 + n(3)
    }

    private fun absUrl(ref: String): String = if (ref.startsWith("http")) ref else BASE + ref

    @Suppress("DEPRECATION")
    private fun unescape(s: String): String = Html.fromHtml(s).toString()

    private fun Request.Builder.headers(referer: String?): Request.Builder = apply {
        header("User-Agent", USER_AGENT)
        header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
        referer?.let { header("Referer", it) }
    }
}
