package com.spotywoop.kt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.spotywoop.kt.data.ArtistPage
import com.spotywoop.kt.data.CollectionPage
import com.spotywoop.kt.data.FollowedArtist
import com.spotywoop.kt.data.LocalLibraryStore
import com.spotywoop.kt.data.SpotiwoopApi
import com.spotywoop.kt.data.TrackResult
import com.spotywoop.kt.ui.theme.SpotyColors

private val CardColor = Color(0xFF1E1E1E)

internal fun splitArtists(artists: String): List<String> =
    artists.split(",").map { it.trim() }.filter { it.isNotEmpty() }

/** Nom de l'artiste dans le lecteur : ouvre sa page, ou propose le choix si le titre en a plusieurs. */
@Composable
internal fun ArtistNameLink(artist: String, onOpenArtist: (ArtistNav) -> Unit) {
    val names = remember(artist) { splitArtists(artist) }
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Text(
            text = com.spotywoop.kt.data.AppSettings.formatDisplay(artist),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clickable {
                    if (names.size > 1) menuOpen = true
                    else names.firstOrNull()?.let { onOpenArtist(ArtistNav("name:$it", it)) }
                }
                .padding(vertical = 6.dp),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            names.forEach { name ->
                DropdownMenuItem(
                    text = { Text(com.spotywoop.kt.data.AppSettings.formatDisplay(name), color = Color.White) },
                    onClick = {
                        menuOpen = false
                        onOpenArtist(ArtistNav("name:$name", name))
                    },
                )
            }
        }
    }
}

