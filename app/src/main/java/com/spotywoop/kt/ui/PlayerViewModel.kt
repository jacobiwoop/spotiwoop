package com.spotywoop.kt.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.spotywoop.kt.data.LyricsClient
import com.spotywoop.kt.data.LyricsResult
import com.spotywoop.kt.data.TrackResult
import com.spotywoop.kt.data.YouTubeRadioClient
import com.spotywoop.kt.playback.PlaybackService
import com.spotywoop.kt.playback.ResolvedStream
import com.spotywoop.kt.playback.StreamResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NowPlaying(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val cover: String?,
    val explicit: Boolean,
)

enum class PlaybackMode {
    NORMAL,
    SHUFFLE,
    REPEAT_ALL,
    REPEAT_ONE
}

data class PlayerUiState(
    val current: NowPlaying? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val playbackMode: PlaybackMode = PlaybackMode.NORMAL,
    val stream: ResolvedStream? = null,
    val error: String? = null,
    val lyrics: LyricsResult? = null,
    val loadingLyrics: Boolean = false,
    val showLyrics: Boolean = false,
    val radioQueue: List<TrackResult> = emptyList(),
    val isRadioActive: Boolean = false,
    val isAutoRadio: Boolean = false,
    val radioSeedTitle: String? = null,
    val loadingRadio: Boolean = false,
)

