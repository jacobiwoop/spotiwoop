package com.spotywoop.kt

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotywoop.kt.data.DownloadManager
import com.spotywoop.kt.data.LocalLibraryStore
import com.spotywoop.kt.data.TrackResult
import com.spotywoop.kt.playback.StreamResolver
import com.spotywoop.kt.ui.AlbumScreen
import com.spotywoop.kt.ui.ArtistScreen
import com.spotywoop.kt.ui.DownloadsScreen
import com.spotywoop.kt.ui.HomeScreen
import com.spotywoop.kt.ui.LibraryScreen
import com.spotywoop.kt.ui.MiniPlayer
import com.spotywoop.kt.ui.NowPlayingScreen
import com.spotywoop.kt.ui.PlayerViewModel
import com.spotywoop.kt.ui.SearchScreen
import com.spotywoop.kt.ui.SearchViewModel
import com.spotywoop.kt.ui.SettingsScreen
import com.spotywoop.kt.ui.TrackOptionsSheet
import com.spotywoop.kt.ui.theme.SpotyColors
import com.spotywoop.kt.ui.theme.SpotywoopTheme

enum class MainTab(val label: String, val icon: ImageVector) {
    Home("Accueil", Icons.Rounded.Home),
    Search("Recherche", Icons.Rounded.Search),
    Library("Bibliothèque", Icons.AutoMirrored.Rounded.QueueMusic),
    Downloads("Téléchargements", Icons.Rounded.Download),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        StreamResolver.init(applicationContext)
        LocalLibraryStore.init(applicationContext)
        DownloadManager.init(applicationContext)
        com.spotywoop.kt.data.AppUpdateManager.init(applicationContext)
        com.spotywoop.kt.data.AppUpdateManager.checkForUpdates(silent = true)
        setContent {
            SpotywoopTheme {
                App()
            }
        }
    }
}

