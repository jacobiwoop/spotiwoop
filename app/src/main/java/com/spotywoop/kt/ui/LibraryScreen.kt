package com.spotywoop.kt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import com.spotywoop.kt.data.UserPlaylist
import com.spotywoop.kt.ui.theme.SpotyColors
import com.spotywoop.kt.ui.theme.SpotyFonts

/**
 * Bibliothèque : raccourcis Titres likés et Téléchargés, titres écoutés récemment, playlists de l'utilisateur.
 */
@Composable
fun LibraryScreen(
    onPlayTracks: (List<TrackResult>, Int) -> Unit,
    onOpenCollection: (UserCollectionNav) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenArtist: (ArtistNav) -> Unit,
    modifier: Modifier = Modifier,
) {
    val likedTracks by LocalLibraryStore.likedTracks.collectAsStateWithLifecycle()
    val playlists by LocalLibraryStore.playlists.collectAsStateWithLifecycle()
    val recent by LocalLibraryStore.recentlyPlayed.collectAsStateWithLifecycle()
    val followed by LocalLibraryStore.followedArtists.collectAsStateWithLifecycle()
    val downloads by DownloadManager.downloads.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(SpotyColors.Background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 100.dp),
    ) {
        item(key = "title") {
            Text(
                text = "Votre Bibliothèque",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            )
        }

        item(key = "shortcuts") {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Shortcut(
                    title = "Titres likés",
                    subtitle = titresLabel(likedTracks.size),
                    icon = Icons.Filled.Favorite,
                    tint = Color.White,
                    iconBackground = Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFFC4EFD9))),
                    onClick = { onOpenCollection(UserCollectionNav("liked")) },
                    modifier = Modifier.weight(1f),
                )
                Shortcut(
                    title = "Téléchargés",
                    subtitle = titresLabel(downloads.size),
                    icon = Icons.Rounded.Download,
                    tint = SpotyColors.Accent,
                    iconBackground = Brush.linearGradient(listOf(Color(0xFF16200C), Color(0xFF16200C))),
                    onClick = onOpenDownloads,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (followed.isNotEmpty()) {
            item(key = "followed") {
                SectionTitle("Artistes suivis", Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(followed, key = { it.name }) { artist ->
                        ArtistBubble(
                            name = artist.name,
                            image = artist.image,
                            onClick = { onOpenArtist(ArtistNav(artist.ref, artist.name, artist.image)) },
                        )
                    }
                }
            }
        }

        if (recent.isNotEmpty()) {
            item(key = "recent") {
                SectionTitle("Écoutés récemment", Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(recent, key = { _, t -> t.id }) { i, t ->
                        Column(Modifier.width(128.dp).clip(RoundedCornerShape(6.dp)).clickable { onPlayTracks(recent, i) }) {
                            AsyncImage(
                                model = DownloadManager.coverFor(t),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(128.dp).clip(RoundedCornerShape(6.dp)).background(SpotyColors.SurfaceHigh),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(t.name, color = SpotyColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = SpotyFonts.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artists, color = SpotyColors.TextSecondary, fontSize = 12.sp, fontFamily = SpotyFonts.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        item(key = "playlists-head") {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("Mes playlists", Modifier.weight(1f))
                Row(
                    Modifier
                        .clip(CircleShape)
                        .border(1.dp, Color(0xFF555555), CircleShape)
                        .clickable { showCreateDialog = true }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = SpotyColors.TextPrimary, modifier = Modifier.size(18.dp))
                    Text("Créer", color = SpotyColors.TextPrimary, fontSize = 14.sp, fontFamily = SpotyFonts.Text, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (playlists.isEmpty()) {
            item(key = "playlists-empty") {
                Text(
                    "Crée une playlist ici, ou depuis le menu ⋮ d'un titre.",
                    color = SpotyColors.TextSecondary,
                    fontSize = 14.sp,
                    fontFamily = SpotyFonts.Text,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        items(playlists, key = { it.id }) { pl ->
            PlaylistRow(
                playlist = pl,
                onClick = { onOpenCollection(UserCollectionNav("user:${pl.id}")) },
            )
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
                        focusedBorderColor = SpotyColors.Accent,
                        cursorColor = SpotyColors.Accent,
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
                    colors = ButtonDefaults.buttonColors(containerColor = SpotyColors.Accent)
                ) {
                    Text("Créer", color = Color(0xFF111111), fontWeight = FontWeight.Bold)
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

internal fun titresLabel(n: Int) = if (n > 1) "$n titres" else "$n titre"

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = SpotyColors.TextPrimary, fontSize = 18.sp, fontFamily = SpotyFonts.Display, fontWeight = FontWeight.Bold, modifier = modifier)
}

@Composable
private fun Shortcut(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    iconBackground: Brush,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SpotyColors.Surface)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(iconBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = SpotyColors.TextSecondary, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun PlaylistRow(playlist: UserPlaylist, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                text = "Playlist • ${titresLabel(playlist.tracks.size)}",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
            )
        }
    }
}
