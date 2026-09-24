package com.spotywoop.kt.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.spotywoop.kt.data.SpotifyClient
import com.spotywoop.kt.data.TrackResult
import com.spotywoop.kt.ui.theme.ColorExtractor
import com.spotywoop.kt.ui.theme.SpotyColors

/**
 * Écran Album façon Spotify Mobile :
 * - Grande pochette avec extraction de couleur dynamique
 * - Titre de l'album, artiste, liste des morceaux numérotés
 * - Lecture séquentielle de l'album
 */
@Composable
fun AlbumScreen(
    albumName: String,
    artistName: String,
    coverUrl: String? = null,
    onBack: () -> Unit,
    onPlayTracks: (List<TrackResult>, Int) -> Unit,
    onTrackOptions: (TrackResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var dominantColor by remember { mutableStateOf(Color(0xFF282828)) }
    val animatedBg by animateColorAsState(dominantColor, animationSpec = tween(600), label = "AlbumBg")

    var loading by remember { mutableStateOf(true) }
    var tracks by remember { mutableStateOf<List<TrackResult>>(emptyList()) }
    var effectiveCover by remember { mutableStateOf(coverUrl) }

    LaunchedEffect(coverUrl) {
        if (!coverUrl.isNullOrBlank()) {
            dominantColor = ColorExtractor.extractDominantColor(context, coverUrl)
        }
    }

    LaunchedEffect(albumName, artistName) {
        loading = true
        try {
            val client = SpotifyClient()
            val query = "album:\"$albumName\" artist:\"$artistName\""
            val results = client.search(query, limit = 30)
            val foundTracks = results.tracks.filter {
                it.album.contains(albumName, ignoreCase = true) || it.artists.contains(artistName, ignoreCase = true)
            }.ifEmpty { results.tracks }

            tracks = foundTracks
            if (effectiveCover == null && foundTracks.isNotEmpty()) {
                val cov = foundTracks.firstOrNull()?.cover
                effectiveCover = cov
                if (cov != null) {
                    dominantColor = ColorExtractor.extractDominantColor(context, cov)
                }
            }
        } catch (_: Exception) {
            // Fallback recherche générique
            try {
                val client = SpotifyClient()
                val results = client.search("$albumName $artistName", limit = 25)
                tracks = results.tracks
            } catch (_: Exception) { }
        } finally {
            loading = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(animatedBg, Color(0xFF121212), Color(0xFF121212)),
                    startY = 0f,
                    endY = 1200f,
                )
            )
            .statusBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            // Barre de retour supérieure
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour", tint = Color.White)
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
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Pochette d'album
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SpotyColors.SurfaceHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = SpotyColors.TextMuted, modifier = Modifier.size(64.dp))
                            if (!effectiveCover.isNullOrBlank()) {
                                AsyncImage(
                                    model = effectiveCover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = albumName,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = artistName,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Album • ${tracks.size} titres",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp,
                        )

                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(SpotyColors.SpotifyGreen)
                                    .clickable(enabled = tracks.isNotEmpty()) {
                                        onPlayTracks(tracks, 0)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = "Lire tout l'album",
                                    tint = Color.Black,
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }

                if (loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = SpotyColors.SpotifyGreen)
                        }
                    }
                } else if (tracks.isEmpty()) {
                    item {
                        Text(
                            text = "Aucun morceau trouvé pour cet album.",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                } else {
                    itemsIndexed(tracks, key = { index, t -> "${t.id}_$index" }) { index, track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlayTracks(tracks, index) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 14.sp,
                                modifier = Modifier.width(28.dp),
                            )
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
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = track.durationLabel,
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 12.sp,
                            )
                            IconButton(onClick = { onTrackOptions(track) }) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    contentDescription = "Options",
                                    tint = Color.White.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
