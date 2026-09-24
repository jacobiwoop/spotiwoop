package com.spotywoop.kt.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import com.spotywoop.kt.R
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import com.spotywoop.kt.data.DownloadManager
import com.spotywoop.kt.data.LocalLibraryStore
import com.spotywoop.kt.data.TrackResult
import com.spotywoop.kt.ui.theme.SpotyColors
import java.util.Calendar

@Composable
fun HomeScreen(
    onPlayTrack: (TrackResult) -> Unit,
    onTrackOptions: (TrackResult) -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onSearchCategory: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val likedTracks by LocalLibraryStore.likedTracks.collectAsStateWithLifecycle()
    val downloads by DownloadManager.downloads.collectAsStateWithLifecycle()

    var selectedFilter by remember { mutableStateOf("Tout") }
    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11 -> "Bonjour"
            in 12..17 -> "Bon après-midi"
            else -> "Bonsoir"
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(SpotyColors.Background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // En-tête : Avatar + Salutation + Boutons notifications & réglages
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Logo officiel Spotywoop
                Image(
                    painter = painterResource(id = R.drawable.ic_spotywoop_logo_badge),
                    contentDescription = "Logo Spotywoop",
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )

                Spacer(Modifier.width(12.dp))

                Text(
                    text = greeting,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )

                IconButton(onClick = {}) {
                    Icon(
                        Icons.Outlined.Notifications,
                        contentDescription = "Notifications",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }

                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = "Paramètres",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // Filtres (Tout / Musique / Podcasts)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Tout", "Musique", "Podcasts").forEach { filter ->
                    val isSelected = selectedFilter == filter
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isSelected) SpotyColors.Gold else Color(0xFF2A2A2A)
                            )
                            .clickable { selectedFilter = filter }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = filter,
                            color = if (isSelected) Color.Black else Color.White,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // Grille d'accès rapide 2 colonnes x 3 lignes (Style Spotify authentique)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Ligne 1 : Titres likés & Téléchargements
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickAccessCard(
                        title = "Titres likés",
                        gradient = Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFF8E8EE5))),
                        icon = Icons.Rounded.Favorite,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenLibrary,
                    )
                    QuickAccessCard(
                        title = "Téléchargements",
                        gradient = Brush.linearGradient(listOf(Color(0xFF1DB954), Color(0xFF104924))),
                        icon = Icons.Rounded.Download,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenDownloads,
                    )
                }

                // Ligne 2 : Artistes / Hits populaires
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickAccessCard(
                        title = "Stromae",
                        imageUrl = "https://i.scdn.co/image/ab6761610000e5ebba02e3b2e5563a5aa180a562",
                        modifier = Modifier.weight(1f),
                        onClick = { onSearchCategory("Stromae") },
                    )
                    QuickAccessCard(
                        title = "Daft Punk",
                        imageUrl = "https://i.scdn.co/image/ab6761610000e5eb989ed050d2109e25e36fa7fb",
                        modifier = Modifier.weight(1f),
                        onClick = { onSearchCategory("Daft Punk") },
                    )
                }

                // Ligne 3 : Mix & Tendances
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickAccessCard(
                        title = "Afrobeat Mix",
                        gradient = Brush.linearGradient(listOf(Color(0xFFE13300), Color(0xFF73230B))),
                        modifier = Modifier.weight(1f),
                        onClick = { onSearchCategory("Afrobeat") },
                    )
                    QuickAccessCard(
                        title = "Top 50 - France",
                        gradient = Brush.linearGradient(listOf(Color(0xFF8D67AB), Color(0xFF4A154B))),
                        modifier = Modifier.weight(1f),
                        onClick = { onSearchCategory("Top 50 France") },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // Section : Vos titres likés (si disponibles)
        if (likedTracks.isNotEmpty()) {
            item {
                SectionHeader(title = "Vos titres favoris", subtitle = "${likedTracks.size} titres enregistrés")
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(likedTracks, key = { "liked_${it.id}" }) { track ->
                        TrackCard(
                            track = track,
                            onClick = { onPlayTrack(track) },
                            onLongClick = { onTrackOptions(track) },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // Section : Téléchargés hors-ligne (si disponibles)
        if (downloads.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Disponible hors-ligne",
                    subtitle = "${downloads.size} titres prêts à l'écoute",
                )
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(downloads, key = { "dl_${it.track.id}" }) { item ->
                        TrackCard(
                            track = item.track,
                            onClick = { onPlayTrack(item.track) },
                            onLongClick = { onTrackOptions(item.track) },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // Section : Recommandations & Mix populaires
        item {
            SectionHeader(title = "Mix conçus pour vous", subtitle = "Basé sur vos écoutes récentes")
            Spacer(Modifier.height(10.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val mixes = listOf(
                    CuratedMix("Daily Mix 1", "Stromae, Daft Punk, Orelsan", Brush.linearGradient(listOf(Color(0xFFE91429), Color(0xFF3B0006)))),
                    CuratedMix("Afro Vibes", "Burna Boy, Rema, Wizkid, Asake", Brush.linearGradient(listOf(Color(0xFFFF6437), Color(0xFF531A00)))),
                    CuratedMix("Chill & Lo-Fi", "Détente, beats doux et focus", Brush.linearGradient(listOf(Color(0xFF1E3264), Color(0xFF0D1B2A)))),
                    CuratedMix("Électro Party", "French Touch, House & Dance", Brush.linearGradient(listOf(Color(0xFFB02897), Color(0xFF3C0A33)))),
                    CuratedMix("Hip-Hop Français", "Ninho, PLK, Damso, SDM", Brush.linearGradient(listOf(Color(0xFF509BF5), Color(0xFF103060)))),
                )
                items(mixes) { mix ->
                    CuratedMixCard(
                        mix = mix,
                        onClick = { onSearchCategory(mix.title) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // Section : Explorer par genre
        item {
            SectionHeader(title = "Explorer les genres", subtitle = "Trouvez votre prochaine écoute")
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val genres = listOf(
                    Pair("Rap & Hip-Hop", Color(0xFFE91429)),
                    Pair("Afrobeat", Color(0xFFFF7A00)),
                    Pair("Pop Internationale", Color(0xFF1DB954)),
                    Pair("Électronique / Dance", Color(0xFF8D67AB)),
                    Pair("R&B & Soul", Color(0xFFE13300)),
                    Pair("Rock & Alternatives", Color(0xFF27856A)),
                )
                for (i in genres.indices step 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GenreBadge(
                            name = genres[i].first,
                            color = genres[i].second,
                            modifier = Modifier.weight(1f),
                            onClick = { onSearchCategory(genres[i].first) },
                        )
                        if (i + 1 < genres.size) {
                            GenreBadge(
                                name = genres[i + 1].first,
                                color = genres[i + 1].second,
                                modifier = Modifier.weight(1f),
                                onClick = { onSearchCategory(genres[i + 1].first) },
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = SpotyColors.TextMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun QuickAccessCard(
    title: String,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    gradient: Brush? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF242424))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .then(
                    if (gradient != null) Modifier.background(gradient)
                    else Modifier.background(Color(0xFF333333))
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                imageUrl != null -> {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                icon != null -> {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        Spacer(Modifier.width(10.dp))

        Text(
            text = title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
    }
}

@Composable
private fun TrackCard(
    track: TrackResult,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(130.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF282828)),
        ) {
            if (track.cover != null) {
                AsyncImage(
                    model = track.cover,
                    contentDescription = track.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = track.name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = track.artists,
            color = SpotyColors.TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class CuratedMix(
    val title: String,
    val subtitle: String,
    val gradient: Brush,
)

@Composable
private fun CuratedMixCard(
    mix: CuratedMix,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(135.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(135.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(mix.gradient)
                .padding(12.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column {
                Text(
                    text = mix.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 18.sp,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = mix.title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = mix.subtitle,
            color = SpotyColors.TextMuted,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GenreBadge(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = name,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
