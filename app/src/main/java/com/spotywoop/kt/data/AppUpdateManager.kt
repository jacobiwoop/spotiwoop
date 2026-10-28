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
    private val http = OkHttpClient.Builder().addInterceptor(com.spotywoop.kt.data.AppLog.Http)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var appContext: Context? = null

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /** Dernière version trouvée : la bulle la garde pour relancer le téléchargement après une erreur. */
    private val _latest = MutableStateFlow<UpdateInfo?>(null)
    val latest: StateFlow<UpdateInfo?> = _latest.asStateFlow()

    private const val PREFS = "app_update"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_DOWNLOAD_ASSET = "download_asset"

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

                        _latest.value = info
                        appContext?.let(::forgetSystemDownload)

                        // Vérifier si un APK existe déjà dans le cache
                        val ctx = appContext
                        var previousWasObsolete = false
                        if (ctx != null) {
                            val updatesDir = getUpdatesDir(ctx)
                            if (updatesDir.exists()) {
                                val targetFile = File(updatesDir, targetAsset.name)
                                if (isValidApk(ctx, targetFile, targetAsset.size)) {
                                    // Fichier déjà téléchargé et correspond à la toute dernière version !
                                    android.util.Log.i("AppUpdateManager", "APK déjà présent et à jour: ${targetFile.name}")
                                    _status.value = UpdateStatus.ReadyToInstall(targetFile, info, isLatest = true)
                                    return@launch
                                } else {
                                    // APK obsolète ou incomplet (téléchargement interrompu) : nettoyer
                                    val keep = File(updatesDir, targetAsset.name + ".part") // reprise du téléchargement
                                    val oldApks = updatesDir.listFiles { f -> f != keep && (f.extension.equals("apk", ignoreCase = true) || f.extension == "part") }
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

    /**
     * Lance le téléchargement de l'APK dans [UpdateDownloadService], un service au premier plan avec sa
     * notification de progression : il continue quand on quitte l'appli. On n'utilise plus le
     * gestionnaire de téléchargements d'Android, que certains téléphones (Xiaomi) laissent en attente
     * sans jamais démarrer.
     */
    fun downloadAndInstall(context: Context, info: UpdateInfo) {
        if (_status.value is UpdateStatus.Downloading) return
        val ctx = context.applicationContext
        _latest.value = info
        _status.value = UpdateStatus.Downloading(0f, 0L, info.assetSize, info)
        try {
            UpdateDownloadService.start(ctx, info)
        } catch (e: Exception) {
            android.util.Log.e("AppUpdateManager", "Service de téléchargement non lancé: ${e.message}")
            _status.value = UpdateStatus.Error("Erreur téléchargement: ${e.message}")
        }
    }

    /**
     * Télécharge [info] dans le dossier des mises à jour, en reprenant un fichier .part interrompu,
     * et met à jour [status]. Appelé par [UpdateDownloadService] ; rend l'APK vérifié.
     */
    internal suspend fun download(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = getUpdatesDir(ctx)
        val apk = File(dir, info.assetName)
        val part = File(dir, info.assetName + ".part")
        dir.listFiles { f -> f != part && (f.extension.equals("apk", ignoreCase = true) || f.extension == "part") }?.forEach { it.delete() }
        var attempt = 0
        while (true) {
            try {
                val from = part.length()
                val req = Request.Builder().url(info.downloadUrl)
                    .header("User-Agent", "Spotywoop/${BuildConfig.VERSION_NAME}")
                    .apply { if (from > 0) header("Range", "bytes=$from-") }
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("Réponse vide")
                    val append = from > 0 && resp.code == 206
                    var done = if (append) from else 0L
                    val total = if (info.assetSize > 0) info.assetSize else done + body.contentLength()
                    java.io.FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        val input = body.byteStream()
                        var lastEmit = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 300) {
                                lastEmit = now
                                val progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                                _status.value = UpdateStatus.Downloading(progress, done, total, info)
                                onProgress(progress)
                            }
                        }
                    }
                }
                break
            } catch (e: IOException) {
                // Coupure réseau : on reprend là où on s'était arrêté (quelques essais).
                if (++attempt >= 5) {
                    _status.value = UpdateStatus.Error("Échec du téléchargement, réessayez")
                    throw e
                }
                kotlinx.coroutines.delay(2_000L * attempt)
            }
        }
        apk.delete()
        if (!part.renameTo(apk) || !isValidApk(ctx, apk, info.assetSize)) {
            apk.delete()
            part.delete()
            _status.value = UpdateStatus.Error("APK corrompu, réessayez")
            throw IOException("APK corrompu")
        }
        _status.value = UpdateStatus.ReadyToInstall(apk, info, isLatest = true)
        apk
    }

    /** Ancienne version : retire de la file d'Android le téléchargement qu'elle y avait mis. */
    private fun forgetSystemDownload(ctx: Context) {
        val p = prefs(ctx)
        val id = p.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id < 0) return
        runCatching { (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager).remove(id) }
        p.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_ASSET).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getUpdatesDir(context: Context): File {
        // Dossier propre à l'appli (aucune permission requise), partagé avec l'installateur par le FileProvider.
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "updates").also { it.mkdirs() }
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

            // Accorder explicitement la permission à toutes les applications susceptibles de gérer l'installation
            val resolveInfos = context.packageManager.queryIntentActivities(
                intent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
            for (resolveInfo in resolveInfos) {
                context.grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("AppUpdateManager", "Impossible de lancer l'installateur: ${e.message}")
            _status.value = UpdateStatus.Error("Impossible d'ouvrir l'installateur : ${e.message}")
        }
    }

    /** Taille attendue + lisible par le PackageManager (sinon « package non valide »). */
    private fun isValidApk(context: Context, file: File, expectedSize: Long): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        if (expectedSize > 0 && file.length() != expectedSize) return false
        return try {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) != null
        } catch (e: Exception) {
            false
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
