package com.spotywoop.kt.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.spotywoop.kt.data.ActiveDownload
import com.spotywoop.kt.data.DownloadManager
import com.spotywoop.kt.data.DownloadedTrack
import com.spotywoop.kt.data.SavedCollection
import com.spotywoop.kt.data.TrackResult
import com.spotywoop.kt.ui.theme.SpotyColors
import com.spotywoop.kt.ui.theme.SpotyFonts

private enum class DownloadFilter(val label: String) { All("Tout"), Albums("Albums"), Playlists("Playlists"), Tracks("Titres") }

private val SavedCollection.isAlbum get() = kind == "album"

/**
 * Écran Téléchargements : albums, playlists et titres mélangés, du plus récent au plus ancien,
 * avec un filtre pour n'afficher qu'un type.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(
    onPlayTracks: (List<TrackResult>, Int) -> Unit,
    onTrackOptions: (TrackResult) -> Unit = {},
    onOpenCollection: (Any) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val downloads by DownloadManager.downloads.collectAsStateWithLifecycle()
    val allCollections by DownloadManager.collections.collectAsStateWithLifecycle()
    // Une playlist vidée reste suivie, mais n'a rien à montrer ici.
    val collections = remember(allCollections) { allCollections.filter { it.tracks.isNotEmpty() } }
    val activeMap by DownloadManager.activeDownloads.collectAsStateWithLifecycle()
    val failed by DownloadManager.failedIds.collectAsStateWithLifecycle()
    var filterName by rememberSaveable { mutableStateOf(DownloadFilter.All.name) }
    val filter = DownloadFilter.valueOf(filterName)

    val byId = remember(downloads) { downloads.associateBy { it.track.id } }
    val inCollections = remember(collections) { collections.flatMap { c -> c.tracks.map { it.id } }.toSet() }
    val newestFirst = remember(downloads) { downloads.sortedByDescending { it.downloadedAt } }
    // Dans « Tout », un titre d'un album téléchargé n'apparaît pas une deuxième fois.
    val loose = remember(newestFirst, inCollections) { newestFirst.filterNot { it.track.id in inCollections } }
    val active = remember(activeMap, inCollections) { activeMap.values.filterNot { it.track.id in inCollections } }

    val totalMb = downloads.sumOf { it.fileSize } / (1024f * 1024f)
    val open: (SavedCollection) -> Unit = { c ->
        when (c.kind) {
            "album", "playlist" -> onOpenCollection(CollectionNav(c.kind, c.ref, c.title, c.image, c.subtitle.orEmpty()))
            else -> onOpenCollection(UserCollectionNav(c.key))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SpotyColors.Background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Téléchargements", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (activeMap.isNotEmpty()) {
                        "${downloads.size} terminés • ${activeMap.size} en cours"
                    } else {
                        "${titresLabel(downloads.size)} • %.1f Mo utilisés".format(totalMb)
                    },
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                )
            }
            if (downloads.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(SpotyColors.SpotifyGreen)
                        .clickable { onPlayTracks(newestFirst.map { it.track }, 0) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Tout lire", tint = Color.Black, modifier = Modifier.size(28.dp))
                }
            }
        }

        if (downloads.isEmpty() && activeMap.isEmpty() && collections.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 100.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.DownloadDone, contentDescription = null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Aucun titre téléchargé", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text("Appuyez sur Télécharger dans un album, ou ⋮ sur un titre.", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                }
            }
            return@Column
        }

        WebChips(DownloadFilter.entries.map { it.name to it.label }, filterName) { filterName = it }
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
        ) {
            if (filter == DownloadFilter.All || filter == DownloadFilter.Tracks) {
                items(active, key = { "active_${it.track.id}" }) { ActiveDownloadRow(it) }
            }
            when (filter) {
                DownloadFilter.All -> {
                    val mixed = (collections.map { it.savedAt to it } + loose.map { it.downloadedAt to it }).sortedByDescending { it.first }.map { it.second }
                    items(mixed, key = { if (it is SavedCollection) "c_${it.key}" else "t_${(it as DownloadedTrack).track.id}" }) { entry ->
                        when (entry) {
                            is SavedCollection -> if (entry.isAlbum) {
                                AlbumCard(entry, byId, failed, onClick = { open(entry) })
                            } else {
                                PlaylistCard(entry, byId, failed, onClick = { open(entry) })
                            }
                            is DownloadedTrack -> TrackLine(
                                entry,
                                onClick = { onPlayTracks(loose.map { it.track }, loose.indexOf(entry)) },
                                onOptions = { onTrackOptions(entry.track) },
                            )
                        }
                    }
                }
                DownloadFilter.Albums -> grid(collections.filter { it.isAlbum }, byId, failed, open, "Aucun album téléchargé.")
                DownloadFilter.Playlists -> grid(collections.filterNot { it.isAlbum }, byId, failed, open, "Aucune playlist téléchargée.")
                DownloadFilter.Tracks -> {
                    if (newestFirst.isEmpty() && active.isEmpty()) {
                        item(key = "empty") { WebState(text = "Aucun titre téléchargé.") }
                    }
                    items(newestFirst, key = { "t_${it.track.id}" }) { entry ->
                        TrackLine(
                            entry,
                            onClick = { onPlayTracks(newestFirst.map { it.track }, newestFirst.indexOf(entry)) },
                            onOptions = { onTrackOptions(entry.track) },
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.grid(
    list: List<SavedCollection>,
    byId: Map<String, DownloadedTrack>,
    failed: Set<String>,
    open: (SavedCollection) -> Unit,
    emptyText: String,
) {
    if (list.isEmpty()) {
        item(key = "empty") { WebState(text = emptyText) }
        return
    }
    items(list.chunked(2), key = { it.first().key }) { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEach { c ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { open(c) }.padding(bottom = 8.dp)) {
                    if (c.isAlbum) {
                        AsyncImage(
                            model = cover(c, byId),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(SpotyColors.SurfaceHigh),
                        )
                    } else {
                        Mosaic(c, byId, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(c.title, color = SpotyColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = SpotyFonts.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Status(c, byId, failed)
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

private fun cover(c: SavedCollection, byId: Map<String, DownloadedTrack>): Any? =
    c.tracks.firstNotNullOfOrNull { t -> byId[t.id]?.localCoverPath?.let { java.io.File(it) } } ?: c.image

/** « 13 titres » une fois complet, « 12/13 » sinon, en rouge après un échec. */
@Composable
private fun Status(c: SavedCollection, byId: Map<String, DownloadedTrack>, failed: Set<String>) {
    val got = c.tracks.count { it.id in byId }
    val hasFailed = c.tracks.any { it.id in failed }
    Text(
        if (got == c.tracks.size) titresLabel(c.tracks.size) else "$got/${c.tracks.size}",
        color = if (hasFailed) SpotyColors.Down else SpotyColors.TextSecondary,
        fontSize = 13.sp,
        fontFamily = SpotyFonts.Text,
        maxLines = 1,
    )
}

