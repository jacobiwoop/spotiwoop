package com.spotywoop.kt.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotywoop.kt.data.DownloadManager
import com.spotywoop.kt.data.LocalLibraryStore
import com.spotywoop.kt.data.TrackResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.spotywoop.kt.ui.theme.ColorExtractor
import com.spotywoop.kt.ui.theme.SpotyColors
import kotlinx.coroutines.launch

@Composable
fun MiniPlayer(state: PlayerUiState, onOpen: () -> Unit, onTogglePlay: () -> Unit, onNext: () -> Unit) {
    val now = state.current ?: return
    val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SpotyColors.SurfaceHigh)
            .border(1.dp, SpotyColors.Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(now.cover, 44.dp, 6.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(now.title, color = SpotyColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    state.error ?: now.artist,
                    color = if (state.error != null) SpotyColors.Explicit else SpotyColors.TextSecondary,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            PlayPauseIcon(state, size = 40.dp, iconSize = 24.dp, filled = false, onClick = onTogglePlay)
            IconButton(Icons.Rounded.SkipNext, enabled = state.hasNext, size = 40.dp, onClick = onNext)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(SpotyColors.Border)) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(2.dp).background(SpotyColors.Gold))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    state: PlayerUiState,
    onClose: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onCyclePlaybackMode: () -> Unit = {},
    onToggleLyrics: () -> Unit = {},
    onStartRadio: () -> Unit = {},
    onPlayTrackFromQueue: (TrackResult) -> Unit = {},
    onOpenOptions: ((TrackResult) -> Unit)? = null,
) {
    val now = state.current ?: return
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // 1. Extraction dynamique de la couleur de la pochette avec transition douce
    var dominantColor by remember { mutableStateOf(ColorExtractor.DefaultDominant) }
    LaunchedEffect(now.cover) {
        dominantColor = ColorExtractor.extractDominantColor(context, now.cover)
    }
    val animatedBgColor by animateColorAsState(
        targetValue = dominantColor,
        animationSpec = tween(durationMillis = 600),
        label = "dominantBgColor",
    )

    // Curseur de recherche et temps
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val shownPosition = dragging?.let { (it * duration).toLong() } ?: state.positionMs

    // États locaux d'interface
    val likedTracks by LocalLibraryStore.likedTracks.collectAsStateWithLifecycle()
    val isFavorite = likedTracks.any { it.id == now.id }
    var fullScreenLyricsOpen by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }

    // 2. Détection de la ligne de parole actuelle (Live Lyric Ticker)
    val activeIndex by remember(state.lyrics, state.positionMs) {
        derivedStateOf {
            val l = state.lyrics ?: return@derivedStateOf -1
            if (!l.isSynced || l.lines.isEmpty()) -1
            else l.lines.indexOfLast { it.timeMs <= state.positionMs }
        }
    }
    val currentLyricText = remember(activeIndex, state.lyrics) {
        val lines = state.lyrics?.lines
        if (lines != null && activeIndex in lines.indices) {
            lines[activeIndex].text.takeIf { it.isNotBlank() }
        } else null
    }

    val isScrolled by remember { derivedStateOf { scrollState.value > 120 } }
    val stickyBarColor by animateColorAsState(
        targetValue = if (isScrolled) animatedBgColor.copy(alpha = 0.96f) else Color.Transparent,
        animationSpec = tween(250),
        label = "stickyBarColor",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SpotyColors.Background) // Fond 100% opaque pour bloquer la vue arrière
    ) {
        // --- COUCHE 1 : ARRIÈRE-PLAN IMMERSIF COVER BLUR (STYLE EXPO) ---
        if (!now.cover.isNullOrBlank()) {
            AsyncImage(
                model = now.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.35f)
                    .blur(radius = 50.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    .alpha(0.60f),
            )
        }

        // --- COUCHE 2 : DÉGRADÉ VIBRANT VERTICAL (STYLE SPOTIFY STUDIO) ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to animatedBgColor.copy(alpha = 0.55f),
                            0.45f to Color(0xFF121212).copy(alpha = 0.70f),
                            0.80f to Color(0xFF121212).copy(alpha = 0.95f),
                            1.0f to Color(0xFF121212),
                        )
                    )
                )
        )

        // --- CONTENU PRINCIPAL DÉFILABLE ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 56.dp) // Espace pour la barre sticky du haut
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))

            // --- HERO COVER ART ---
            Cover(
                url = now.cover,
                size = null,
                corner = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp)),
            )

            // --- LIGNE DE PAROLE EN DIRECT (TICKER AVEC ANIMATION SWIPE / SLIDE-FADE) ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clickable {
                        coroutineScope.launch {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                AnimatedContent(
                    targetState = currentLyricText,
                    transitionSpec = {
                        (slideInVertically(animationSpec = tween(350)) { height -> height } + fadeIn(animationSpec = tween(350)))
                            .togetherWith(
                                slideOutVertically(animationSpec = tween(350)) { height -> -height } + fadeOut(animationSpec = tween(350))
                            )
                    },
                    label = "lyric_ticker_swipe",
                ) { text ->
                    if (!text.isNullOrBlank()) {
                        Text(
                            text = text,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Spacer(Modifier.fillMaxWidth())
                    }
                }
            }

            // --- TRACK INFO & ACTION ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = now.title,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (now.explicit) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "E",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(SpotyColors.Explicit, RoundedCornerShape(2.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = now.artist,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    icon = if (isFavorite) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                    tint = if (isFavorite) SpotyColors.SpotifyGreen else Color.White,
                    size = 48.dp,
                    iconSize = 30.dp,
                    onClick = {
                        val tr = TrackResult(
                            id = now.id,
                            name = now.title,
                            artists = now.artist,
                            album = now.album,
                            durationMs = state.durationMs,
                            cover = now.cover,
                            isExplicit = now.explicit,
                        )
                        LocalLibraryStore.toggleLike(tr)
                    },
                )
            }

            Spacer(Modifier.height(8.dp))

            // --- SEEK BAR (SLIDER SPOTIFY) ---
            Slider(
                value = dragging ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onSeek((it * duration).toLong()) }
                    dragging = null
                },
                enabled = state.durationMs > 0,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            ) {
                Text(
                    formatTime(shownPosition),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (state.durationMs > 0) formatTime(state.durationMs) else "--:--",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(12.dp))

            // --- 5 COMMANDES PRINCIPALES SPOTIFY ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlaybackModeButton(
                    mode = state.playbackMode,
                    onClick = onCyclePlaybackMode,
                )
                IconButton(
                    icon = Icons.Rounded.SkipPrevious,
                    tint = Color.White,
                    size = 52.dp,
                    iconSize = 36.dp,
                    onClick = onPrevious,
                )
                PlayPauseIcon(
                    state = state,
                    size = 66.dp,
                    iconSize = 36.dp,
                    filled = true,
                    onClick = onTogglePlay,
                )
                IconButton(
                    icon = Icons.Rounded.SkipNext,
                    enabled = state.hasNext || state.radioQueue.size > 1,
                    tint = Color.White,
                    size = 52.dp,
                    iconSize = 36.dp,
                    onClick = onNext,
                )
                PlayerDownloadButton(
                    track = now,
                    durationMs = state.durationMs,
                    dominantColor = dominantColor,
                )
            }

            Spacer(Modifier.height(14.dp))

            // --- LIGNE DISPOSITIF & UTILITAIRES ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Devices,
                    contentDescription = null,
                    tint = SpotyColors.SpotifyGreen,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.stream?.let { "${it.source} (${it.quality})" } ?: "Connexion filaire",
                    color = SpotyColors.SpotifyGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    icon = Icons.Rounded.Share,
                    tint = Color.White.copy(alpha = 0.7f),
                    size = 36.dp,
                    iconSize = 20.dp,
                    onClick = { /* Partager */ },
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    tint = if (state.isRadioActive) SpotyColors.SpotifyGreen else Color.White.copy(alpha = 0.7f),
                    size = 44.dp,
                    iconSize = 24.dp,
                    onClick = { showQueueSheet = true },
                )
            }

            state.error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = SpotyColors.Explicit, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 3)
            }

            Spacer(Modifier.height(24.dp))

            // --- CARTE D'APERÇU DES PAROLES (DÉFILABLE) ---
            LyricsCard(
                lyrics = state.lyrics,
                loading = state.loadingLyrics,
                currentPositionMs = state.positionMs,
                dominantColor = dominantColor,
                onSeek = onSeek,
                onOpenFullScreen = { fullScreenLyricsOpen = true },
            )

            Spacer(Modifier.height(20.dp))

            // --- CARTE À PROPOS DE L'ARTISTE (STYLE SPOTIFY) ---
            ArtistCard(now = now)

            Spacer(Modifier.height(40.dp))
        }

        // --- STICKY TOP BAR SPOTIFY (EN HAUT DU BOX) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(stickyBarColor)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            AnimatedContent(
                targetState = isScrolled,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "stickyTopBarContent",
            ) { scrolled ->
                if (!scrolled) {
                    // Barre complète
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            icon = Icons.Rounded.KeyboardArrowDown,
                            size = 40.dp,
                            iconSize = 28.dp,
                            onClick = onClose,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = if (state.isRadioActive) "LECTURE À PARTIR DE LA RADIO" else "LECTURE À PARTIR DE L'ALBUM",
                                color = if (state.isRadioActive) SpotyColors.SpotifyGreen else Color.White.copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                maxLines = 1,
                            )
                            Text(
                                text = if (state.isRadioActive && !state.radioSeedTitle.isNullOrBlank()) {
                                    state.radioSeedTitle
                                } else if (now.album.isNotBlank()) now.album else "Morceau",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box {
                            IconButton(
                                icon = Icons.Rounded.MoreVert,
                                size = 40.dp,
                                iconSize = 24.dp,
                                onClick = { showOptionsMenu = true },
                            )
                            DropdownMenu(
                                expanded = showOptionsMenu,
                                onDismissRequest = { showOptionsMenu = false },
                                modifier = Modifier.background(Color(0xFF242424)),
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Accéder à la radio liée au titre", color = Color.White, fontSize = 14.sp) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.Radio,
                                            contentDescription = null,
                                            tint = SpotyColors.SpotifyGreen,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        showOptionsMenu = false
                                        onStartRadio()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Voir la file d'attente / Radio", color = Color.White, fontSize = 14.sp) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.QueueMusic,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        showOptionsMenu = false
                                        showQueueSheet = true
                                    }
                                )
                                if (onOpenOptions != null) {
                                    DropdownMenuItem(
                                        text = { Text("Options du titre (Playlists, Télécharger...)", color = Color.White, fontSize = 14.sp) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Rounded.MoreVert,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        },
                                        onClick = {
                                            showOptionsMenu = false
                                            onOpenOptions(
                                                TrackResult(
                                                    id = now.id,
                                                    name = now.title,
                                                    artists = now.artist,
                                                    album = now.album,
                                                    durationMs = state.durationMs,
                                                    cover = now.cover,
                                                    isExplicit = now.explicit,
                                                )
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Mini-barre compacte sticky (comme Spotify quand on scrolle sur les paroles)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            icon = Icons.Rounded.KeyboardArrowDown,
                            size = 40.dp,
                            iconSize = 28.dp,
                            onClick = onClose,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${now.title} • ${now.artist}",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = state.stream?.let { "${it.source} (${it.quality})" } ?: "Connexion filaire",
                                color = SpotyColors.SpotifyGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                        IconButton(
                            icon = if (isFavorite) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                            tint = if (isFavorite) SpotyColors.SpotifyGreen else Color.White,
                            size = 38.dp,
                            iconSize = 24.dp,
                            onClick = {
                                val tr = TrackResult(
                                    id = now.id,
                                    name = now.title,
                                    artists = now.artist,
                                    album = now.album,
                                    durationMs = state.durationMs,
                                    cover = now.cover,
                                    isExplicit = now.explicit,
                                )
                                LocalLibraryStore.toggleLike(tr)
                            },
                        )
                        PlayPauseIcon(
                            state = state,
                            size = 38.dp,
                            iconSize = 20.dp,
                            filled = false,
                            onClick = onTogglePlay,
                        )
                    }
                }
            }
        }

        // --- OVERLAY PLEIN ÉCRAN DES PAROLES ---
        if (fullScreenLyricsOpen) {
            FullScreenLyricsDialog(
                lyrics = state.lyrics,
                currentPositionMs = state.positionMs,
                dominantColor = dominantColor,
                onClose = { fullScreenLyricsOpen = false },
                onSeek = onSeek,
            )
        }

        // --- OVERLAY FILE D'ATTENTE / RADIO ---
        if (showQueueSheet) {
            RadioQueueSheet(
                state = state,
                onClose = { showQueueSheet = false },
                onPlayTrack = { track ->
                    onPlayTrackFromQueue(track)
                    showQueueSheet = false
                },
                onStartRadio = onStartRadio,
            )
        }
    }
}

