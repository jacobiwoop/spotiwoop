package com.spotywoop.kt.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.spotywoop.kt.data.ArtistResult
import com.spotywoop.kt.data.PlaylistResult
import com.spotywoop.kt.data.SearchResults
import com.spotywoop.kt.data.TrackResult
import com.spotywoop.kt.ui.theme.SpotyColors
import com.spotywoop.kt.ui.theme.SpotyFonts

/** Recherche : la page de résultats du site web (version mobile). */
@Composable
fun SearchScreen(
    onPlayTrack: (TrackResult) -> Unit,
    onPlayTracks: (List<TrackResult>, Int) -> Unit = { list, i -> list.getOrNull(i)?.let(onPlayTrack) },
    onTrackOptions: (TrackResult) -> Unit = {},
    onOpenAlbum: (CollectionNav) -> Unit = {},
    onOpenArtist: (ArtistNav) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    currentTrackId: String? = null,
    focusRequest: Int = 0,
    modifier: Modifier = Modifier,
    vm: SearchViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val submit = {
        keyboard?.hide()
        focus.clearFocus()
        vm.search()
    }
    // Un appui sur le champ de l'accueil ouvre la recherche, curseur dans le champ.
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) runCatching { focusRequester.requestFocus() }
    }
    val playPlaylist: (PlaylistResult) -> Unit = { pl ->
        Toast.makeText(context, "Chargement de « ${pl.name} »…", Toast.LENGTH_SHORT).show()
        vm.playPlaylist(pl.id, onTracks = { onPlayTracks(it, 0) }) {
            Toast.makeText(context, "Impossible de charger ce contenu.", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier
            .background(SpotyColors.Background)
            .statusBarsPadding(),
    ) {
        WebTopBar(
            query = state.query,
            onQueryChange = vm::onQueryChange,
            onSubmit = submit,
            onOpenSettings = onOpenSettings,
            loading = state.loading,
            focusRequester = focusRequester,
        )
        val results = state.results
        when {
            state.showSuggestions && state.suggestions.isNotEmpty() -> SuggestionsList(state.suggestions) {
                keyboard?.hide()
                focus.clearFocus()
                vm.selectSuggestion(it)
            }
            state.loading && results == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SpotyColors.Accent)
            }
            state.error != null && results == null -> WebState(text = state.error!!, action = "Réessayer", onAction = { vm.search() })
            results == null -> WebState(title = "Recherche", text = "Cherche un titre, un artiste ou un album dans la barre en haut.")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 18.dp, bottom = 32.dp),
            ) {
                item(key = "head") {
                    Text(
                        "Résultats pour « ${state.searchedQuery} »",
                        color = SpotyColors.TextPrimary,
                        fontSize = 30.sp,
                        lineHeight = 36.sp,
                        fontFamily = SpotyFonts.Display,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    WebChips(SearchTab.entries.map { it.name to it.label }, state.tab.name) { vm.onTabSelected(SearchTab.valueOf(it)) }
                }
                results(
                    results = results,
                    query = state.searchedQuery,
                    tab = state.tab,
                    currentTrackId = currentTrackId,
                    onTab = vm::onTabSelected,
                    onPlayTrack = onPlayTrack,
                    onTrackOptions = onTrackOptions,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                    onPlayPlaylist = playPlaylist,
                )
            }
        }
    }
}