/** Album : grande carte pleine, un disque dépasse de la pochette. */
@Composable
private fun AlbumCard(c: SavedCollection, byId: Map<String, DownloadedTrack>, failed: Set<String>, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SpotyColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(112.dp).height(88.dp)) {
            Box(
                Modifier
                    .offset(x = 36.dp, y = 6.dp)
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF050505))
                    .border(1.dp, Color(0xFF3A3A3A), CircleShape)
                    .padding(end = 10.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(SpotyColors.Accent))
            }
            AsyncImage(
                model = cover(c, byId),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(88.dp).shadow(6.dp, shape).clip(shape).background(SpotyColors.Surface),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("ALBUM", color = SpotyColors.Accent, fontSize = 11.sp, letterSpacing = 1.2.sp, fontFamily = SpotyFonts.Display, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(c.title, color = SpotyColors.TextPrimary, fontSize = 18.sp, fontFamily = SpotyFonts.Display, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            c.subtitle?.let { Text(it, color = Color(0xFFBDBDBD), fontSize = 13.sp, fontFamily = SpotyFonts.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Status(c, byId, failed)
        }
    }
}

/** Playlist : grande carte à contour, pochette en mosaïque de 4 titres. */
@Composable
private fun PlaylistCard(c: SavedCollection, byId: Map<String, DownloadedTrack>, failed: Set<String>, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, SpotyColors.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mosaic(c, byId, Modifier.size(88.dp).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = SpotyColors.TextSecondary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("PLAYLIST", color = SpotyColors.TextSecondary, fontSize = 11.sp, letterSpacing = 1.2.sp, fontFamily = SpotyFonts.Display, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(3.dp))
            Text(c.title, color = SpotyColors.TextPrimary, fontSize = 18.sp, fontFamily = SpotyFonts.Display, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Status(c, byId, failed)
        }
    }
}

@Composable
private fun Mosaic(c: SavedCollection, byId: Map<String, DownloadedTrack>, modifier: Modifier) {
    val cells = c.tracks.distinctBy { it.cover }.take(4).map { t -> byId[t.id]?.localCoverPath?.let { java.io.File(it) } ?: t.cover }
    Column(modifier.background(SpotyColors.SurfaceHigh)) {
        repeat(2) { row ->
            Row(Modifier.weight(1f).fillMaxWidth()) {
                repeat(2) { col ->
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        cells.getOrNull(row * 2 + col)?.let {
                            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                        }
                    }
                }
            }
        }
    }
}

/** Titre : ligne fine, sans carte. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackLine(item: DownloadedTrack, onClick: () -> Unit, onOptions: () -> Unit) {
    val track = item.track
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onOptions)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)).background(SpotyColors.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = SpotyColors.TextMuted)
            // Pochette locale passée en File : son nom peut contenir « : » (am:123_cover.jpg).
            val cover: Any? = item.localCoverPath?.let { java.io.File(it) } ?: track.cover?.takeIf { it.isNotBlank() }
            if (cover != null) {
                AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DownloadDone, contentDescription = null, tint = SpotyColors.SpotifyGreen, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(track.artists, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onOptions) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Options", tint = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun ActiveDownloadRow(item: ActiveDownload) {
    val track = item.track
    val percent = (item.progress * 100).toInt()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SpotyColors.Surface)
            .padding(10.dp),
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
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = SpotyColors.SpotifyGreen,
                trackColor = Color.White.copy(alpha = 0.2f),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = track.artists,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = if (item.progress > 0f) "$percent%" else "Démarrage...",
                    color = SpotyColors.SpotifyGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