@Composable
private fun ArtistCard(now: NowPlaying) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1E1E1E))
            .padding(20.dp),
    ) {
        Column {
            Text(
                text = "À propos de l'artiste",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(now.cover, 54.dp, 27.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = now.artist,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Artiste vérifié",
                        color = SpotyColors.TextSecondary,
                        fontSize = 13.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        "S'abonner",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayPauseIcon(state: PlayerUiState, size: Dp, iconSize: Dp, filled: Boolean, onClick: () -> Unit) {
    val bg = if (filled) Color.White else Color.Transparent
    val tint = if (filled) Color.Black else Color.White
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (state.buffering && !state.isPlaying) {
            CircularProgressIndicator(color = tint, strokeWidth = 2.5.dp, modifier = Modifier.size(iconSize * 0.7f))
        } else {
            Icon(
                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Lecture",
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun IconButton(
    icon: ImageVector,
    enabled: Boolean = true,
    tint: Color = Color.White,
    size: Dp,
    iconSize: Dp = size * 0.6f,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun Cover(url: String?, size: Dp?, corner: Dp, modifier: Modifier = Modifier) {
    val base = if (size != null) modifier.size(size) else modifier
    Box(
        base.clip(RoundedCornerShape(corner)).background(SpotyColors.Surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = SpotyColors.TextMuted)
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun PlaybackModeButton(
    mode: PlaybackMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, tint, showDot) = when (mode) {
        PlaybackMode.NORMAL -> Triple(Icons.Rounded.Shuffle, Color.White.copy(alpha = 0.5f), false)
        PlaybackMode.SHUFFLE -> Triple(Icons.Rounded.Shuffle, SpotyColors.SpotifyGreen, true)
        PlaybackMode.REPEAT_ALL -> Triple(Icons.Rounded.Repeat, SpotyColors.SpotifyGreen, true)
        PlaybackMode.REPEAT_ONE -> Triple(Icons.Rounded.RepeatOne, SpotyColors.SpotifyGreen, true)
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon,
                contentDescription = "Mode de lecture: $mode",
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            if (showDot) {
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(SpotyColors.SpotifyGreen)
                )
            } else {
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun PlayerDownloadButton(
    track: NowPlaying,
    durationMs: Long,
    dominantColor: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val downloads by DownloadManager.downloads.collectAsStateWithLifecycle()
    val activeDownloads by DownloadManager.activeDownloads.collectAsStateWithLifecycle()

    val isDownloaded = remember(downloads, track.id) { downloads.any { it.track.id == track.id } }
    val activeDownload = activeDownloads[track.id]
    val isDownloading = activeDownload != null

    // Animation de balayage vert rapide sur l'icône quand téléchargement en cours
    val infiniteTransition = rememberInfiniteTransition(label = "download_shimmer")
    val shimmerTranslate by infiniteTransition.animateFloat(
        initialValue = -80f,
        targetValue = 120f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslate",
    )

    // Couleur prédominante dynamique (sécurisée pour être bien visible)
    val downloadDoneTint = if (dominantColor == ColorExtractor.DefaultDominant || dominantColor == Color.Black) {
        SpotyColors.SpotifyGreen
    } else {
        dominantColor
    }

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable {
                when {
                    isDownloaded -> {
                        Toast.makeText(context, "Ce titre est déjà disponible hors-ligne", Toast.LENGTH_SHORT).show()
                    }
                    isDownloading -> {
                        val pct = (activeDownload.progress * 100).toInt()
                        Toast.makeText(context, "Téléchargement en cours ($pct%)", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        val tr = TrackResult(
                            id = track.id,
                            name = track.title,
                            artists = track.artist,
                            album = track.album,
                            durationMs = durationMs,
                            cover = track.cover,
                            isExplicit = track.explicit,
                        )
                        DownloadManager.downloadTrack(tr)
                        Toast.makeText(context, "Téléchargement lancé...", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                isDownloaded -> {
                    Icon(
                        Icons.Rounded.DownloadDone,
                        contentDescription = "Déjà téléchargé",
                        tint = downloadDoneTint,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                isDownloading -> {
                    val sweepBrush = Brush.linearGradient(
                        colors = listOf(
                            SpotyColors.SpotifyGreen.copy(alpha = 0.35f),
                            Color.White,
                            SpotyColors.SpotifyGreen,
                            SpotyColors.SpotifyGreen.copy(alpha = 0.35f),
                        ),
                        start = Offset(shimmerTranslate, 0f),
                        end = Offset(shimmerTranslate + 50f, 50f),
                    )
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = "Téléchargement en cours",
                        modifier = Modifier
                            .size(22.dp)
                            .drawWithCache {
                                onDrawWithContent {
                                    drawContent()
                                    drawRect(sweepBrush, blendMode = BlendMode.SrcAtop)
                                }
                            },
                        tint = SpotyColors.SpotifyGreen,
                    )
                    val pct = (activeDownload.progress * 100).toInt()
                    Text(
                        text = if (pct > 0) "$pct%" else "0%",
                        color = SpotyColors.SpotifyGreen,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                else -> {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = "Télécharger le morceau",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

