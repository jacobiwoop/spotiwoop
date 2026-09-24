package com.spotywoop.kt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.spotywoop.kt.data.DownloadManager
import com.spotywoop.kt.data.LocalLibraryStore
import com.spotywoop.kt.data.TrackResult
import com.spotywoop.kt.ui.theme.SpotyColors

/**
 * Bottom Sheet modal d'options pour un titre (Menu Trois Points ⋮),
 * calqué sur l'expérience Spotify / YouTube Music.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackOptionsSheet(
    track: TrackResult,
    onDismiss: () -> Unit,
    onStartRadio: ((TrackResult) -> Unit)? = null,
    onNavigateToArtist: ((artistName: String) -> Unit)? = null,
    onNavigateToAlbum: ((albumName: String, artistName: String) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val likedTracks by LocalLibraryStore.likedTracks.collectAsStateWithLifecycle()
    val isLiked = likedTracks.any { it.id == track.id }

    val downloads by DownloadManager.downloads.collectAsStateWithLifecycle()
    val isDownloaded = downloads.any { it.track.id == track.id }
    val downloadingIds by DownloadManager.downloadingIds.collectAsStateWithLifecycle()
    val isDownloading = downloadingIds.contains(track.id)

    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF202020),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // En-tête du titre
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(6.dp))
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
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
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
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.1f))
            )
            Spacer(Modifier.height(8.dp))

            // Option 1 : Liker / Favoris
            OptionRow(
                icon = if (isLiked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                tint = if (isLiked) SpotyColors.SpotifyGreen else Color.White,
                title = if (isLiked) "Titre ajouté aux Titres likés" else "Aimer ce titre",
                onClick = {
                    LocalLibraryStore.toggleLike(track)
                }
            )

            // Option 2 : Ajouter à une playlist
            OptionRow(
                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                title = "Ajouter à une playlist",
                onClick = {
                    showPlaylistDialog = true
                }
            )

            // Option 3 : Télécharger
            OptionRow(
                icon = when {
                    isDownloaded -> Icons.Rounded.CheckCircle
                    isDownloading -> Icons.Outlined.Download
                    else -> Icons.Outlined.Download
                },
                tint = if (isDownloaded) SpotyColors.SpotifyGreen else Color.White,
                title = when {
                    isDownloaded -> "Téléchargé (Appuyer pour supprimer)"
                    isDownloading -> "Téléchargement en cours..."
                    else -> "Télécharger pour écoute hors-ligne"
                },
                onClick = {
                    if (isDownloaded) {
                        DownloadManager.deleteDownload(track.id)
                    } else {
                        DownloadManager.downloadTrack(track)
                    }
                }
            )

            // Option 4 : Lancer la radio
            if (onStartRadio != null) {
                OptionRow(
                    icon = Icons.Outlined.Radio,
                    title = "Lancer la radio liée au titre",
                    onClick = {
                        onDismiss()
                        onStartRadio(track)
                    }
                )
            }

            // Option 5 : Accéder à l'artiste
            if (onNavigateToArtist != null && track.artists.isNotBlank()) {
                val primaryArtist = track.artists.split(",")[0].trim()
                OptionRow(
                    icon = Icons.Outlined.Person,
                    title = "Accéder à l'artiste ($primaryArtist)",
                    onClick = {
                        onDismiss()
                        onNavigateToArtist(primaryArtist)
                    }
                )
            }

            // Option 6 : Accéder à l'album
            if (onNavigateToAlbum != null && track.album.isNotBlank()) {
                val primaryArtist = track.artists.split(",")[0].trim()
                OptionRow(
                    icon = Icons.Outlined.Album,
                    title = "Accéder à l'album (${track.album})",
                    onClick = {
                        onDismiss()
                        onNavigateToAlbum(track.album, primaryArtist)
                    }
                )
            }

            // Option 7 : Partager
            OptionRow(
                icon = Icons.Outlined.Share,
                title = "Partager",
                onClick = {
                    onDismiss()
                }
            )

            Spacer(Modifier.height(16.dp))
        }
    }

    // Dialogue de choix de playlist
    if (showPlaylistDialog) {
        val playlists by LocalLibraryStore.playlists.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { showPlaylistDialog = false },
            containerColor = Color(0xFF282828),
            title = { Text("Ajouter à une playlist", color = Color.White) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // Créer une nouvelle playlist
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showPlaylistDialog = false
                                showNewPlaylistDialog = true
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = SpotyColors.SpotifyGreen)
                        Spacer(Modifier.width(12.dp))
                        Text("Nouvelle playlist", color = SpotyColors.SpotifyGreen, fontWeight = FontWeight.Bold)
                    }

                    playlists.forEach { pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    LocalLibraryStore.addTrackToPlaylist(pl.id, track)
                                    showPlaylistDialog = false
                                    onDismiss()
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(pl.name, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text("${pl.tracks.size} titres", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlaylistDialog = false }) {
                    Text("Fermer", color = SpotyColors.SpotifyGreen)
                }
            }
        )
    }

    // Dialogue de création de playlist
    if (showNewPlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showNewPlaylistDialog = false },
            containerColor = Color(0xFF282828),
            title = { Text("Nom de la playlist", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text("Ma playlist", color = Color.Gray) },
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
                            val pl = LocalLibraryStore.createPlaylist(newPlaylistName)
                            LocalLibraryStore.addTrackToPlaylist(pl.id, track)
                            showNewPlaylistDialog = false
                            newPlaylistName = ""
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SpotyColors.SpotifyGreen)
                ) {
                    Text("Créer et ajouter", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewPlaylistDialog = false }) {
                    Text("Annuler", color = Color.White)
                }
            }
        )
    }
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    title: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
