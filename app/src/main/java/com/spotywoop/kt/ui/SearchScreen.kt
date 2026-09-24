package com.spotywoop.kt.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.spotywoop.kt.BuildConfig
import com.spotywoop.kt.R
import com.spotywoop.kt.data.SearchResults
import com.spotywoop.kt.data.TrackResult
import com.spotywoop.kt.ui.theme.SpotyColors

@Composable
fun SearchScreen(
    onPlayTrack: (TrackResult) -> Unit,
    onTrackOptions: (TrackResult) -> Unit = {},
    onOpenAlbum: (albumName: String, artistName: String, coverUrl: String?) -> Unit = { _, _, _ -> },
    onOpenArtist: (artistName: String, coverUrl: String?) -> Unit = { _, _ -> },
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    vm: SearchViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        focus.clearFocus()
        vm.search()
    }

    Column(
        modifier
            .background(SpotyColors.Background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Header(onOpenSettings = onOpenSettings)
        Spacer(Modifier.height(20.dp))
        SearchBar(
            query = state.query,
            onQueryChange = vm::onQueryChange,
            onSubmit = submit,
            loading = state.loading,
        )
        state.results?.let { results ->
            Spacer(Modifier.height(12.dp))
            Tabs(results, state.tab, vm::onTabSelected)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val results = state.results
            when {
                state.loading && results == null -> CircularProgressIndicator(
                    color = SpotyColors.Gold,
                    modifier = Modifier.align(Alignment.Center),
                )
                state.error != null -> Message(state.error!!, SpotyColors.Explicit)
                results == null -> Message("Recherche un titre, un album, un artiste ou une playlist.")
                else -> ResultList(
                    results = results,
                    tab = state.tab,
                    onPlayTrack = onPlayTrack,
                    onTrackOptions = onTrackOptions,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                )
            }
        }
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar rond avec pastille bleue de statut
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF472B6)), // Rose comme sur la capture
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "S",
                    color = Color(0xFF1E1E1E),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(Color(0xFF38BDF8))
                    .border(1.5.dp, SpotyColors.Background, CircleShape),
            )
        }

        Spacer(Modifier.width(12.dp))

        Text(
            text = "Rechercher",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )

        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Outlined.PhotoCamera,
                contentDescription = "Scan / Paramètres",
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    loading: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = Color(0xFF191414),
            modifier = Modifier.size(24.dp),
        )

        Spacer(Modifier.width(10.dp))

        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "Que souhaitez-vous écouter ?",
                    color = Color(0xFF535353),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = Color(0xFF191414),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(Color.Black),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (loading) {
            CircularProgressIndicator(
                color = Color(0xFF1DB954),
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else if (query.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Effacer",
                tint = Color(0xFF535353),
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable { onQueryChange("") },
            )
        }
    }
}

@Composable
private fun Tabs(results: SearchResults, selected: SearchTab, onSelect: (SearchTab) -> Unit) {
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            SearchTab.entries.forEach { tab ->
                val count = when (tab) {
                    SearchTab.Tracks -> results.tracks.size
                    SearchTab.Albums -> results.albums.size
                    SearchTab.Artists -> results.artists.size
                    SearchTab.Playlists -> results.playlists.size
                }
                val active = tab == selected
                Column(
                    Modifier.clickable { onSelect(tab) }.padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "${tab.label} ($count)",
                        color = if (active) SpotyColors.TextPrimary else SpotyColors.TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                    Box(
                        Modifier
                            .height(2.dp)
                            .width(if (active) 96.dp else 0.dp)
                            .background(SpotyColors.Gold),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(SpotyColors.Border))
    }
}

@Composable
private fun ResultList(
    results: SearchResults,
    tab: SearchTab,
    onPlayTrack: (TrackResult) -> Unit,
    onTrackOptions: (TrackResult) -> Unit,
    onOpenAlbum: (albumName: String, artistName: String, coverUrl: String?) -> Unit,
    onOpenArtist: (artistName: String, coverUrl: String?) -> Unit,
) {
    val rows: List<RowData> = when (tab) {
        SearchTab.Tracks -> results.tracks.map { track ->
            RowData(
                id = track.id,
                title = track.name,
                subtitle = track.artists,
                cover = track.cover,
                placeholder = Icons.Outlined.MusicNote,
                explicit = track.isExplicit,
                trailing = track.durationLabel,
                onClick = { onPlayTrack(track) },
                onOptionsClick = { onTrackOptions(track) },
            )
        }
        SearchTab.Albums -> results.albums.map { alb ->
            RowData(
                id = alb.id,
                title = alb.name,
                subtitle = listOfNotNull(alb.artists, alb.year?.toString()).joinToString(" • "),
                cover = alb.cover,
                placeholder = Icons.Outlined.Album,
                onClick = { onOpenAlbum(alb.name, alb.artists, alb.cover) },
            )
        }
        SearchTab.Artists -> results.artists.map { art ->
            RowData(
                id = art.id,
                title = art.name,
                subtitle = "Artiste",
                cover = art.cover,
                placeholder = Icons.Outlined.Person,
                round = true,
                onClick = { onOpenArtist(art.name, art.cover) },
            )
        }
        SearchTab.Playlists -> results.playlists.map { pl ->
            RowData(
                id = pl.id,
                title = pl.name,
                subtitle = pl.owner?.let { o -> "par $o" } ?: "Playlist",
                cover = pl.cover,
                placeholder = Icons.Outlined.QueueMusic,
            )
        }
    }
    if (rows.isEmpty()) {
        Message("Aucun résultat.")
        return
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 100.dp),
    ) {
        items(rows, key = { "${tab.name}:${it.id}" }) { ResultRow(it) }
    }
}

private data class RowData(
    val id: String,
    val title: String,
    val subtitle: String,
    val cover: String?,
    val placeholder: ImageVector,
    val explicit: Boolean = false,
    val trailing: String? = null,
    val round: Boolean = false,
    val onClick: () -> Unit = {},
    val onOptionsClick: (() -> Unit)? = null,
)

@Composable
private fun ResultRow(row: RowData) {
    val shape: Shape = if (row.round) CircleShape else RoundedCornerShape(4.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SpotyColors.Surface)
            .border(BorderStroke(1.dp, SpotyColors.Border), RoundedCornerShape(8.dp))
            .clickable(onClick = row.onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(shape).background(SpotyColors.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(row.placeholder, contentDescription = null, tint = SpotyColors.TextMuted, modifier = Modifier.size(22.dp))
            if (row.cover != null) {
                AsyncImage(
                    model = row.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title,
                    color = SpotyColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.explicit) {
                    Spacer(Modifier.width(6.dp))
                    ExplicitBadge()
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                row.subtitle,
                color = SpotyColors.TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        row.trailing?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, color = SpotyColors.TextSecondary, fontSize = 12.sp)
        }
        if (row.onOptionsClick != null) {
            IconButton(
                onClick = row.onOptionsClick,
                modifier = Modifier.size(36.dp).padding(4.dp),
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "Options",
                    tint = SpotyColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ExplicitBadge() {
    Text(
        "E",
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 10.sp,
        modifier = Modifier
            .background(SpotyColors.Explicit, RoundedCornerShape(2.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun Message(text: String, color: Color = SpotyColors.TextSecondary) {
    Text(
        text,
        color = color,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp, start = 16.dp, end = 16.dp),
    )
}
