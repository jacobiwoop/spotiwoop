package com.spotywoop.kt.data

import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class SpotifyException(message: String) : IOException(message)

/**
 * Client anonyme du web player Spotify, porté de SpotiFLAC (spotfetch.go) :
 * page d'accueil (clientVersion + cookie sp_t) → token TOTP → client token → GraphQL pathfinder.
 */
class SpotifyClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private var accessToken = ""
    private var accessTokenExpiresAt = 0L
    private var clientToken = ""
    private var clientId = ""
    private var deviceId = ""
    private var clientVersion = ""
    private val cookies = mutableMapOf<String, String>()

    suspend fun search(query: String, limit: Int = 50, offset: Int = 0): SearchResults {
        require(query.isNotBlank()) { "La recherche ne peut pas être vide" }
        val payload = buildJsonObject {
            putJsonObject("variables") {
                put("searchTerm", query)
                put("offset", offset)
                put("limit", limit.coerceIn(1, 50))
                put("numberOfTopResults", 5)
                put("includeAudiobooks", true)
                put("includeArtistHasConcertsField", false)
                put("includePreReleases", true)
                put("includeAuthors", false)
            }
            put("operationName", "searchDesktop")
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", SEARCH_DESKTOP_HASH)
                }
            }
        }
        return SearchParser.parse(query(payload))
    }

    suspend fun query(payload: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val (token, client, version) = mutex.withLock {
            if (!tokensValid()) initialize()
            Triple(accessToken, clientToken, clientVersion)
        }

        var response = postQuery(payload, token, client, version)
        if (response.first == 401 || response.first == 403) {
            val retry = mutex.withLock {
                if (accessToken == token) {
                    accessToken = ""
                    clientToken = ""
                    initialize()
                }
                Triple(accessToken, clientToken, clientVersion)
            }
            response = postQuery(payload, retry.first, retry.second, retry.third)
        }

        val (code, body) = response
        if (code != 200) throw SpotifyException("Requête API échouée : HTTP $code | ${body.take(200)}")
        json.parseToJsonElement(body) as JsonObject
    }

    private fun tokensValid() = accessToken.isNotEmpty() && clientToken.isNotEmpty() &&
        System.currentTimeMillis() < accessTokenExpiresAt - 30_000

    /** Récupère un token d'accès valide avec ses cookies de session (sp_t). */
    suspend fun getValidAccessToken(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!tokensValid()) initialize()
            accessToken
        }
    }

    private fun postQuery(payload: JsonObject, token: String, client: String, version: String): Pair<Int, String> {
        val request = Request.Builder()
            .url("https://api-partner.spotify.com/pathfinder/v2/query")
            .header("Authorization", "Bearer $token")
            .header("Client-Token", client)
            .header("Spotify-App-Version", version)
            .header("User-Agent", USER_AGENT)
            .post(payload.toString().toByteArray().toRequestBody(JSON_TYPE))
            .build()
        return http.newCall(request).execute().use { it.code to it.body!!.string() }
    }

    private fun initialize() {
        fetchSessionInfo()
        fetchAccessToken()
        fetchClientToken()
    }

    private fun fetchSessionInfo() {
        val request = Request.Builder()
            .url("https://open.spotify.com")
            .header("User-Agent", USER_AGENT)
            .apply { if (cookies.isNotEmpty()) header("Cookie", cookieHeader()) }
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.code != 200) throw SpotifyException("Initialisation de session échouée : HTTP ${resp.code}")
            val body = resp.body!!.string()
            APP_SERVER_CONFIG.find(body)?.groupValues?.get(1)?.let { encoded ->
                runCatching {
                    val decoded = String(Base64.getDecoder().decode(encoded.trim()))
                    clientVersion = (json.parseToJsonElement(decoded) as JsonObject).str("clientVersion")
                }
            }
            storeCookies(resp)
        }
    }

    private fun fetchAccessToken() {
        var lastError: Exception? = null
        // Même tolérance que SpotiFLAC : fenêtre courante, puis ±30 s en cas de dérive d'horloge.
        for ((attempt, offset) in listOf(0L, -30_000L, 30_000L).withIndex()) {
            try {
                val totp = SpotifyTotp.generate(System.currentTimeMillis() + offset)
                val url = "https://open.spotify.com/api/token".toHttpUrl().newBuilder()
                    .addQueryParameter("reason", "init")
                    .addQueryParameter("productType", "web-player")
                    .addQueryParameter("totp", totp)
                    .addQueryParameter("totpVer", SpotifyTotp.VERSION.toString())
                    .addQueryParameter("totpServer", totp)
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .build()
                http.newCall(request).execute().use { resp ->
                    val body = resp.body!!.string()
                    if (resp.code != 200) {
                        throw SpotifyException("Token refusé : HTTP ${resp.code} | ${body.trim().take(200)}")
                    }
                    val data = json.parseToJsonElement(body) as JsonObject
                    val token = data.str("accessToken")
                    val id = data.str("clientId")
                    if (token.isEmpty() || id.isEmpty()) throw SpotifyException("Réponse de token incomplète")
                    accessToken = token
                    clientId = id
                    val expiresMs = data.num("accessTokenExpirationTimestampMs").toLong()
                    accessTokenExpiresAt = if (expiresMs > 0) expiresMs else System.currentTimeMillis() + 55 * 60_000
                    storeCookies(resp)
                }
                return
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) Thread.sleep(400)
            }
        }
        throw lastError ?: SpotifyException("Récupération du token échouée")
    }

    private fun fetchClientToken() {
        val payload = buildJsonObject {
            putJsonObject("client_data") {
                put("client_version", clientVersion)
                put("client_id", clientId)
                putJsonObject("js_sdk_data") {
                    put("device_brand", "unknown")
                    put("device_model", "unknown")
                    put("os", "windows")
                    put("os_version", "NT 10.0")
                    put("device_id", deviceId)
                    put("device_type", "computer")
                }
            }
        }
        val request = Request.Builder()
            .url("https://clienttoken.spotify.com/v1/clienttoken")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(payload.toString().toByteArray().toRequestBody(JSON_TYPE))
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.code != 200) throw SpotifyException("Client token refusé : HTTP ${resp.code}")
            val data = json.parseToJsonElement(resp.body!!.string()) as JsonObject
            if (data.str("response_type") != "RESPONSE_GRANTED_TOKEN_RESPONSE") {
                throw SpotifyException("Type de réponse client token invalide")
            }
            clientToken = data.obj("granted_token").str("token")
        }
    }

    private fun storeCookies(resp: Response) {
        for (header in resp.headers("Set-Cookie")) {
            val pair = header.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isEmpty()) continue
            cookies[name] = value
            if (name == "sp_t") deviceId = value
        }
    }

    private fun cookieHeader() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
        const val SEARCH_DESKTOP_HASH = "fcad5a3e0d5af727fb76966f06971c19cfa2275e6ff7671196753e008611873c"
        // Sans « ; charset=utf-8 » : clienttoken.spotify.com répond 400 si le charset est présent.
        val JSON_TYPE = "application/json".toMediaType()
        val APP_SERVER_CONFIG = Regex("""<script id="appServerConfig" type="text/plain">([^<]+)</script>""")
    }
}

