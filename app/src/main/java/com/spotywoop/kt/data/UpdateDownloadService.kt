package com.spotywoop.kt.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.spotywoop.kt.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Télécharge la mise à jour au premier plan, avec une notification de progression : le téléchargement
 * continue quand on quitte l'appli. À la fin, la notification « Installer » ouvre l'installateur.
 */
class UpdateDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val info = pending
        if (info == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIF_PROGRESS,
            progressNotification(info, 0f),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (job?.isActive == true) return START_NOT_STICKY
        job = scope.launch {
            val manager = getSystemService(NotificationManager::class.java)
            var shown = -1
            val result = runCatching {
                AppUpdateManager.download(applicationContext, info) { progress ->
                    val pct = (progress * 100).toInt()
                    if (pct != shown) {
                        shown = pct
                        manager.notify(NOTIF_PROGRESS, progressNotification(info, progress))
                    }
                }
            }
            ServiceCompat.stopForeground(this@UpdateDownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            result.onSuccess { apk -> manager.notify(NOTIF_DONE, doneNotification(info, apk)) }
                .onFailure { manager.notify(NOTIF_DONE, failedNotification(info)) }
            pending = null
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Téléchargement des mises à jour", NotificationManager.IMPORTANCE_LOW))
        // Fin du téléchargement : visible en clair (pas rangée dans les notifications silencieuses).
        manager.createNotificationChannel(NotificationChannel(CHANNEL_READY, "Mise à jour prête", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(info: UpdateInfo, progress: Float) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Spotywoop ${info.tagName}")
            .setContentText("Téléchargement de la mise à jour… ${(progress * 100).toInt()} %")
            .setProgress(100, (progress * 100).toInt(), progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun doneNotification(info: UpdateInfo, apk: File): android.app.Notification {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pi = PendingIntent.getActivity(this, 1, install, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_READY)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Spotywoop ${info.tagName} est prête")
            .setContentText("Touchez pour installer la mise à jour")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
    }

    private fun failedNotification(info: UpdateInfo) =
        NotificationCompat.Builder(this, CHANNEL_READY)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Spotywoop ${info.tagName}")
            .setContentText("Le téléchargement a échoué. Touchez la bulle dans l'appli pour réessayer.")
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()

    companion object {
        private const val CHANNEL = "app_update"
        private const val CHANNEL_READY = "app_update_ready"
        private const val NOTIF_PROGRESS = 7101
        private const val NOTIF_DONE = 7102

        /** Mise à jour à télécharger, passée par l'appli au service (même processus). */
        @Volatile private var pending: UpdateInfo? = null

        fun start(context: Context, info: UpdateInfo) {
            pending = info
            context.getSystemService(NotificationManager::class.java).cancel(NOTIF_DONE)
            ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadService::class.java))
        }
    }
}
