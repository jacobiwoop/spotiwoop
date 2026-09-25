package com.spotywoop.kt.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.spotywoop.kt.MainActivity

/**
 * Lecteur en tâche de fond.
 *
 * Chaque titre porte l'URI `spotywoop://track/<id>` ; la vraie URL est résolue
 * au moment où ExoPlayer charge le titre (thread de chargement IO), via StreamResolver.
 *
 * Quand la résolution retourne une data URI MPEG-DASH
 * (`data:application/dash+xml;base64,...`), ExoPlayer la gère nativement
 * grâce à media3-exoplayer-dash.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // Initialise StreamResolver avec SpotiflacSource en priorité
        StreamResolver.init(applicationContext)
        // Le service peut être recréé sans l'activité : le catalogue hors-ligne doit être chargé
        com.spotywoop.kt.data.DownloadManager.init(applicationContext)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(LocalYoutubeDlSource.CHROME_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            resolveSpec(spec)
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(OfflineSkipper(applicationContext, player))

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()
    }

    @OptIn(UnstableApi::class)
    private fun resolveSpec(spec: DataSpec): DataSpec {
        val trackId = trackIdOf(spec.uri) ?: return spec
        val artist = spec.uri.getQueryParameter("artist")
        val title = spec.uri.getQueryParameter("title")
        val durationMs = spec.uri.getQueryParameter("durationMs")?.toLongOrNull()
        val stream = StreamResolver.resolve(trackId, artist, title, durationMs)
        return spec.buildUpon()
            .setUri(Uri.parse(stream.url))
            .setHttpRequestHeaders(spec.httpRequestHeaders + stream.headers)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val SCHEME = "spotywoop"

        fun trackUri(trackId: String, artist: String? = null, title: String? = null, durationMs: Long? = null): Uri {
            val builder = Uri.Builder()
                .scheme(SCHEME)
                .authority("track")
                .appendPath(trackId)
            if (!artist.isNullOrBlank()) builder.appendQueryParameter("artist", artist)
            if (!title.isNullOrBlank()) builder.appendQueryParameter("title", title)
            if (durationMs != null && durationMs > 0) builder.appendQueryParameter("durationMs", durationMs.toString())
            return builder.build()
        }

        fun trackIdOf(uri: Uri): String? =
            if (uri.scheme == SCHEME && uri.host == "track") uri.lastPathSegment else null
    }
}
