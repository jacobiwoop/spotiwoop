package com.spotywoop.kt.ui

import android.net.ConnectivityManager
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.spotywoop.kt.data.CollectionPage
import com.spotywoop.kt.data.DownloadManager
import com.spotywoop.kt.data.LocalLibraryStore
import com.spotywoop.kt.data.SavedCollection
import com.spotywoop.kt.data.SpotiwoopApi
import com.spotywoop.kt.data.TrackResult
import com.spotywoop.kt.ui.theme.SpotyColors
import com.spotywoop.kt.ui.theme.SpotyFonts
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Album ou playlist à ouvrir : [ref] vide quand on ne connaît que le nom (menu ⋮ d'un titre). */
data class CollectionNav(val kind: String, val ref: String, val title: String, val image: String? = null, val artist: String = "")

/**
 * Page album ou playlist, comme sur le site : pochette, date, nombre de titres et durée, écoutes,
 * boutons Écouter et Aléatoire, tous les titres dans l'ordre, maison de disques.
 */
@Composable
fun AlbumScreen(
    nav: CollectionNav,
    currentTrackId: String?,
    onBack: () -> Unit,
    onPlayTracks: (List<TrackResult>, Int) -> Unit,
    onTrackOptions: (TrackResult) -> Unit,
    onOpenArtist: (ArtistNav) -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember(nav) { mutableStateOf<CollectionPage?>(null) }
    var error by remember(nav) { mutableStateOf<String?>(null) }
    var attempt by remember(nav) { mutableIntStateOf(0) }
    val collections by DownloadManager.collections.collectAsStateWithLifecycle()
    val saved = remember(collections, nav) { DownloadManager.findCollection(nav.kind, nav.ref, nav.title) }

    LaunchedEffect(nav, attempt) {
        error = null
        try {
            val ref = nav.ref.ifBlank { SpotiwoopApi.findAlbumRef(nav.title, nav.artist) ?: throw NoSuchElementException() }
            page = SpotiwoopApi.collection(nav.kind, ref)
        } catch (e: NoSuchElementException) {
            error = "Album introuvable."
        } catch (e: Exception) {
            error = "Chargement impossible. Vérifie ta connexion."
        }
    }

    val kicker = if (nav.kind == "album") "Album" else "Playlist"
    val p = page
    when {
        p != null -> CollectionLayout(
            header = CollectionHeader(
                kicker = kicker,
                title = p.title,
                image = p.image,
                description = p.description,
                subtitle = p.subtitle,
                onSubtitle = p.subtitle?.let { name ->
                    (p.subtitleRef ?: if (p.kind == "album") "name:$name" else null)?.let { ref -> { onOpenArtist(ArtistNav(ref, name)) } }
                },
                meta = listOfNotNull(
                    p.date?.let(::frenchDate),
                    "${p.totalTracks.takeIf { it > 0 } ?: p.tracks.size} titres" + (totalDuration(p.tracks)?.let { ", $it" } ?: ""),
                    p.plays.takeIf { it > 0 }?.let { "${compactCount(it)} écoutes" },
                    p.followers.takeIf { it > 0 }?.let { "${fullCount(it)} abonnés" },
                ).joinToString("  •  "),
            ),
            tracks = p.tracks,
            collection = (saved?.ref ?: nav.ref.ifBlank { p.ref }).let { ref ->
                SavedCollection("${p.kind}:$ref", p.kind, ref, p.title, p.subtitle, p.image, p.tracks)
            },
            currentTrackId = currentTrackId,
            onBack = onBack,
            onPlayTracks = onPlayTracks,
            onTrackOptions = onTrackOptions,
            footer = listOfNotNull(
                if (p.totalTracks > p.tracks.size) "${p.tracks.size} titres affichés sur ${p.totalTracks}." else null,
                listOfNotNull(p.label, p.copyright).joinToString(" · ").ifBlank { null },
            ).joinToString("\n").ifBlank { null },
        )
        // Hors connexion : un album téléchargé s'ouvre sans le réseau.
        saved != null -> CollectionLayout(
            header = CollectionHeader(
                kicker = kicker,
                title = saved.title,
                image = saved.image,
                subtitle = saved.subtitle,
                meta = titresLabel(saved.tracks.size) + (totalDuration(saved.tracks)?.let { ", $it" } ?: ""),
            ),
            tracks = saved.tracks,
            collection = saved,
            currentTrackId = currentTrackId,
            onBack = onBack,
            onPlayTracks = onPlayTracks,
            onTrackOptions = onTrackOptions,
        )
        else -> Box(modifier.fillMaxSize().background(SpotyColors.Background)) {
            if (error != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    WebState(text = error!!, action = "Réessayer", onAction = { attempt++ })
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SpotyColors.Accent)
                }
            }
            BackButton(onBack)
        }
    }
}

