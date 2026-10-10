package com.spotywoop.kt.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import com.spotywoop.kt.data.AppUpdateManager
import com.spotywoop.kt.data.UpdateStatus
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
import android.widget.Toast
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotywoop.kt.data.ApiCard
import com.spotywoop.kt.data.DiscoverData
import com.spotywoop.kt.data.SpotiwoopApi
import com.spotywoop.kt.ui.theme.SpotyFonts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Genres de la page Découvrir du site (slugs Audiomack). */
val DISCOVER_GENRES = listOf(
    "" to "Tout",
    "afrobeats" to "Afrosounds",
    "rap" to "Hip-Hop/Rap",
    "rnb" to "R&B",
    "dancehall" to "Dancehall",
    "amapiano" to "Amapiano",
    "gospel" to "Gospel",
    "latin" to "Latin",
    "pop" to "Pop",
    "electronic" to "Electronic",
    "jazz" to "Jazz/Blues",
    "instrumental" to "Instrumental",
)

data class DiscoverUiState(
    val genre: String = "",
    val data: Map<String, DiscoverData> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Page Découvrir : tendances, Top Bénin, albums et nouveautés Audiomack, gardés par genre. */
class DiscoverViewModel : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    init { load("") }

    fun selectGenre(genre: String) {
        _state.update { it.copy(genre = genre) }
        if (genre !in _state.value.data) load(genre)
    }

    fun retry() = load(_state.value.genre)

    private fun load(genre: String) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val data = SpotiwoopApi.discover(genre)
                _state.update { it.copy(data = it.data + (genre to data), loading = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.genre == genre) _state.update { it.copy(loading = false, error = e.message ?: "Erreur réseau") }
            }
        }
    }

    /** Charge les titres d'un album puis les rend pour la lecture. */
    fun playCollection(card: ApiCard, onTracks: (List<TrackResult>) -> Unit, onError: () -> Unit) {
        viewModelScope.launch {
            val tracks = runCatching { SpotiwoopApi.collectionTracks(card.kind, card.ref) }.getOrNull()
            if (tracks.isNullOrEmpty()) onError() else onTracks(tracks)
        }
    }
}