/** Pont entre l'UI Compose et le PlaybackService, via un MediaController. */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var pending: (MediaController.() -> Unit)? = null
    private var isPrefetchingAutoplay = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()

        override fun onPlayerError(error: PlaybackException) {
            val cause = generateSequence<Throwable>(error) { it.cause }.last()
            val currentId = _state.value.current?.id
            if (currentId != null) {
                StreamResolver.invalidate(currentId)
            }
            _state.update { it.copy(error = cause.message ?: error.errorCodeName) }
        }
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            controller = future.get().also {
                it.addListener(listener)
                pending?.invoke(it)
                pending = null
            }
            refresh()
        }, ContextCompat.getMainExecutor(app))

        viewModelScope.launch {
            while (isActive) {
                if (controller?.isPlaying == true) refresh()
                delay(500)
            }
        }
        viewModelScope.launch {
            StreamResolver.resolved.collect { refresh() }
        }
    }

    fun playQueue(tracks: List<TrackResult>, startIndex: Int) {
        val items = tracks.map { it.toMediaItem() }
        withController {
            setMediaItems(items, startIndex, 0L)
            prepare()
            play()
        }
        _state.update {
            it.copy(
                error = null,
                radioQueue = tracks,
                isRadioActive = false,
                isAutoRadio = false,
                radioSeedTitle = null,
            )
        }
    }

    /**
     * Joue un titre immédiatement et lance une Radio Auto Glissante :
     * 1. Démarre le morceau instantanément (0 ms)
     * 2. Va chercher 5 morceaux similaires sur YouTube
     * 3. Recharge automatiquement 5 morceaux quand on arrive à l'avant-dernier, basés sur le dernier
     */
    fun playTrackWithAutoRadio(track: TrackResult) {
        val item = track.toMediaItem()
        withController {
            setMediaItems(listOf(item), 0, 0L)
            prepare()
            play()
        }
        _state.update {
            it.copy(
                error = null,
                radioQueue = listOf(track),
                isRadioActive = true,
                isAutoRadio = true,
                radioSeedTitle = "${track.name} · ${track.artists}",
                loadingRadio = true,
            )
        }

        viewModelScope.launch {
            try {
                val query = "${track.artists} ${track.name}"
                val radioTracks = YouTubeRadioClient.fetchRadioTracks(query, limit = 15)
                // YouTube Mix place toujours le morceau graine en position 0 : on l'élimine systématiquement
                val candidates = if (radioTracks.isNotEmpty()) radioTracks.drop(1) else emptyList()
                val cleanSeed = cleanSongTitle(track.name)
                val next5 = candidates.filter { candidate ->
                    val cleanCandidate = cleanSongTitle(candidate.name)
                    candidate.id != track.id &&
                        cleanCandidate != cleanSeed &&
                        (cleanSeed.length <= 3 || !cleanCandidate.contains(cleanSeed))
                }.take(5)
                if (next5.isNotEmpty()) {
                    val mediaItems = next5.map { it.toMediaItem() }
                    withController {
                        addMediaItems(mediaItems)
                    }
                    _state.update {
                        it.copy(
                            radioQueue = listOf(track) + next5,
                            loadingRadio = false,
                        )
                    }
                } else {
                    _state.update { it.copy(loadingRadio = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loadingRadio = false) }
            }
        }
    }

    fun togglePlay() = withController {
        if (playbackState == Player.STATE_ENDED) seekTo(0)
        if (playbackState == Player.STATE_IDLE) prepare()
        if (isPlaying) pause() else play()
    }

    fun next() = withController {
        if (hasNextMediaItem()) {
            seekToNextMediaItem()
        } else {
            val queue = _state.value.radioQueue
            val currentId = currentMediaItem?.mediaId
            val currentIndex = queue.indexOfFirst { it.id == currentId }
            if (currentIndex >= 0 && currentIndex < queue.size - 1) {
                playTrackWithAutoRadio(queue[currentIndex + 1])
            } else {
                _state.value.current?.let { triggerAutoplay(this, it) }
            }
        }
    }

    fun previous() = withController {
        if (currentPosition > 3_000 || !hasPreviousMediaItem()) seekTo(0) else seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) = withController { seekTo(positionMs) }

    fun playTrackFromQueue(track: TrackResult) {
        val c = controller ?: return
        val queue = _state.value.radioQueue
        val index = queue.indexOfFirst { it.id == track.id }
        if (index >= 0 && index < c.mediaItemCount) {
            withController {
                seekToDefaultPosition(index)
                play()
            }
        } else {
            playTrackWithAutoRadio(track)
        }
    }

    /**
     * Lance la radio explicite basée sur le morceau en cours d'écoute :
     * Récupère 25 titres similaires fixes via YouTube Mix (Innertube), remplace la file et lance la lecture.
     */
    fun startRadioForCurrentTrack() {
        val now = _state.value.current ?: return
        viewModelScope.launch {
            _state.update { it.copy(loadingRadio = true) }
            val query = "${now.artist} ${now.title}"
            val tracks = YouTubeRadioClient.fetchRadioTracks(query, limit = 25)

            if (tracks.isNotEmpty()) {
                val items = tracks.map { it.toMediaItem() }
                withController {
                    setMediaItems(items, 0, 0L)
                    prepare()
                    play()
                }
                _state.update {
                    it.copy(
                        radioQueue = tracks,
                        isRadioActive = true,
                        isAutoRadio = false, // Mode explicite = 25 morceaux fixes
                        radioSeedTitle = "${now.title} · ${now.artist}",
                        loadingRadio = false,
                        error = null,
                    )
                }
            } else {
                _state.update { it.copy(loadingRadio = false, error = "Impossible de charger la radio") }
            }
        }
    }

    fun cyclePlaybackMode() {
        val nextMode = when (_state.value.playbackMode) {
            PlaybackMode.NORMAL -> PlaybackMode.SHUFFLE
            PlaybackMode.SHUFFLE -> PlaybackMode.REPEAT_ALL
            PlaybackMode.REPEAT_ALL -> PlaybackMode.REPEAT_ONE
            PlaybackMode.REPEAT_ONE -> PlaybackMode.NORMAL
        }
        setPlaybackMode(nextMode)
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        _state.update { it.copy(playbackMode = mode) }
        withController {
            when (mode) {
                PlaybackMode.NORMAL -> {
                    shuffleModeEnabled = false
                    repeatMode = Player.REPEAT_MODE_OFF
                }
                PlaybackMode.SHUFFLE -> {
                    shuffleModeEnabled = true
                    repeatMode = Player.REPEAT_MODE_OFF
                }
                PlaybackMode.REPEAT_ALL -> {
                    shuffleModeEnabled = false
                    repeatMode = Player.REPEAT_MODE_ALL
                }
                PlaybackMode.REPEAT_ONE -> {
                    shuffleModeEnabled = false
                    repeatMode = Player.REPEAT_MODE_ONE
                }
            }
        }
    }

    private fun withController(action: MediaController.() -> Unit) {
        val c = controller
        if (c == null) pending = action else c.action()
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val current = item?.let {
            val md = it.mediaMetadata
            NowPlaying(
                id = it.mediaId,
                title = md.title?.toString().orEmpty(),
                artist = md.artist?.toString().orEmpty(),
                album = md.albumTitle?.toString().orEmpty(),
                cover = md.artworkUri?.toString(),
                explicit = md.extras?.getBoolean(EXTRA_EXPLICIT) == true,
            )
        }
        val trackChanged = current?.id != _state.value.current?.id
        _state.update {
            it.copy(
                current = current,
                isPlaying = c.isPlaying,
                buffering = c.playbackState == Player.STATE_BUFFERING,
                positionMs = c.currentPosition.coerceAtLeast(0),
                durationMs = c.duration.takeIf { d -> d > 0 } ?: 0,
                hasNext = c.hasNextMediaItem(),
                hasPrevious = c.hasPreviousMediaItem(),
                stream = current?.let { now -> StreamResolver.resolved.value[now.id] },
                error = if (trackChanged) null else it.error,
                lyrics = if (trackChanged) null else it.lyrics,
            )
        }

        if (trackChanged && current != null) {
            loadLyrics(current)
        }

        // Mode infini automatique (Radio Auto Glissante) :
        // Dès qu'on arrive à l'avant-dernier morceau (currentMediaItemIndex >= mediaItemCount - 2),
        // on charge 5 nouveaux morceaux en se basant sur le DERNIER morceau actuel !
        if (current != null && !isPrefetchingAutoplay) {
            val isNearEnd = c.mediaItemCount >= 2 && c.currentMediaItemIndex >= c.mediaItemCount - 2
            val isAtEnd = !c.hasNextMediaItem()
            if (_state.value.isAutoRadio && (isNearEnd || isAtEnd)) {
                triggerAutoRadioRolling(c)
            } else if (!c.hasNextMediaItem()) {
                triggerAutoplay(c, current)
            }
        }
    }

    /**
     * Radio Auto Glissante : recharge 5 morceaux basés sur le DERNIER morceau de la file.
     */
    private fun triggerAutoRadioRolling(c: MediaController) {
        isPrefetchingAutoplay = true
        viewModelScope.launch {
            try {
                val queue = _state.value.radioQueue
                val lastTrack = queue.lastOrNull()
                val seedQuery = if (lastTrack != null) "${lastTrack.artists} ${lastTrack.name}" else "${_state.value.current?.artist} ${_state.value.current?.title}"
                val suggestions = YouTubeRadioClient.fetchRadioTracks(seedQuery, limit = 15)
                // Élimine le premier élément qui est le seed lui-même
                val candidates = if (suggestions.isNotEmpty()) suggestions.drop(1) else emptyList()
                val existingIds = queue.map { it.id }.toSet()
                val existingTitles = queue.map { cleanSongTitle(it.name) }.toSet()

                val new5 = candidates
                    .filterNot { candidate ->
                        val clean = cleanSongTitle(candidate.name)
                        candidate.id in existingIds ||
                            clean in existingTitles ||
                            existingTitles.any { ex -> ex.length > 3 && (clean.contains(ex) || ex.contains(clean)) }
                    }
                    .take(5)

                if (new5.isNotEmpty()) {
                    val newItems = new5.map { it.toMediaItem() }
                    withController {
                        addMediaItems(newItems)
                    }
                    _state.update {
                        it.copy(radioQueue = it.radioQueue + new5)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("PlayerViewModel", "Échec auto-radio rolling: ${e.message}")
            } finally {
                delay(3000)
                isPrefetchingAutoplay = false
            }
        }
    }

    /**
     * Fallback standard pour lecture non-radio en fin de file.
     */
    private fun triggerAutoplay(c: MediaController, current: NowPlaying) {
        isPrefetchingAutoplay = true
        viewModelScope.launch {
            try {
                val query = "${current.artist} ${current.title}"
                val suggestions = YouTubeRadioClient.fetchRadioTracks(query, limit = 10)
                val candidates = if (suggestions.isNotEmpty()) suggestions.drop(1) else emptyList()
                val cleanCurrent = cleanSongTitle(current.title)
                val existingIds = _state.value.radioQueue.map { it.id }.toSet() + current.id
                val newTracks = candidates.filterNot { candidate ->
                    val clean = cleanSongTitle(candidate.name)
                    candidate.id in existingIds ||
                        clean == cleanCurrent ||
                        (cleanCurrent.length > 3 && clean.contains(cleanCurrent))
                }

                if (newTracks.isNotEmpty()) {
                    val newItems = newTracks.map { it.toMediaItem() }
                    withController {
                        addMediaItems(newItems)
                    }
                    _state.update {
                        it.copy(radioQueue = it.radioQueue + newTracks)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("PlayerViewModel", "Échec autoplay: ${e.message}")
            } finally {
                delay(2000)
                isPrefetchingAutoplay = false
            }
        }
    }

    private fun cleanSongTitle(raw: String): String {
        return raw.lowercase()
            .replace(Regex("""\([^)]*\)"""), "")
            .replace(Regex("""\[[^]]*\]"""), "")
            .replace(Regex("""\b(feat\.?|ft\.?|official|video|audio|lyrics?|radio edit|remaster(ed)?)\b.*"""), "")
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    fun toggleLyrics() {
        _state.update { it.copy(showLyrics = !it.showLyrics) }
    }

    private fun loadLyrics(track: NowPlaying) {
        viewModelScope.launch {
            _state.update { it.copy(loadingLyrics = true) }
            val res = LyricsClient.fetchLyrics(
                artist = track.artist,
                title = track.title,
                album = track.album,
            )
            if (_state.value.current?.id == track.id) {
                _state.update { it.copy(lyrics = res, loadingLyrics = false) }
            }
        }
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controller?.release()
        super.onCleared()
    }

    private companion object {
        const val EXTRA_EXPLICIT = "explicit"

        fun TrackResult.toMediaItem(): MediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setUri(PlaybackService.trackUri(id, artists, name, durationMs))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setArtist(artists)
                    .setAlbumTitle(album)
                    .setArtworkUri(cover?.let(Uri::parse))
                    .setExtras(Bundle().apply { putBoolean(EXTRA_EXPLICIT, isExplicit) })
                    .build(),
            )
            .build()
    }
}