/** Titres likés ([key] « liked ») ou playlist de l'utilisateur (« user:id »). */
data class UserCollectionNav(val key: String)

@Composable
fun UserCollectionScreen(
    nav: UserCollectionNav,
    currentTrackId: String?,
    onBack: () -> Unit,
    onPlayTracks: (List<TrackResult>, Int) -> Unit,
    onTrackOptions: (TrackResult) -> Unit,
) {
    val liked by LocalLibraryStore.likedTracks.collectAsStateWithLifecycle()
    val playlists by LocalLibraryStore.playlists.collectAsStateWithLifecycle()
    val isLiked = nav.key == "liked"
    val playlist = playlists.find { "user:${it.id}" == nav.key }
    if (!isLiked && playlist == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val tracks = if (isLiked) liked else playlist!!.tracks
    val title = if (isLiked) "Titres likés" else playlist!!.name
    val image = if (isLiked) null else playlist!!.cover
    var confirmDelete by remember { mutableStateOf(false) }
    CollectionLayout(
        header = CollectionHeader(
            kicker = "Playlist",
            title = title,
            image = image,
            liked = isLiked,
            meta = titresLabel(tracks.size) + (totalDuration(tracks)?.let { ", $it" } ?: ""),
        ),
        tracks = tracks,
        collection = SavedCollection(nav.key, if (isLiked) "liked" else "user", nav.key, title, null, image ?: tracks.firstOrNull()?.cover, tracks),
        currentTrackId = currentTrackId,
        onBack = onBack,
        onPlayTracks = onPlayTracks,
        onTrackOptions = onTrackOptions,
        emptyText = "Aucun titre dans cette liste.",
        onDeletePage = if (isLiked) null else ({ confirmDelete = true }),
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Color(0xFF282828),
            title = { Text("Supprimer « $title » ?", color = Color.White) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    playlist?.let { LocalLibraryStore.deletePlaylist(it.id) }
                }) { Text("Supprimer", color = SpotyColors.Down) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler", color = Color.White) } },
        )
    }
}

data class CollectionHeader(
    val kicker: String,
    val title: String,
    val meta: String,
    val image: Any? = null,
    val liked: Boolean = false,
    val description: String? = null,
    val subtitle: String? = null,
    val onSubtitle: (() -> Unit)? = null,
)

