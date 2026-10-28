package com.spotywoop.kt.data

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.spotywoop.kt.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Journal de l'appli dans des fichiers, pour remonter un bug après coup.
 *
 * Tout ce que le processus écrit dans logcat (nos Log.*, ExoPlayer EventLogger, erreurs système
 * du processus) est recopié dans `Android/data/com.spotywoop.kt/files/logs/` : un fichier par
 * lancement, coupé à [MAX_FILE_BYTES], les [MAX_FILES] plus récents gardés. Un plantage est écrit
 * directement dans le fichier avant que le processus meure.
 *
 * Récupérer : Paramètres → Journal → Partager, ou
 * `adb pull /sdcard/Android/data/com.spotywoop.kt/files/logs`.
 */
object AppLog {
    private const val TAG = "AppLog"
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val MAX_FILES = 12

    private lateinit var dir: File
    private val lock = Any()
    private var out: FileOutputStream? = null
    private var current: File? = null
    private var written = 0L
    private var part = 0
    private val session = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }
        openNewFile()
        installCrashHandler()
        Thread(::pumpLogcat, "AppLog-logcat").apply { isDaemon = true }.start()
    }

    fun files(): List<File> =
        if (!::dir.isInitialized) emptyList()
        else dir.listFiles { f -> f.name.endsWith(".log") }.orEmpty().sortedByDescending { it.name }

    fun totalBytes(): Long = files().sumOf { it.length() }

    /** Tous les journaux dans une archive à partager. */
    fun zip(context: Context): File {
        val zip = File(context.cacheDir, "logs/spotywoop-logs-$session.zip").apply { parentFile?.mkdirs() }
        synchronized(lock) { out?.flush() }
        ZipOutputStream(zip.outputStream()).use { z ->
            files().forEach { f ->
                z.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return zip
    }

    /** Efface les anciens journaux ; celui du lancement en cours repart à zéro. */
    fun clear() {
        synchronized(lock) {
            files().forEach { it.delete() }
            out?.close()
            out = null
            part = 0
            openNewFile()
        }
        Log.i(TAG, "Journaux effacés")
    }

    private fun openNewFile(): Unit = synchronized(lock) {
        out?.close()
        val name = "spotywoop-$session" + (if (part > 0) "-$part" else "") + ".log"
        part++
        current = File(dir, name)
        out = FileOutputStream(current, true)
        written = current!!.length()
        files().drop(MAX_FILES).forEach { it.delete() }
        writeRaw(
            "===== Spotywoop ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                "${Build.SUPPORTED_ABIS.firstOrNull()} · pid ${Process.myPid()} =====\n",
        )
    }

    private fun writeRaw(text: String): Unit = synchronized(lock) {
        val o = out ?: return
        val bytes = text.toByteArray()
        o.write(bytes)
        written += bytes.size
        if (written > MAX_FILE_BYTES) openNewFile()
    }

    /** Lit le logcat de notre seul processus (aucune permission nécessaire) et le recopie. */
    private fun pumpLogcat() {
        try {
            val proc = ProcessBuilder(
                "logcat", "-b", "main,system,crash", "-v", "threadtime", "--pid=${Process.myPid()}",
            ).redirectErrorStream(true).start()
            // FileOutputStream n'a pas de tampon : chaque ligne est sur le disque dès qu'elle est lue.
            proc.inputStream.bufferedReader().forEachLine { writeRaw(it + "\n") }
            writeRaw("${stamp()} AppLog : logcat s'est arrêté (code ${proc.waitFor()})\n")
        } catch (e: Exception) {
            writeRaw("${stamp()} AppLog : lecture de logcat impossible : $e\n")
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                writeRaw("${stamp()} PLANTAGE dans le thread « ${thread.name} »\n$trace\n")
                synchronized(lock) { out?.flush(); out?.fd?.sync() }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun stamp() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    /**
     * Trace chaque appel HTTP : méthode, adresse, code, durée, taille. Les en-têtes ne sont jamais
     * écrits (clé d'API, jetons) et les paramètres sensibles de l'adresse sont masqués.
     */
    object Http : Interceptor {
        private val SECRET_PARAMS = Regex("(?i)([?&](?:key|api_?key|token|access_token|secret|sig|signature)=)[^&]*")

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val url = SECRET_PARAMS.replace(req.url.toString(), "$1***")
            val start = System.nanoTime()
            val res = try {
                chain.proceed(req)
            } catch (e: Exception) {
                Log.w("HTTP", "${req.method} $url → ÉCHEC après ${ms(start)} ms : $e")
                throw e
            }
            val size = res.body?.contentLength()?.takeIf { it >= 0 }?.let { " · $it o" } ?: ""
            val line = "${req.method} $url → ${res.code} en ${ms(start)} ms$size"
            if (res.isSuccessful || res.isRedirect) Log.d("HTTP", line) else Log.w("HTTP", line)
            return res
        }

        private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000
    }
}
