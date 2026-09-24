package com.spotywoop.kt.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Vérification de session communautaire SpotiFLAC via mini-serveur HTTP local (Option 1).
 *
 * Utilise un ServerSocket sur 127.0.0.1 avec port dynamique.
 * Le serveur SpotiFLAC exige impérativement un callback HTTP vers 127.0.0.1
 * et app_version="7.2.2".
 */
object CommunityVerifier {

    private const val APP_VERSION_DEFAULT = "7.2.2"
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    @Volatile
    private var activeServerSocket: ServerSocket? = null

    suspend fun verify(context: Context, appVersion: String = APP_VERSION_DEFAULT): CommunitySession =
        withContext(Dispatchers.IO) {
            val installId = loadOrCreateInstallId(context)
            val state = randomHex(16)

            // 1. Démarrer un serveur HTTP local sur un port libre (127.0.0.1)
            val serverSocket = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
            activeServerSocket = serverSocket
            val localPort = serverSocket.localPort

            val grantRef = AtomicReference<String?>()
            val latch = CountDownLatch(1)

            // Thread d'écoute pour la redirection du navigateur
            Thread {
                try {
                    while (!serverSocket.isClosed && latch.count > 0) {
                        val client: Socket = try {
                            serverSocket.accept()
                        } catch (_: Exception) {
                            break
                        }

                        Thread {
                            try {
                                handleClient(client, state, grantRef, latch)
                            } catch (_: Exception) {
                            } finally {
                                runCatching { client.close() }
                            }
                        }.start()
                    }
                } finally {
                    runCatching { serverSocket.close() }
                }
            }.start()

            try {
                // 2. Bootstrap auprès de l'API SpotiFLAC
                val verifyBase = CommunitySignature.verifyEndpoint()
                val bootstrapUrl = "$verifyBase/bootstrap" +
                    "?install_id=${urlEncode(installId)}" +
                    "&app_version=${urlEncode(appVersion)}" +
                    "&platform=desktop"

                val bootstrapReq = Request.Builder().url(bootstrapUrl).build()
                val challengeUrl = http.newCall(bootstrapReq).execute().use { resp ->
                    if (resp.code != 200) throw IOException("Bootstrap: HTTP ${resp.code}")
                    JSONObject(resp.body!!.string()).optString("challenge_url")
                        .ifBlank { throw IOException("challenge_url absent") }
                }

                // 3. Callback vers http://127.0.0.1:<port>/session-grant
                val callbackUrl = "http://127.0.0.1:$localPort/session-grant?state=${urlEncode(state)}"
                val fullChallengeUri = Uri.parse(challengeUrl).buildUpon()
                    .appendQueryParameter("cb", callbackUrl)
                    .build()

                // 4. Ouvrir le navigateur
                withContext(Dispatchers.Main) {
                    val intent = CustomTabsIntent.Builder().build().intent.apply {
                        data = fullChallengeUri
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }

                // 5. Attendre la validation (timeout 5 minutes)
                val completed = latch.await(300, TimeUnit.SECONDS)
                if (!completed) throw IOException("Délai de vérification dépassé (timeout)")

                val grant = grantRef.get() ?: throw IOException("Grant de session introuvable")

                // 6. Échanger le grant contre la session complète
                val exchangeUrl = "$verifyBase/session/exchange"
                val exchangeBody = JSONObject().apply {
                    put("grant", grant)
                    put("install_id", installId)
                    put("app_version", appVersion)
                    put("platform", "desktop")
                }.toString().toByteArray()

                val exchangeReq = Request.Builder()
                    .url(exchangeUrl)
                    .post(exchangeBody.toRequestBody("application/json".toMediaType()))
                    .build()

                val session = http.newCall(exchangeReq).execute().use { resp ->
                    if (resp.code != 200) throw IOException("Exchange: HTTP ${resp.code}")
                    val obj = JSONObject(resp.body!!.string())
                    CommunitySession(
                        installId = installId,
                        sessionId = obj.optString("session_id")
                            .ifBlank { throw IOException("session_id absent") },
                        sessionSecret = obj.optString("session_secret")
                            .ifBlank { throw IOException("session_secret absent") },
                        expiresAt = obj.optString("expires_at", ""),
                    )
                }

                // 7. Stockage chiffré
                CommunitySessionStore.save(context, session)
                session
            } finally {
                cancel()
            }
        }

    private fun handleClient(
        client: Socket,
        expectedState: String,
        grantRef: AtomicReference<String?>,
        latch: CountDownLatch,
    ) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val pathAndQuery = parts[1]

        val uri = Uri.parse("http://127.0.0.1$pathAndQuery")
        if (uri.path == "/session-grant") {
            val state = uri.getQueryParameter("state")
            val grant = uri.getQueryParameter("grant")

            val out = client.getOutputStream()
            if (state == expectedState && !grant.isNullOrBlank()) {
                grantRef.set(grant)
                latch.countDown()
                val html = """
                    <!doctype html>
                    <html>
                    <head><meta charset="utf-8"><title>Vérifié</title></head>
                    <body style="background:#111;color:#fff;font-family:sans-serif;text-align:center;padding:50px;">
                        <h2>✓ Vérification réussie !</h2>
                        <p>Vous pouvez fermer cette fenêtre et retourner sur Spotywoop.</p>
                    </body>
                    </html>
                """.trimIndent()
                val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${html.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n" + html
                out.write(response.toByteArray())
                out.flush()
            } else {
                val error = "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\nInvalid state or grant"
                out.write(error.toByteArray())
                out.flush()
            }
        }
    }

    fun cancel() {
        runCatching { activeServerSocket?.close() }
        activeServerSocket = null
    }

    private fun loadOrCreateInstallId(context: Context): String {
        val existing = CommunitySessionStore.load(context)?.installId
        if (!existing.isNullOrBlank()) return existing
        return randomHex(16)
    }

    private fun randomHex(bytes: Int): String {
        val arr = ByteArray(bytes)
        SecureRandom().nextBytes(arr)
        return arr.joinToString("") { "%02x".format(it) }
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
}