/** Accueil : la page Découvrir du site web (version mobile). */
@Composable
fun HomeScreen(
    onPlayTracks: (List<TrackResult>, Int) -> Unit,
    onTrackOptions: (TrackResult) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    currentTrackId: String? = null,
    modifier: Modifier = Modifier,
    vm: DiscoverViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val updateStatus by AppUpdateManager.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val data = state.data[state.genre]
    var fullTop by remember { mutableStateOf(false) }
    val playCard: (ApiCard) -> Unit = { card ->
        Toast.makeText(context, "Chargement de « ${card.title} »…", Toast.LENGTH_SHORT).show()
        vm.playCollection(card, onTracks = { onPlayTracks(it, 0) }) {
            Toast.makeText(context, "Impossible de charger ce contenu.", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(SpotyColors.Background)
            .statusBarsPadding(),
    ) {
        WebTopBar(onClick = onOpenSearch, onOpenSettings = onOpenSettings)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 18.dp, bottom = 32.dp),
        ) {
        // Bannière dynamique de Mise à Jour (si disponible, prête ou en cours)
        when (val s = updateStatus) {
            is UpdateStatus.Available -> {
                item(key = "update_banner") { Box(Modifier.padding(bottom = 18.dp)) {
                    UpdateBannerCard(
                        title = "Mise à jour ${s.info.tagName} disponible",
                        subtitle = if (s.previousWasObsolete) "Version précédente obsolète • Nouvelle version prête" else "Nouvelles fonctionnalités & améliorations",
                        actionLabel = "Mettre à jour",
                        badgeText = if (s.previousWasObsolete) "NOUVEAU" else null,
                        onClick = onOpenSettings,
                    )
                } }
            }
            is UpdateStatus.ReadyToInstall -> {
                item(key = "update_banner") { Box(Modifier.padding(bottom = 18.dp)) {
                    UpdateBannerCard(
                        title = "Mise à jour ${s.info?.tagName ?: ""} prête",
                        subtitle = "Téléchargé • Dernière version",
                        actionLabel = "Installer",
                        badgeText = "PRÊT",
                        badgeColor = SpotyColors.SpotifyGreen,
                        onClick = { AppUpdateManager.launchInstaller(context, s.apkFile) },
                    )
                } }
            }
            is UpdateStatus.Downloading -> {
                item(key = "update_banner") { Box(Modifier.padding(bottom = 18.dp)) {
                    UpdateDownloadingCard(progress = s.progress)
                } }
            }
            else -> {}
        }

            item(key = "genres") {
                WebChips(DISCOVER_GENRES, state.genre, vm::selectGenre)
            }

            when {
                data == null && state.error != null -> item(key = "error") {
                    WebState(text = state.error!!, action = "Réessayer", onAction = vm::retry)
                }
                data == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator(color = SpotyColors.Accent)
                    }
                }
                else -> {
                    val trending = data.trendingSongs.take(12)
                    item(key = "h-trend") { SectionGap(); WebSectionHeader("Titres tendance"); Spacer(Modifier.height(16.dp)) }
                    webGrid(trending.withIndex().toList(), "trend") { (i, t), m ->
                        WebTrackCard(t.track, onClick = { onPlayTracks(trending.map { it.track }, i) }, modifier = m)
                    }

                    if (data.top.isNotEmpty()) {
                        val top = if (fullTop) data.top else data.top.take(5)
                        item(key = "h-top") {
                            SectionGap()
                            WebSectionHeader(
                                "Top Bénin · cette semaine",
                                link = if (data.top.size > 5) (if (fullTop) "Réduire" else "Classement") else null,
                                onLink = { fullTop = !fullTop },
                                leading = { BeninFlag() },
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        itemsIndexed(top, key = { i, t -> "top-$i-${t.track.id}" }) { i, t ->
                            WebTrackRow(
                                track = t.track,
                                number = "${if (t.rank > 0) t.rank else i + 1}",
                                rank = true,
                                change = t.change,
                                current = t.track.id == currentTrackId,
                                onClick = { onPlayTracks(data.top.map { it.track }, i) },
                                onOptions = { onTrackOptions(t.track) },
                            )
                        }
                    }

                    if (data.trendingAlbums.isNotEmpty()) {
                        item(key = "h-albums") { SectionGap(); WebSectionHeader("Albums tendance"); Spacer(Modifier.height(16.dp)) }
                        webGrid(data.trendingAlbums, "albums") { c, m ->
                            WebCollectionCard(
                                title = c.title,
                                image = c.image,
                                subtitle = c.subtitle,
                                meta = if (c.plays > 0) "${compactCount(c.plays)} écoutes" else null,
                                explicit = c.explicit,
                                stack = true,
                                onClick = { playCard(c) },
                                modifier = m,
                            )
                        }
                    }

                    if (data.recent.isNotEmpty()) {
                        item(key = "h-new") {
                            SectionGap()
                            WebSectionHeader("Nouveautés")
                            Spacer(Modifier.height(16.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(22.dp),
                            ) {
                                itemsIndexed(data.recent, key = { i, t -> "new-$i-${t.track.id}" }) { i, t ->
                                    WebTrackCard(t.track, onClick = { onPlayTracks(data.recent.map { it.track }, i) }, modifier = Modifier.width(168.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Espace entre deux sections, comme le site (30 px). */
@Composable
private fun SectionGap() = Spacer(Modifier.height(30.dp))

@Composable
private fun UpdateBannerCard(
    title: String,
    subtitle: String,
    actionLabel: String,
    badgeText: String? = null,
    badgeColor: Color = SpotyColors.Gold,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SpotyColors.SurfaceHigh)
            .border(BorderStroke(1.dp, badgeColor.copy(alpha = 0.5f)), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(badgeColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.SystemUpdate,
                contentDescription = null,
                tint = badgeColor,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badgeText != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = badgeText,
                        color = Color.Black,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(badgeColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = badgeColor),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.height(32.dp),
        ) {
            Text(
                text = actionLabel,
                color = Color.Black,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun UpdateDownloadingCard(progress: Float) {
    val pct = (progress * 100).toInt()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SpotyColors.SurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Téléchargement de la mise à jour...",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "$pct%",
                color = SpotyColors.SpotifyGreen,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = SpotyColors.SpotifyGreen,
            trackColor = Color.DarkGray,
        )
    }
}