private fun LazyListScope.results(
    results: SearchResults,
    query: String,
    tab: SearchTab,
    currentTrackId: String?,
    onTab: (SearchTab) -> Unit,
    // Un titre trouvé se lance seul, suivi des suggestions : pas des autres résultats de la recherche.
    onPlayTrack: (TrackResult) -> Unit,
    onTrackOptions: (TrackResult) -> Unit,
    onOpenAlbum: (CollectionNav) -> Unit,
    onOpenArtist: (ArtistNav) -> Unit,
    onPlayPlaylist: (PlaylistResult) -> Unit,
) {
    val empty = results.tracks.isEmpty() && results.albums.isEmpty() && results.artists.isEmpty()
    if (empty) {
        item(key = "empty") { WebState(text = "Aucun résultat.") }
        return
    }
    fun tracks(list: List<TrackResult>, numbered: Boolean) =
        itemsIndexed(list, key = { i, t -> "t-$i-${t.id}" }) { i, t ->
            WebTrackRow(
                track = t,
                number = if (numbered) "${i + 1}" else null,
                current = t.id == currentTrackId,
                onClick = { onPlayTrack(t) },
                onOptions = { onTrackOptions(t) },
            )
        }
    fun albums(key: String, list: List<com.spotywoop.kt.data.AlbumResult>) = webGrid(list, key) { a, m ->
        WebCollectionCard(
            title = a.name,
            image = a.cover,
            subtitle = listOfNotNull(a.artists, a.year?.toString()).joinToString(" · "),
            stack = true,
            onClick = { onOpenAlbum(CollectionNav("album", spRef(a.id), a.name, a.cover, a.artists)) },
            modifier = m,
        )
    }
    fun artists(key: String, list: List<ArtistResult>) = webGrid(list, key) { a, m ->
        WebCollectionCard(title = a.name, image = a.cover, round = true, onClick = { onOpenArtist(ArtistNav(spRef(a.id, a.name), a.name, a.cover)) }, modifier = m)
    }
    fun playlists(key: String, list: List<PlaylistResult>) = webGrid(list, key) { p, m ->
        WebCollectionCard(title = p.name, image = p.cover, onClick = { onPlayPlaylist(p) }, modifier = m)
    }
    fun header(key: String, title: String, link: String? = null, onLink: () -> Unit = {}) = item(key = key) {
        Spacer(Modifier.height(30.dp))
        WebSectionHeader(title, link, onLink)
        Spacer(Modifier.height(if (link != null) 10.dp else 16.dp))
    }

    when (tab) {
        SearchTab.All -> {
            // Spotify n'ordonne pas toujours les artistes par pertinence (« Stromae alors on danse » donnait
            // Dadju) : le meilleur résultat est l'artiste nommé dans la recherche, sinon le premier titre.
            val q = " ${normalizeForMatch(query)} "
            val namedArtist = results.artists.firstOrNull { a -> normalizeForMatch(a.name).let { it.isNotEmpty() && q.contains(" $it ") } }
            val bestTrack = results.tracks.firstOrNull()
            when {
                namedArtist != null -> {
                    header("h-best", "Meilleur résultat")
                    item(key = "best") { BestResult(namedArtist.name, namedArtist.cover, "ARTISTE", round = true) { onOpenArtist(ArtistNav(spRef(namedArtist.id, namedArtist.name), namedArtist.name, namedArtist.cover)) } }
                }
                bestTrack != null -> {
                    header("h-best", "Meilleur résultat")
                    item(key = "best") {
                        BestResult(bestTrack.name, bestTrack.cover, "TITRE", subtitle = bestTrack.artists, explicit = bestTrack.isExplicit) {
                            onPlayTrack(bestTrack)
                        }
                    }
                }
            }
            if (results.tracks.isNotEmpty()) {
                header("h-tracks", "Titres", "Tout voir") { onTab(SearchTab.Tracks) }
                tracks(results.tracks.take(6), numbered = false)
            }
            if (results.albums.isNotEmpty()) {
                header("h-albums", "Albums")
                albums("albums", results.albums.take(8))
            }
            if (results.artists.size > 1) {
                header("h-artists", "Artistes")
                artists("artists", results.artists.take(8))
            }
            if (results.playlists.isNotEmpty()) {
                header("h-playlists", "Playlists")
                playlists("playlists", results.playlists.take(8))
            }
        }
        SearchTab.Tracks -> { item(key = "gap") { Spacer(Modifier.height(20.dp)) }; tracks(results.tracks, numbered = true) }
        SearchTab.Albums -> { item(key = "gap") { Spacer(Modifier.height(28.dp)) }; albums("albums", results.albums) }
        SearchTab.Artists -> { item(key = "gap") { Spacer(Modifier.height(28.dp)) }; artists("artists", results.artists) }
        SearchTab.Playlists -> { item(key = "gap") { Spacer(Modifier.height(28.dp)) }; playlists("playlists", results.playlists) }
    }
}

/** Minuscules sans accents ni ponctuation, pour reconnaître un nom dans la recherche. */
private fun normalizeForMatch(s: String): String =
    java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

/** Grande carte « Meilleur résultat » : photo (ronde pour un artiste), nom, étiquette ARTISTE ou TITRE. */
@Composable
private fun BestResult(
    title: String,
    image: String?,
    label: String,
    round: Boolean = false,
    subtitle: String? = null,
    explicit: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SpotyColors.Surface)
            .border(BorderStroke(1.dp, Color(0xFF262626)), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AsyncImage(
            model = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(120.dp).clip(if (round) CircleShape else RoundedCornerShape(8.dp)).background(Color(0xFF1F1F1F)),
        )
        Text(
            title,
            color = SpotyColors.TextPrimary,
            fontSize = 30.sp,
            lineHeight = 34.sp,
            fontFamily = SpotyFonts.Display,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
        if (explicit) { ExplicitTag(); Spacer(Modifier.width(8.dp)) }
        if (subtitle != null) {
            Text(subtitle, color = SpotyColors.TextSecondary, fontSize = 15.sp, fontFamily = SpotyFonts.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(12.dp))
        }
        Box(
            Modifier.height(30.dp).clip(CircleShape).border(1.dp, Color(0xFF333333), CircleShape).padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color(0xFFE5E5E5), fontSize = 12.sp, letterSpacing = 0.7.sp, fontFamily = SpotyFonts.Text, fontWeight = FontWeight.Bold)
        }
        }
    }
}

@Composable
private fun SuggestionsList(suggestions: List<String>, onSelect: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        itemsIndexed(suggestions) { _, item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(item) }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = SpotyColors.TextSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(16.dp))
                Text(item, color = SpotyColors.TextPrimary, fontSize = 15.sp, fontFamily = SpotyFonts.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Référence de l'API pour un identifiant Spotify ; sans identifiant, recherche par nom (artistes) ou par titre (albums). */
private fun spRef(id: String, name: String? = null): String = when {
    id.isNotBlank() -> "sp:${id.removePrefix("sp:")}"
    name != null -> "name:$name"
    else -> ""
}
