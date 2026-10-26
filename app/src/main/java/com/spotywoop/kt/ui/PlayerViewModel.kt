package com.spotywoop.kt.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import com.spotywoop.kt.playback.OfflineSkipper
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
    /** Durée annoncée par la source (Spotify, Audiomack), 0 si inconnue. */
    val durationMs: Long = 0,
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
    private var wasOnline = true

    // Retour de la connexion : les titres sautés redeviennent jouables, on relance le pré-chargement.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
            viewModelScope.launch {
                if (!wasOnline) controller?.let { preloadNextTrack(it) }
                wasOnline = true
            }
        }

        override fun onLost(network: Network) {
            viewModelScope.launch { wasOnline = OfflineSkipper.isOnline(getApplication()) }
        }
    }

    private var lastErrorRetryTrackId: String? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()

        override fun onPlayerError(error: PlaybackException) {
            val cause = generateSequence<Throwable>(error) { it.cause }.last()
            val currentId = _state.value.current?.id
            val errorMsg = cause.message.orEmpty()
            val is403OrIo = errorMsg.contains("403") || error.errorCodeName.contains("IO") || error.errorCodeName.contains("BEHIND_LIVE_WINDOW")

            if (currentId != null) {
                StreamResolver.invalidate(currentId)
            }

            // Auto-relance immédiate si c'est une erreur 403 / IO / transition réseau (1 tentative par morceau)
            if (currentId != null && is403OrIo && lastErrorRetryTrackId != currentId) {
                lastErrorRetryTrackId = currentId
                android.util.Log.w("PlayerViewModel", "Auto-relance suite à erreur réseau/403 sur $currentId")
                withController {
                    prepare()
                    play()
                }
                return
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
        viewModelScope.launch {
            OfflineSkipper.notice.collect { msg ->
                if (msg != null) {
                    _state.update { it.copy(error = msg) }
                    OfflineSkipper.consumeNotice()
                }
            }
        }

        wasOnline = OfflineSkipper.isOnline(app)
        app.getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            networkCallback,
        )
    }

    fun playQueue(tracks: List<TrackResult>, startIndex: Int) {
        val selectedTrack = tracks.getOrNull(startIndex) ?: return
        if (!OfflineSkipper.isOnline(getApplication()) && !com.spotywoop.kt.data.DownloadManager.isDownloaded(selectedTrack.id)) {
            _state.update { it.copy(error = "Ce titre n'est pas disponible hors connexion") }
            return
        }
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
        if (!OfflineSkipper.isOnline(getApplication()) && !com.spotywoop.kt.data.DownloadManager.isDownloaded(track.id)) {
            _state.update { it.copy(error = "Ce titre n'est pas disponible hors connexion") }
            return
        }
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
                val candidates = suggestions(track.id, track.artists, track.name, track.durationMs, 15)
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
                        preloadNextTrack(this)
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
        } else if (_state.value.isRadioActive) {
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
        if (!OfflineSkipper.isOnline(getApplication()) && !com.spotywoop.kt.data.DownloadManager.isDownloaded(track.id)) {
            _state.update { it.copy(error = "Ce titre n'est pas disponible hors connexion") }
            return
        }
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
     * Radio du titre : le titre en cours puis ses suggestions (YouTube Mix d'abord, voir suggestions()).
     */
    fun startRadioForCurrentTrack() {
        if (!OfflineSkipper.isOnline(getApplication())) {
            _state.update { it.copy(error = "Radio indisponible hors connexion") }
            return
        }
        val now = _state.value.current ?: return
        viewModelScope.launch {
            _state.update { it.copy(loadingRadio = true) }
            // La file repart du titre en cours, suivi de ses suggestions.
            val suggested = suggestions(now.id, now.artist, now.title, now.durationMs, 24)
            val tracks = if (suggested.isEmpty()) emptyList() else listOf(now.toTrackResult()) + suggested

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
                durationMs = md.extras?.getLong("durationMs") ?: 0,
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
            lastErrorRetryTrackId = null
            loadLyrics(current)
            preloadNextTrack(c)
        }

        // Mode infini automatique (Radio Auto Glissante) :
        // Dès qu'on arrive à l'avant-dernier morceau (currentMediaItemIndex >= mediaItemCount - 2),
        // on charge 5 nouveaux morceaux en se basant sur le DERNIER morceau actuel !
        // Pas pendant le premier chargement des suggestions : la file n'a alors qu'un titre et paraît
        // finie, ce qui ajoutait une deuxième fois les mêmes suggestions.
        if (current != null && !isPrefetchingAutoplay && !_state.value.loadingRadio) {
            val isNearEnd = c.mediaItemCount >= 2 && c.currentMediaItemIndex >= c.mediaItemCount - 2
            val isAtEnd = !c.hasNextMediaItem()
            if (_state.value.isAutoRadio && (isNearEnd || isAtEnd)) {
                triggerAutoRadioRolling(c)
            } else if (_state.value.isRadioActive && !c.hasNextMediaItem()) {
                triggerAutoplay(c, current)
            }
        }
    }

    private fun preloadNextTrack(c: MediaController) {
        if (!OfflineSkipper.isOnline(getApplication())) return
        val nextIndex = c.currentMediaItemIndex + 1
        if (nextIndex < c.mediaItemCount) {
            val nextItem = c.getMediaItemAt(nextIndex)
            val trackId = nextItem.mediaId
            val artist = nextItem.mediaMetadata.artist?.toString()
            val title = nextItem.mediaMetadata.title?.toString()
            val durationMs = nextItem.mediaMetadata.extras?.getLong("durationMs")
            if (trackId.isNotBlank()) {
                StreamResolver.prefetch(trackId, artist, title, durationMs)
            }
        }
    }

    /**
     * Radio Auto Glissante : recharge 5 morceaux basés sur le DERNIER morceau de la file.
     */
    /**
     * Suggestions d'un titre, au choix de l'utilisateur : YouTube Mix d'abord (vrais titres, variés),
     * puis la radio Spotify de l'artiste, puis les titres liés d'Audiomack. Le titre de départ est retiré.
     */
    private suspend fun suggestions(seedId: String, artists: String, title: String, durationMs: Long, limit: Int): List<TrackResult> {
        val seed = cleanSongTitle(title)
        fun List<TrackResult>.withoutSeed() = filter { it.id != seedId && cleanSongTitle(it.name) != seed }
        val sources = listOf<Pair<String, suspend () -> List<TrackResult>>>(
            "YouTube Mix" to { YouTubeRadioClient.fetchRadioTracks("$artists $title", limit = limit + 1) },
            "radio Spotify" to {
                kotlinx.coroutines.withTimeout(10_000) {
                    com.spotywoop.kt.data.SpotiwoopApi.artistRadio(artists.substringBefore(",").trim())
                }
            },
            "Audiomack" to {
                kotlinx.coroutines.withTimeout(10_000) { com.spotywoop.kt.data.SpotiwoopApi.radio(seedId, artists, title, durationMs) }
            },
        )
        for ((name, fetch) in sources) {
            val found = runCatching { fetch() }
                .onFailure { android.util.Log.w("PlayerViewModel", "Suggestions $name indisponibles : ${it.message}") }
                .getOrDefault(emptyList())
                .withoutSeed()
            if (found.isNotEmpty()) {
                android.util.Log.i("PlayerViewModel", "Suggestions : ${found.size} titres via $name pour '$artists - $title'")
                return found.take(limit)
            }
        }
        return emptyList()
    }

    private fun NowPlaying.toTrackResult() = TrackResult(
        id = id, name = title, artists = artist, album = album, durationMs = durationMs, cover = cover, isExplicit = explicit,
    )

    private fun triggerAutoRadioRolling(c: MediaController) {
        isPrefetchingAutoplay = true
        viewModelScope.launch {
            try {
                val queue = _state.value.radioQueue
                val lastTrack = queue.lastOrNull()
                val seed = lastTrack ?: _state.value.current?.toTrackResult() ?: return@launch
                val candidates = suggestions(seed.id, seed.artists, seed.name, seed.durationMs, 15)
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
                        preloadNextTrack(this)
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
     * Radio explicite (25 titres) arrivée en fin de file : on la prolonge.
     * Jamais appelé pour une playlist / un album / les téléchargements : la lecture s'arrête.
     */
    private fun triggerAutoplay(c: MediaController, current: NowPlaying) {
        isPrefetchingAutoplay = true
        viewModelScope.launch {
            try {
                val candidates = suggestions(current.id, current.artist, current.title, current.durationMs, 10)
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
                durationSec = (track.durationMs + 500) / 1000,
            )
            if (_state.value.current?.id == track.id) {
                _state.update { it.copy(lyrics = res, loadingLyrics = false) }
            }
        }
    }

    override fun onCleared() {
        runCatching {
            getApplication<Application>().getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(networkCallback)
        }
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
                    .setExtras(Bundle().apply {
                        putBoolean(EXTRA_EXPLICIT, isExplicit)
                        if (durationMs > 0) putLong("durationMs", durationMs)
                    })
                    .build(),
            )
            .build()
    }
}