@Composable
private fun App(
    player: PlayerViewModel = viewModel(),
    searchVm: SearchViewModel = viewModel(),
) {
    val state by player.state.collectAsStateWithLifecycle()
    val updateStatus by com.spotywoop.kt.data.AppUpdateManager.status.collectAsStateWithLifecycle()
    val promptDismissed by com.spotywoop.kt.data.AppUpdateManager.promptDismissed.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var currentTab by rememberSaveable { mutableStateOf(MainTab.Home) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // Navigation sous-écrans
    var openedAlbum by remember { mutableStateOf<Triple<String, String, String?>?>(null) }
    var openedArtist by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var optionsTrack by remember { mutableStateOf<TrackResult?>(null) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Dialogue d'information Mise à jour au lancement (non-bloquant, masquable)
    if (!promptDismissed) {
        when (val s = updateStatus) {
            is com.spotywoop.kt.data.UpdateStatus.Available -> {
                AlertDialog(
                    onDismissRequest = { com.spotywoop.kt.data.AppUpdateManager.dismissPrompt() },
                    title = {
                        Text(
                            text = if (s.previousWasObsolete) "Nouvelle version disponible" else "Mise à jour disponible",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (s.previousWasObsolete) {
                                    "La version téléchargée précédemment était obsolète. Une toute nouvelle version (${s.info.tagName}) est disponible !"
                                } else {
                                    "Une nouvelle version de Spotywoop (${s.info.tagName}) est disponible.\nSouhaitez-vous la télécharger ?"
                                },
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 14.sp,
                            )
                            if (s.info.changelog.isNotBlank()) {
                                Text(
                                    text = s.info.changelog.take(200),
                                    color = SpotyColors.TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                com.spotywoop.kt.data.AppUpdateManager.downloadAndInstall(context, s.info)
                                com.spotywoop.kt.data.AppUpdateManager.dismissPrompt()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SpotyColors.SpotifyGreen),
                        ) {
                            Text("Télécharger", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { com.spotywoop.kt.data.AppUpdateManager.dismissPrompt() },
                        ) {
                            Text("Plus tard", color = Color.White.copy(alpha = 0.7f))
                        }
                    },
                    containerColor = SpotyColors.SurfaceHigh,
                    shape = RoundedCornerShape(16.dp),
                )
            }
            is com.spotywoop.kt.data.UpdateStatus.ReadyToInstall -> {
                AlertDialog(
                    onDismissRequest = { com.spotywoop.kt.data.AppUpdateManager.dismissPrompt() },
                    title = {
                        Text(
                            text = "Mise à jour prête à installer",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    text = {
                        Text(
                            text = "La mise à jour ${s.info?.tagName ?: ""} est déjà téléchargée (dernière version). Souhaitez-vous lancer l'installation maintenant ?",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 14.sp,
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                com.spotywoop.kt.data.AppUpdateManager.launchInstaller(context, s.apkFile)
                                com.spotywoop.kt.data.AppUpdateManager.dismissPrompt()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SpotyColors.SpotifyGreen),
                        ) {
                            Text("Installer maintenant", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { com.spotywoop.kt.data.AppUpdateManager.dismissPrompt() },
                        ) {
                            Text("Plus tard", color = Color.White.copy(alpha = 0.7f))
                        }
                    },
                    containerColor = SpotyColors.SurfaceHigh,
                    shape = RoundedCornerShape(16.dp),
                )
            }
            else -> {}
        }
    }

    Box(Modifier.fillMaxSize().background(SpotyColors.Background)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Écran actif
                when {
                    openedAlbum != null -> {
                        val (name, artist, cover) = openedAlbum!!
                        AlbumScreen(
                            albumName = name,
                            artistName = artist,
                            coverUrl = cover,
                            onBack = { openedAlbum = null },
                            onPlayTracks = { tracks, index ->
                                player.playQueue(tracks, index)
                                showPlayer = true
                            },
                            onTrackOptions = { optionsTrack = it },
                        )
                    }
                    openedArtist != null -> {
                        val (name, cover) = openedArtist!!
                        ArtistScreen(
                            artistName = name,
                            artistCover = cover,
                            onBack = { openedArtist = null },
                            onPlayTracks = { tracks, index ->
                                player.playQueue(tracks, index)
                                showPlayer = true
                            },
                            onTrackOptions = { optionsTrack = it },
                            onOpenAlbum = { albName, artName, albCover ->
                                openedAlbum = Triple(albName, artName, albCover)
                            }
                        )
                    }
                    else -> {
                        when (currentTab) {
                            MainTab.Home -> {
                                HomeScreen(
                                    onPlayTrack = { track ->
                                        player.playTrackWithAutoRadio(track)
                                        showPlayer = true
                                    },
                                    onTrackOptions = { optionsTrack = it },
                                    onOpenDownloads = { currentTab = MainTab.Downloads },
                                    onOpenLibrary = { currentTab = MainTab.Library },
                                    onOpenSettings = { showSettings = true },
                                    onSearchCategory = { category ->
                                        searchVm.onQueryChange(category)
                                        searchVm.search()
                                        currentTab = MainTab.Search
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            MainTab.Search -> {
                                SearchScreen(
                                    onPlayTrack = { track ->
                                        player.playTrackWithAutoRadio(track)
                                        showPlayer = true
                                    },
                                    onTrackOptions = { optionsTrack = it },
                                    onOpenAlbum = { albName, artName, albCover ->
                                        openedAlbum = Triple(albName, artName, albCover)
                                    },
                                    onOpenArtist = { artName, artCover ->
                                        openedArtist = Pair(artName, artCover)
                                    },
                                    onOpenSettings = { showSettings = true },
                                    modifier = Modifier.fillMaxSize(),
                                    vm = searchVm,
                                )
                            }
                            MainTab.Library -> {
                                LibraryScreen(
                                    onPlayTracks = { tracks, index ->
                                        player.playQueue(tracks, index)
                                        showPlayer = true
                                    },
                                    onTrackOptions = { optionsTrack = it },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            MainTab.Downloads -> {
                                DownloadsScreen(
                                    onPlayTracks = { tracks, index ->
                                        player.playQueue(tracks, index)
                                        showPlayer = true
                                    },
                                    onTrackOptions = { optionsTrack = it },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }

            // MiniPlayer au-dessus de la barre de navigation
            MiniPlayer(
                state = state,
                onOpen = { showPlayer = true },
                onTogglePlay = player::togglePlay,
                onNext = player::next,
            )

            // Barre de navigation inférieure façon Spotify
            BottomNav(
                currentTab = currentTab,
                onTabSelect = {
                    openedAlbum = null
                    openedArtist = null
                    currentTab = it
                }
            )
        }

        // Lecteur plein écran
        AnimatedVisibility(
            visible = showPlayer && state.current != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen(
                state = state,
                onClose = { showPlayer = false },
                onTogglePlay = player::togglePlay,
                onNext = player::next,
                onPrevious = player::previous,
                onSeek = player::seekTo,
                onCyclePlaybackMode = player::cyclePlaybackMode,
                onToggleLyrics = player::toggleLyrics,
                onStartRadio = player::startRadioForCurrentTrack,
                onPlayTrackFromQueue = player::playTrackFromQueue,
                onOpenOptions = { optionsTrack = it },
            )
        }

        // Écran Paramètres
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            SettingsScreen(onClose = { showSettings = false })
        }

        // Modal d'options Trois Points ⋮
        if (optionsTrack != null) {
            TrackOptionsSheet(
                track = optionsTrack!!,
                onDismiss = { optionsTrack = null },
                onStartRadio = { track ->
                    player.playTrackWithAutoRadio(track)
                    showPlayer = true
                },
                onNavigateToArtist = { artistName ->
                    openedArtist = Pair(artistName, null)
                },
                onNavigateToAlbum = { albumName, artistName ->
                    openedAlbum = Triple(albumName, artistName, null)
                }
            )
        }
    }

    // Gestion de la touche retour Android
    BackHandler(enabled = optionsTrack != null) { optionsTrack = null }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showPlayer && !showSettings) { showPlayer = false }
    BackHandler(enabled = openedAlbum != null) { openedAlbum = null }
    BackHandler(enabled = openedArtist != null) { openedArtist = null }
    BackHandler(enabled = currentTab != MainTab.Home && !showPlayer && !showSettings && openedAlbum == null && openedArtist == null) {
        currentTab = MainTab.Home
    }
}

@Composable
private fun BottomNav(
    currentTab: MainTab,
    onTabSelect: (MainTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color(0xFF121212)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        MainTab.entries.forEach { tab ->
            val active = tab == currentTab
            val color = if (active) Color.White else Color.White.copy(alpha = 0.5f)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTabSelect(tab) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = color,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = tab.label,
                    color = color,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
