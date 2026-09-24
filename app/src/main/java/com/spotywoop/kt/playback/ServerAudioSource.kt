package com.spotywoop.kt.playback

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Source audio via le serveur Spotiwoop local.
 *
 * Utilise l'endpoint haute vitesse /api/stream/resolve?q=...
 * Accessible depuis Waydroid via 192.168.240.1 ou 127.0.0.1.
 */
class ServerAudioSource(
    private val host: String = "192.168.240.1",
    private val port: Int = 3005,
) : StreamSource {
    override val name = "Serveur HQ"

    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

    override fun resolve(spotifyTrackId: String): ResolvedStream {
        throw UnsupportedOperationException("Utiliser resolveWithQuery")
    }

    fun resolveWithQuery(spotifyTrackId: String, query: String): ResolvedStream {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val endpoints = listOf(
            "https://spotywoop-srv.onrender.com/api/stream/resolve?q=$encoded",
            "https://spotiwoop.onrender.com/api/stream/resolve?q=$encoded",
            "http://10.208.243.235:$port/api/stream/resolve?q=$encoded",
            "http://$host:$port/api/stream/resolve?q=$encoded",
            "http://192.168.1.93:$port/api/stream/resolve?q=$encoded",
            "http://127.0.0.1:$port/api/stream/resolve?q=$encoded",
            "http://10.0.2.2:$port/api/stream/resolve?q=$encoded",
        )

        var lastError: Exception? = null
        for (url in endpoints) {
            try {
                val req = Request.Builder().url(url).build()
                val respBody = http.newCall(req).execute().use { resp ->
                    if (resp.code != 200) throw IOException("HTTP ${resp.code}")
                    resp.body!!.string()
                }

                val obj = JSONObject(respBody)
                val audioUrl = obj.optString("url")
                if (audioUrl.isNotBlank()) {
                    return ResolvedStream(
                        url = audioUrl,
                        source = obj.optString("source", name),
                        quality = obj.optString("quality", "Audio HQ (Complet)"),
                    )
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: IOException("Serveur local indisponible")
    }
}
