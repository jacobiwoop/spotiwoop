package com.spotywoop.kt.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.spotywoop.kt.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val changelog: String,
    val downloadUrl: String,
    val assetName: String,
    val assetSize: Long,
)

sealed interface UpdateStatus {
    object Idle : UpdateStatus
    object Checking : UpdateStatus
    data class Available(val info: UpdateInfo, val previousWasObsolete: Boolean = false) : UpdateStatus
    data class UpToDate(val checkedAt: Long = System.currentTimeMillis()) : UpdateStatus
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long, val info: UpdateInfo? = null) : UpdateStatus
    data class ReadyToInstall(val apkFile: File, val info: UpdateInfo? = null, val isLatest: Boolean = true) : UpdateStatus
    data class Error(val message: String) : UpdateStatus
}

object AppUpdateManager {

    private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/jacobiwoop/spotiwoop/releases/latest"

    private val scope = CoroutineScope(Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var appContext: Context? = null

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val _promptDismissed = MutableStateFlow(false)
    val promptDismissed: StateFlow<Boolean> = _promptDismissed.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun dismissPrompt() {
        _promptDismissed.value = true
    }

    fun showPromptAgain() {
        _promptDismissed.value = false
    }

    fun checkForUpdates(silent: Boolean = false) {
        if (_status.value is UpdateStatus.Checking || _status.value is UpdateStatus.Downloading) return

        _status.value = UpdateStatus.Checking
        scope.launch {
            try {
                val req = Request.Builder()
                    .url(GITHUB_RELEASES_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "Spotywoop/${BuildConfig.VERSION_NAME}")
                    .build()

                val jsonStr = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    resp.body?.string() ?: throw IOException("Réponse vide")
                }

                val obj = JSONObject(jsonStr)
                val tagName = obj.optString("tag_name", "").trim()
                val remoteVersion = tagName.removePrefix("v").trim()
                val title = obj.optString("name", "Mise à jour Spotywoop")
                val changelog = obj.optString("body", "Améliorations et corrections de bugs.")

                val currentVersion = BuildConfig.VERSION_NAME

                if (isNewer(remoteVersion, currentVersion)) {
                    val assets = obj.optJSONArray("assets") ?: org.json.JSONArray()
                    val targetAsset = selectBestAsset(assets)
                    if (targetAsset != null) {
                        val info = UpdateInfo(
                            tagName = tagName,
                            versionName = remoteVersion,
                            title = title,
                            changelog = changelog,
                            downloadUrl = targetAsset.url,
                            assetName = targetAsset.name,
                            assetSize = targetAsset.size,
                        )

                        // Vérifier si un APK existe déjà dans le cache
                        val ctx = appContext
                        var previousWasObsolete = false
                        if (ctx != null) {
                            val updatesDir = File(ctx.cacheDir, "updates")
                            if (updatesDir.exists()) {
                                val targetFile = File(updatesDir, targetAsset.name)
                                if (targetFile.exists() && targetFile.length() > 0) {
                                    // Fichier déjà téléchargé et correspond à la toute dernière version !
                                    android.util.Log.i("AppUpdateManager", "APK déjà présent et à jour: ${targetFile.name}")
                                    _status.value = UpdateStatus.ReadyToInstall(targetFile, info, isLatest = true)
                                    return@launch
                                } else {
                                    // Des fichiers APK d'anciennes versions existent ? Nettoyer
                                    val oldApks = updatesDir.listFiles { f -> f.extension.equals("apk", ignoreCase = true) }
                                    if (!oldApks.isNullOrEmpty()) {
                                        android.util.Log.i("AppUpdateManager", "Suppression de ${oldApks.size} ancien(s) APK obsolète(s)")
                                        oldApks.forEach { it.delete() }
                                        previousWasObsolete = true
                                    }
                                }
                            }
                        }

                        _status.value = UpdateStatus.Available(info, previousWasObsolete = previousWasObsolete)
                        return@launch
                    }
                }

                _status.value = UpdateStatus.UpToDate()
            } catch (e: Exception) {
                android.util.Log.w("AppUpdateManager", "Échec vérification mise à jour: ${e.message}")
                if (!silent) {
                    _status.value = UpdateStatus.Error(e.message ?: "Impossible de vérifier les mises à jour")
                } else {
                    _status.value = UpdateStatus.Idle
                }
            }
        }
    }

    fun downloadAndInstall(context: Context, info: UpdateInfo) {
        if (_status.value is UpdateStatus.Downloading) return

        scope.launch {
            try {
                val updatesDir = File(context.cacheDir, "updates").also { it.mkdirs() }
                // Nettoyer tout ancien APK
                updatesDir.listFiles { f -> f.extension.equals("apk", ignoreCase = true) }?.forEach { it.delete() }

                val apkFile = File(updatesDir, info.assetName)

                val req = Request.Builder()
                    .url(info.downloadUrl)
                    .header("User-Agent", "Spotywoop/${BuildConfig.VERSION_NAME}")
                    .build()

                _status.value = UpdateStatus.Downloading(0f, 0L, info.assetSize, info)

                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Échec téléchargement : HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("Contenu vide")
                    val totalBytes = body.contentLength()
                    var downloaded = 0L
                    val buffer = ByteArray(32768)
                    val input = body.byteStream()

                    FileOutputStream(apkFile).use { out ->
                        var read: Int
                        var lastEmit = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (totalBytes > 0 && now - lastEmit > 100) {
                                lastEmit = now
                                val prog = (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
                                _status.value = UpdateStatus.Downloading(prog, downloaded, totalBytes, info)
                            }
                        }
                    }
                }

                _status.value = UpdateStatus.ReadyToInstall(apkFile, info, isLatest = true)

                withContext(Dispatchers.Main) {
                    launchInstaller(context, apkFile)
                }
            } catch (e: Exception) {
                android.util.Log.e("AppUpdateManager", "Erreur téléchargement APK: ${e.message}")
                _status.value = UpdateStatus.Error("Erreur téléchargement: ${e.message}")
            }
        }
    }

    fun launchInstaller(context: Context, apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("AppUpdateManager", "Impossible de lancer l'installateur: ${e.message}")
            _status.value = UpdateStatus.Error("Impossible d'ouvrir l'installateur : ${e.message}")
        }
    }

    private data class AssetCandidate(val name: String, val url: String, val size: Long)

    private fun selectBestAsset(assets: org.json.JSONArray): AssetCandidate? {
        val list = mutableListOf<AssetCandidate>()
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name", "")
            val url = a.optString("browser_download_url", "")
            val size = a.optLong("size", 0L)
            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                list.add(AssetCandidate(name, url, size))
            }
        }
        if (list.isEmpty()) return null

        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        for (abi in supportedAbis) {
            val match = list.find { it.name.contains(abi, ignoreCase = true) }
            if (match != null) return match
        }

        return list.firstOrNull()
    }

    private fun isNewer(remote: String, current: String): Boolean {
        if (remote.isBlank()) return false
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
