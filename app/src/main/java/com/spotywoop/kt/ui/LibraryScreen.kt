package com.spotywoop.kt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.spotywoop.kt.data.LocalLibraryStore
import com.spotywoop.kt.data.TrackResult
import com.spotywoop.kt.data.UserPlaylist
import com.spotywoop.kt.ui.theme.SpotyColors

/**
 * Écran Bibliothèque façon Spotify :
 * - Carte "Titres likés" (dégradé violet/indigo)
 * - Playlists de l'utilisateur avec création, suppression et gestion des morceaux
 */
@Composable
fun LibraryScreen(
    onPlayTracks: (List<TrackResult>, Int) -> Unit,
    onTrackOptions: (TrackResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val likedTracks by LocalLibraryStore.likedTracks.collectAsStateWithLifecycle()
    val playlists by LocalLibraryStore.playlists.collectAsStateWithLifecycle()

    var selectedView by remember { mutableStateOf<LibraryView>(LibraryView.Root) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    when (val view = selectedView) {
        is LibraryView.Root -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .background(SpotyColors.Background)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp),
            ) {
                // En-tête
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Votre Bibliothèque",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = "Créer une playlist",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 100.dp),
                ) {
                    // Carte "Titres likés" façon Spotify
                    item {
                        LikedSongsCard(
                            count = likedTracks.size,
                            onClick = { selectedView = LibraryView.LikedSongs },
                        )
                    }

                    // Liste des playlists personnalisées
                    items(playlists, key = { it.id }) { pl ->
                        PlaylistRow(
                            playlist = pl,
                            onClick = { selectedView = LibraryView.PlaylistDetail(pl.id) },
                        )
                    }
                }
            }
        }
        is LibraryView.LikedSongs -> {
            PlaylistDetailView(
                title = "Titres likés",
                subtitle = "${likedTracks.size} titres",
                gradientColors = listOf(Color(0xFF450AF5), Color(0xFF8E8EE5)),
                tracks = likedTracks,
                onBack = { selectedView = LibraryView.Root },
                onPlay = { index -> onPlayTracks(likedTracks, index) },
                onTrackOptions = onTrackOptions,
                modifier = modifier,
            )
        }
        is LibraryView.PlaylistDetail -> {
            val pl = playlists.find { it.id == view.playlistId }
            if (pl != null) {
                PlaylistDetailView(
                    title = pl.name,
                    subtitle = "Playlist • ${pl.tracks.size} titres",
                    cover = pl.cover,
                    tracks = pl.tracks,
                    onBack = { selectedView = LibraryView.Root },
                    onPlay = { index -> onPlayTracks(pl.tracks, index) },
                    onTrackOptions = onTrackOptions,
                    onDelete = {
                        LocalLibraryStore.deletePlaylist(pl.id)
                        selectedView = LibraryView.Root
                    },
                    modifier = modifier,
                )
            } else {
                selectedView = LibraryView.Root
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = Color(0xFF282828),
            title = { Text("Nouvelle playlist", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text("Donnez un titre", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = SpotyColors.SpotifyGreen,
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            LocalLibraryStore.createPlaylist(newPlaylistName)
                            newPlaylistName = ""
                            showCreateDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SpotyColors.SpotifyGreen)
                ) {
                    Text("Créer", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Annuler", color = Color.White)
                }
            }
        )
    }
}

private sealed interface LibraryView {
    data object Root : LibraryView
    data object LikedSongs : LibraryView
    data class PlaylistDetail(val playlistId: String) : LibraryView
}

@Composable
private fun LikedSongsCard(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF450AF5), Color(0xFFC4EFD9))
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Titres likés",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Playlist • $count titres",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun PlaylistRow(playlist: UserPlaylist, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(SpotyColors.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = SpotyColors.TextMuted)
            if (playlist.cover != null) {
                AsyncImage(
                    model = playlist.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Playlist • ${playlist.tracks.size} titres",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
fun PlaylistDetailView(
    title: String,
    subtitle: String,
    tracks: List<TrackResult>,
    gradientColors: List<Color>? = null,
    cover: String? = null,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onTrackOptions: (TrackResult) -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SpotyColors.Background)
            .statusBarsPadding(),
    ) {
        // Barre de retour
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Supprimer", tint = Color.White)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    if (gradientColors != null) {
                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Brush.linearGradient(gradientColors)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(60.dp))
                        }
                    } else if (cover != null) {
                        AsyncImage(
                            model = cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(140.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(text = title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(text = subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)

                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(SpotyColors.SpotifyGreen)
                                .clickable(enabled = tracks.isNotEmpty()) { onPlay(0) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = "Lire",
                                tint = Color.Black,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            if (tracks.isEmpty()) {
                item {
                    Text(
                        text = "Aucun titre dans cette liste.",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                itemsIndexed(tracks, key = { index, t -> "${t.id}_$index" }) { index, track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlay(index) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(SpotyColors.SurfaceHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = SpotyColors.TextMuted)
                            if (!track.cover.isNullOrBlank()) {
                                AsyncImage(
                                    model = track.cover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.matchParentSize(),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.name,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = track.artists,
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onTrackOptions(track) }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Options", tint = Color.White.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
    }
}
