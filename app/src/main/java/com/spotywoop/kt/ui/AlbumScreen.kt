package com.spotywoop.kt.ui

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.spotywoop.kt.data.CollectionPage
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

    Box(modifier.fillMaxSize().background(SpotyColors.Background)) {
        val p = page
        when {
            p != null -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 140.dp)) {
                item(key = "hero") { CollectionHero(p, onPlayTracks, onOpenArtist) }
                itemsIndexed(p.tracks, key = { i, t -> "t-$i-${t.id}" }) { i, t ->
                    WebTrackRow(
                        track = t,
                        number = "${i + 1}",
                        current = t.id == currentTrackId,
                        onClick = { onPlayTracks(p.tracks, i) },
                        onOptions = { onTrackOptions(t) },
                    )
                }
                if (p.totalTracks > p.tracks.size) {
                    item(key = "partial") {
                        Text(
                            "${p.tracks.size} titres affichés sur ${p.totalTracks}.",
                            color = PageText3, fontSize = 13.sp, fontFamily = SpotyFonts.Text, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        )
                    }
                }
                val footer = listOfNotNull(p.label, p.copyright).joinToString(" · ")
                if (footer.isNotBlank()) {
                    item(key = "label") {
                        Text(
                            footer, color = PageText3, fontSize = 13.sp, lineHeight = 18.sp, fontFamily = SpotyFonts.Text,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                        )
                    }
                }
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                WebState(text = error!!, action = "Réessayer", onAction = { attempt++ })
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SpotyColors.Accent)
            }
        }
        BackButton(onBack)
    }
}

@Composable
private fun CollectionHero(p: CollectionPage, onPlayTracks: (List<TrackResult>, Int) -> Unit, onOpenArtist: (ArtistNav) -> Unit) {
    Column(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AsyncImage(
            model = p.image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(230.dp).shadow(16.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(PageSurface),
        )
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Kicker(if (p.kind == "album") "Album" else "Playlist")
            Text(
                p.title, color = SpotyColors.TextPrimary, fontSize = 30.sp, lineHeight = 34.sp,
                fontFamily = SpotyFonts.Display, fontWeight = FontWeight.ExtraBold,
            )
            p.description?.let { Text(it, color = SpotyColors.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = SpotyFonts.Text) }
            p.subtitle?.let { name ->
                val ref = p.subtitleRef ?: if (p.kind == "album") "name:$name" else null
                Text(
                    name, color = SpotyColors.TextPrimary, fontSize = 15.sp, fontFamily = SpotyFonts.Text, fontWeight = FontWeight.Bold,
                    modifier = if (ref != null) Modifier.clickable { onOpenArtist(ArtistNav(ref, name)) } else Modifier,
                )
            }
            val meta = listOfNotNull(
                p.date?.let(::frenchDate),
                "${p.totalTracks.takeIf { it > 0 } ?: p.tracks.size} titres" + (totalDuration(p.tracks)?.let { ", $it" } ?: ""),
                p.plays.takeIf { it > 0 }?.let { "${compactCount(it)} écoutes" },
                p.followers.takeIf { it > 0 }?.let { "${fullCount(it)} abonnés" },
            )
            Text(meta.joinToString("  •  "), color = SpotyColors.TextSecondary, fontSize = 13.sp, fontFamily = SpotyFonts.Text)
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Écouter", Icons.Rounded.PlayArrow, primary = true, enabled = p.tracks.isNotEmpty()) { onPlayTracks(p.tracks, 0) }
            PillButton("Aléatoire", Icons.Rounded.Shuffle, primary = false, enabled = p.tracks.isNotEmpty()) { onPlayTracks(p.tracks.shuffled(), 0) }
        }
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
internal fun PillButton(label: String, icon: ImageVector, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = CircleShape
    Row(
        Modifier
            .clip(shape)
            .then(if (primary) Modifier.background(SpotyColors.Accent) else Modifier.border(BorderStroke(1.dp, Color(0xFF555555)), shape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