/** Ce qui suit les paroles dans le lecteur : artiste, file d'attente, autres titres, similaires, crédits. */
@Composable
internal fun PlayerDetails(
    now: NowPlaying,
    queue: List<TrackResult>,
    onOpenArtist: (ArtistNav) -> Unit,
    onOpenQueue: () -> Unit,
    onPlayFromQueue: (TrackResult) -> Unit,
    onPlayTracks: (List<TrackResult>, Int) -> Unit,
) {
    val main = remember(now.artist) { splitArtists(now.artist).firstOrNull().orEmpty() }
    var artist by remember(main) { mutableStateOf<ArtistPage?>(null) }
    var album by remember(now.album, main) { mutableStateOf<CollectionPage?>(null) }

    LaunchedEffect(main) {
        if (main.isNotEmpty()) artist = runCatching { SpotiwoopApi.artist("name:$main") }.getOrNull()
    }
    LaunchedEffect(now.album, main) {
        if (now.album.isBlank()) return@LaunchedEffect
        album = runCatching {
            SpotiwoopApi.findAlbumRef(now.album, now.artist)?.let { SpotiwoopApi.collection("album", it) }
        }.getOrNull()?.takeIf { it.title.equals(now.album, ignoreCase = true) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (main.isNotEmpty()) AboutCard(main, artist, onOpenArtist)

        val index = queue.indexOfFirst { it.id == now.id }
        val upcoming = if (index >= 0) queue.drop(index + 1).take(3) else emptyList()
        if (upcoming.isNotEmpty()) {
            DetailCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardTitle("À suivre", Modifier.weight(1f))
                    Text(
                        "File d'attente",
                        color = Color(0xFFBDBDBD),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onOpenQueue).padding(vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                upcoming.forEach { t ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onPlayFromQueue(t) }.padding(vertical = 7.dp),
                    ) {
                        AsyncImage(t.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)).background(SpotyColors.SurfaceHigh))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(t.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artists, color = SpotyColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        val others = artist?.topTracks.orEmpty().filterNot { it.id == now.id || it.name.equals(now.title, ignoreCase = true) }
        if (others.isNotEmpty()) {
            ScrollCard("Autres titres de ${artist?.name ?: main}") {
                others.forEachIndexed { i, t ->
                    Column(Modifier.width(116.dp).clickable { onPlayTracks(others, i) }) {
                        AsyncImage(t.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.size(116.dp).clip(RoundedCornerShape(6.dp)).background(SpotyColors.SurfaceHigh))
                        Spacer(Modifier.height(8.dp))
                        Text(t.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        val similar = artist?.related.orEmpty()
        if (similar.isNotEmpty()) {
            ScrollCard("Artistes similaires") {
                similar.forEach { c ->
                    ArtistBubble(c.title, c.image, 92.dp) { onOpenArtist(ArtistNav(c.ref, c.title, c.image)) }
                }
            }
        }

        val credits = listOfNotNull(
            "Interprète" to now.artist,
            now.album.takeIf { it.isNotBlank() }?.let { "Album" to it },
            album?.date?.take(4)?.let { "Année" to it },
            album?.label?.let { "Label" to it },
        )
        DetailCard {
            CardTitle("Crédits")
            credits.forEach { (k, v) ->
                Spacer(Modifier.height(12.dp))
                Text(k, color = SpotyColors.TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(2.dp))
                Text(v, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AboutCard(name: String, page: ArtistPage?, onOpenArtist: (ArtistNav) -> Unit) {
    val followed by LocalLibraryStore.followedArtists.collectAsStateWithLifecycle()
    val isFollowed = followed.any { it.name.equals(name, ignoreCase = true) }
    val open = { onOpenArtist(ArtistNav("name:$name", name, page?.image)) }
    var bioOpen by remember(name) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardColor)) {
        val photo = page?.image ?: page?.banner
        if (photo != null) {
            Box(Modifier.fillMaxWidth().height(190.dp).clickable(onClick = open)) {
                AsyncImage(photo, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(190.dp).background(SpotyColors.SurfaceHigh))
                Box(Modifier.fillMaxWidth().height(80.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))))
                Text(
                    "À propos de l'artiste",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp),
                )
            }
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp)) {
            if (photo == null) {
                CardTitle("À propos de l'artiste")
                Spacer(Modifier.height(12.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = open)) {
                    Text(page?.name ?: name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    page?.monthlyListeners?.takeIf { it > 0 }?.let {
                        Spacer(Modifier.height(2.dp))
                        Text("${fullCount(it)} auditeurs mensuels", color = Color(0xFFBDBDBD), fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                val followShape = RoundedCornerShape(50)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(40.dp)
                        .clip(followShape)
                        .border(1.dp, if (isFollowed) SpotyColors.Accent else Color.White.copy(alpha = 0.6f), followShape)
                        .clickable { LocalLibraryStore.toggleFollow(FollowedArtist(name, "name:$name", page?.image)) }
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        if (isFollowed) "Abonné" else "S'abonner",
                        color = if (isFollowed) SpotyColors.Accent else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            page?.bio?.let(::plainBio)?.let { bio ->
                Spacer(Modifier.height(12.dp))
                Text(
                    bio,
                    color = Color(0xFFD4D4D4),
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    maxLines = if (bioOpen) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (bio.length > 160) {
                    Text(
                        if (bioOpen) "Moins" else "Lire la suite",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { bioOpen = !bioOpen }.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** La bio arrive en HTML : balises et entités ne doivent pas s'afficher telles quelles. */
private fun plainBio(bio: String): String = bio
    .replace(Regex("<[^>]+>"), "")
    .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }
    .replace("&amp;", "&")

/** Photo ronde et nom d'un artiste, partagés par le lecteur et la Bibliothèque. */
@Composable
internal fun ArtistBubble(name: String, image: String?, size: Dp = 92.dp, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(size).clickable(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size).clip(CircleShape).background(SpotyColors.SurfaceHigh),
        ) {
            Text(name.take(1).uppercase(), color = SpotyColors.TextSecondary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        }
        Spacer(Modifier.height(8.dp))
        Text(name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardColor).padding(20.dp)) {
        content()
    }
}

@Composable
private fun ScrollCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardColor).padding(vertical = 20.dp)) {
        CardTitle(title, Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