/** La page commune des albums, playlists et titres likés : pochette, Lecture, Aléatoire, Télécharger, titres. */
@Composable
fun CollectionLayout(
    header: CollectionHeader,
    tracks: List<TrackResult>,
    collection: SavedCollection,
    currentTrackId: String?,
    onBack: () -> Unit,
    onPlayTracks: (List<TrackResult>, Int) -> Unit,
    onTrackOptions: (TrackResult) -> Unit,
    footer: String? = null,
    emptyText: String? = null,
    onDeletePage: (() -> Unit)? = null,
) {
    val downloads by DownloadManager.downloads.collectAsStateWithLifecycle()
    val downloading by DownloadManager.downloadingIds.collectAsStateWithLifecycle()
    val failed by DownloadManager.failedIds.collectAsStateWithLifecycle()
    val byId = remember(downloads) { downloads.associateBy { it.track.id } }

    Box(Modifier.fillMaxSize().background(SpotyColors.Background)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 140.dp)) {
            item(key = "hero") {
                Column(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HeroCover(header, tracks.firstOrNull()?.let { byId[it.id]?.localCoverPath }?.let { java.io.File(it) })
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Kicker(header.kicker)
                        Text(
                            header.title, color = SpotyColors.TextPrimary, fontSize = 30.sp, lineHeight = 34.sp,
                            fontFamily = SpotyFonts.Display, fontWeight = FontWeight.ExtraBold,
                        )
                        header.description?.let { Text(it, color = SpotyColors.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = SpotyFonts.Text) }
                        header.subtitle?.let { name ->
                            Text(
                                name, color = SpotyColors.TextPrimary, fontSize = 15.sp, fontFamily = SpotyFonts.Text, fontWeight = FontWeight.Bold,
                                modifier = header.onSubtitle?.let { Modifier.clickable(onClick = it) } ?: Modifier,
                            )
                        }
                        Text(header.meta, color = SpotyColors.TextSecondary, fontSize = 13.sp, fontFamily = SpotyFonts.Text)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Lecture", Icons.Rounded.PlayArrow, primary = true, enabled = tracks.isNotEmpty(), modifier = Modifier.weight(1f)) { onPlayTracks(tracks, 0) }
                        PillButton("Aléatoire", Icons.Rounded.Shuffle, primary = false, enabled = tracks.isNotEmpty(), modifier = Modifier.weight(1f)) { onPlayTracks(tracks.shuffled(), 0) }
                    }
                    CollectionDownloadButton(collection, tracks, byId.keys, downloading, failed)
                }
            }
            if (tracks.isEmpty() && emptyText != null) {
                item(key = "empty") { WebState(text = emptyText) }
            }
            itemsIndexed(tracks, key = { i, t -> "t-$i-${t.id}" }) { i, t ->
                WebTrackRow(
                    track = t,
                    number = "${i + 1}",
                    current = t.id == currentTrackId,
                    onClick = { onPlayTracks(tracks, i) },
                    onOptions = { onTrackOptions(t) },
                    coverModel = byId[t.id]?.localCoverPath?.let { java.io.File(it) } ?: t.cover,
                    offline = t.id in byId,
                    failed = t.id in failed,
                    downloading = t.id in downloading,
                )
            }
            if (footer != null) {
                item(key = "footer") {
                    Text(
                        footer, color = PageText3, fontSize = 13.sp, lineHeight = 18.sp, fontFamily = SpotyFonts.Text,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                    )
                }
            }
        }
        BackButton(onBack)
        if (onDeletePage != null) {
            IconButton(
                onClick = onDeletePage,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).size(40.dp).clip(CircleShape).background(Color(0x99000000)),
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Supprimer la playlist", tint = Color.White)
            }
        }
    }
}

@Composable
private fun HeroCover(header: CollectionHeader, localCover: java.io.File?) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(230.dp).shadow(16.dp, shape).clip(shape).then(
            if (header.liked) Modifier.background(Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFFC4EFD9)))) else Modifier.background(PageSurface),
        ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            header.liked -> Icon(Icons.Filled.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(92.dp))
            else -> {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = SpotyColors.TextMuted, modifier = Modifier.size(72.dp))
                (localCover ?: header.image)?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                }
            }
        }
    }
}

