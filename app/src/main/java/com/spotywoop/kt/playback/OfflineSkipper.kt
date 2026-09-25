package com.spotywoop.kt.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.spotywoop.kt.data.DownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hors connexion, saute les titres non téléchargés de la file jusqu'au prochain
 * titre téléchargé (dans le sens de navigation : suivant ou précédent).
 * Rien n'est retiré de la file : dès que la connexion revient, tout redevient jouable.
 */
class OfflineSkipper(private val context: Context, private val player: Player) : Player.Listener {

    private var lastIndex = C.INDEX_UNSET

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val index = player.currentMediaItemIndex
        val backward = lastIndex != C.INDEX_UNSET && index != lastIndex &&
            index == player.currentTimeline.getPreviousWindowIndex(lastIndex, navRepeatMode(), player.shuffleModeEnabled)
        lastIndex = index
        skipIfUnavailable(backward)
    }

    override fun onPlayerError(error: PlaybackException) {
        skipIfUnavailable(backward = false)
    }

    private fun skipIfUnavailable(backward: Boolean) {
        if (isOnline(context)) return
        val current = player.currentMediaItemIndex
        if (current == C.INDEX_UNSET || isPlayable(current)) return

        val target = findPlayable(current, backward) ?: if (backward) findPlayable(current, false) else null
        if (target == null) {
            player.stop()
            _notice.value = "Aucun titre disponible hors connexion"
            return
        }
        lastIndex = target
        player.seekToDefaultPosition(target)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    private fun findPlayable(from: Int, backward: Boolean): Int? {
        val timeline = player.currentTimeline
        var i = from
        repeat(timeline.windowCount) {
            i = if (backward) {
                timeline.getPreviousWindowIndex(i, navRepeatMode(), player.shuffleModeEnabled)
            } else {
                timeline.getNextWindowIndex(i, navRepeatMode(), player.shuffleModeEnabled)
            }
            if (i == C.INDEX_UNSET || i == from) return null
            if (isPlayable(i)) return i
        }
        return null
    }

    private fun isPlayable(index: Int): Boolean =
        DownloadManager.isDownloaded(player.getMediaItemAt(index).mediaId)

    // En « répéter un titre », la navigation parcourt quand même la file.
    private fun navRepeatMode(): Int =
        if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else player.repeatMode

    companion object {
        private val _notice = MutableStateFlow<String?>(null)
        /** Message à afficher à l'utilisateur (consommé par PlayerViewModel). */
        val notice: StateFlow<String?> = _notice.asStateFlow()

        fun consumeNotice() {
            _notice.value = null
        }

        fun isOnline(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