/** Le bouton Télécharger : compteur pendant le téléchargement, reprise après un échec, suppression une fois complet. */
@Composable
private fun CollectionDownloadButton(
    collection: SavedCollection,
    tracks: List<TrackResult>,
    downloaded: Set<String>,
    downloading: Set<String>,
    failed: Set<String>,
) {
    val context = LocalContext.current
    val saved by DownloadManager.collections.collectAsStateWithLifecycle()
    val isSaved = saved.any { it.key == collection.key }
    val total = tracks.size
    val done = tracks.count { it.id in downloaded }
    val running = tracks.any { it.id in downloading }
    val hasFailed = tracks.any { it.id in failed }
    val complete = total > 0 && done == total
    var askMobile by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    val start = {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (total - done > 5 && cm?.isActiveNetworkMetered == true) askMobile = true else DownloadManager.downloadCollection(collection)
    }
    val (label, icon, color) = when {
        total == 0 -> Triple("Télécharger", Icons.Rounded.Download, SpotyColors.TextSecondary)
        running -> Triple("$done/$total — Téléchargement…", Icons.Rounded.Download, SpotyColors.TextPrimary)
        hasFailed -> Triple("$done/$total — Réessayer", Icons.Rounded.ErrorOutline, SpotyColors.Down)
        complete && isSaved -> Triple("Téléchargé", Icons.Rounded.DownloadDone, SpotyColors.SpotifyGreen)
        complete -> Triple("Tous les titres sont téléchargés", Icons.Rounded.DownloadDone, SpotyColors.SpotifyGreen)
        isSaved -> Triple("$done/$total — Récupérer", Icons.Rounded.Download, SpotyColors.TextPrimary)
        done > 0 -> Triple("Télécharger les ${total - done} restants", Icons.Rounded.Download, SpotyColors.TextPrimary)
        else -> Triple("Télécharger", Icons.Rounded.Download, SpotyColors.TextPrimary)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(CircleShape)
            .border(1.dp, if (hasFailed && !running) SpotyColors.Down else Color(0xFF555555), CircleShape)
            .clickable(enabled = total > 0 && !running) {
                when {
                    complete && isSaved -> askDelete = true
                    complete -> DownloadManager.downloadCollection(collection)
                    else -> start()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (running && total > 0) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(done.toFloat() / total).align(Alignment.CenterStart).background(Color(0xFF26380F)))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(label, color = color, fontSize = 15.sp, fontFamily = SpotyFonts.Text, fontWeight = FontWeight.Bold)
        }
    }
    if (askMobile) {
        AlertDialog(
            onDismissRequest = { askMobile = false },
            containerColor = Color(0xFF282828),
            title = { Text("Données mobiles", color = Color.White) },
            text = { Text("Télécharger ${total - done} titres sans Wi-Fi ?", color = Color.White.copy(alpha = 0.85f)) },
            confirmButton = {
                TextButton(onClick = { askMobile = false; DownloadManager.downloadCollection(collection) }) { Text("Télécharger", color = SpotyColors.Accent) }
            },
            dismissButton = { TextButton(onClick = { askMobile = false }) { Text("Annuler", color = Color.White) } },
        )
    }
    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            containerColor = Color(0xFF282828),
            title = { Text("Supprimer le téléchargement ?", color = Color.White) },
            text = { Text("Les titres de « ${collection.title} » seront retirés du téléphone.", color = Color.White.copy(alpha = 0.85f)) },
            confirmButton = {
                TextButton(onClick = { askDelete = false; DownloadManager.deleteCollection(collection.key) }) { Text("Supprimer", color = SpotyColors.Down) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Annuler", color = Color.White) } },
        )
    }
}

// ---------- Éléments communs aux pages album et artiste ----------

internal val PageSurface = Color(0xFF1E1E1E)
internal val PageText3 = Color(0xFF8A8A8A)

@Composable
internal fun Kicker(text: String) {
    Text(
        text.uppercase(Locale.FRENCH), color = SpotyColors.TextSecondary, fontSize = 12.sp,
        fontFamily = SpotyFonts.Display, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
    )
}

/** Bouton rond du site : vert plein (« Écouter ») ou contour (« Aléatoire », « Radio de l'artiste »). */
@Composable
internal fun PillButton(label: String, icon: ImageVector, primary: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = CircleShape
    Row(
        modifier
            .clip(shape)
            .then(if (primary) Modifier.background(SpotyColors.Accent) else Modifier.border(BorderStroke(1.dp, Color(0xFF555555)), shape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val color = if (primary) Color(0xFF111111) else SpotyColors.TextPrimary
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(label, color = color, fontSize = 15.sp, fontFamily = SpotyFonts.Text, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun BackButton(onBack: () -> Unit) {
    IconButton(
        onClick = onBack,
        modifier = Modifier.statusBarsPadding().padding(8.dp).size(40.dp).clip(CircleShape).background(Color(0x99000000)),
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour", tint = Color.White)
    }
}

/** Nombre complet à la française : 8 283 759. */
internal fun fullCount(n: Long): String = NumberFormat.getIntegerInstance(Locale.FRENCH).format(n)

/** « 2026-04-03 » → « 3 avril 2026 » ; une année seule reste telle quelle. */
private fun frenchDate(s: String): String = runCatching {
    LocalDate.parse(s.take(10)).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH))
}.getOrDefault(s.take(4))

/** Durée totale : « 42 min » ou « 1 h 12 min ». */
private fun totalDuration(tracks: List<TrackResult>): String? {
    val min = (tracks.sumOf { it.durationMs } / 60_000).toInt()
    if (min <= 0) return null
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}
