package com.spotywoop.kt.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.spotywoop.kt.data.ApiCard
import com.spotywoop.kt.data.ArtistPage
import com.spotywoop.kt.data.SpotiwoopApi
import com.spotywoop.kt.data.TrackResult
import com.spotywoop.kt.ui.theme.SpotyColors
import com.spotywoop.kt.ui.theme.SpotyFonts
import kotlinx.coroutines.launch
import java.util.Locale

/** Artiste à ouvrir : [ref] vaut "sp:<id>", ou "name:<nom>" quand on ne connaît que le nom. */
data class ArtistNav(val ref: String, val name: String, val image: String? = null)

/**
 * Page artiste, comme sur le site : bannière, auditeurs mensuels et abonnés, Écouter et Radio de
 * l'artiste, titres populaires, albums, singles et EP, artistes similaires (Audiomack), à propos,
 * villes où on l'écoute.
 */
@Composable
fun ArtistScreen(
    nav: ArtistNav,
    currentTrackId: String?,
    onBack: () -> Unit,
    onPlayTracks: (List<TrackResult>, Int) -> Unit,
    onTrackOptions: (TrackResult) -> Unit,
    onOpenCollection: (CollectionNav) -> Unit,
    onOpenArtist: (ArtistNav) -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember(nav) { mutableStateOf<ArtistPage?>(null) }
    var error by remember(nav) { mutableStateOf<String?>(null) }
    var attempt by remember(nav) { mutableIntStateOf(0) }
    // Sections déployées par « Tout voir » (titres, albums, singles, artistes).
    var expanded by remember(nav) { mutableStateOf(setOf<String>()) }
    fun toggle(section: String) { expanded = if (section in expanded) expanded - section else expanded + section }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(nav, attempt) {
        error = null
        try {
            page = SpotiwoopApi.artist(nav.ref)
        } catch (e: Exception) {
            error = "Chargement impossible. Vérifie ta connexion."
        }
    }

    fun startRadio(name: String) {
        Toast.makeText(context, "Radio de $name…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val tracks = runCatching { SpotiwoopApi.artistRadio(name) }.getOrDefault(emptyList())
            if (tracks.isEmpty()) Toast.makeText(context, "Radio indisponible pour le moment", Toast.LENGTH_SHORT).show()
            else onPlayTracks(tracks, 0)
        }
    }

    Box(modifier.fillMaxSize().background(SpotyColors.Background)) {
        val p = page
        when {
            p != null -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 140.dp)) {
                item(key = "hero") { ArtistHero(p, nav.image) }
                item(key = "actions") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PillButton("Écouter", Icons.Rounded.PlayArrow, primary = true, enabled = p.topTracks.isNotEmpty()) { onPlayTracks(p.topTracks, 0) }
                        PillButton("Radio de l'artiste", Icons.Rounded.Radio, primary = false) { startRadio(p.name) }
                    }
                }
                if (p.topTracks.isNotEmpty()) {
                    val open = "pop" in expanded
                    header("h-pop", "Titres populaires", seeAll(p.topTracks.size > POPULAR_SHORT, open)) { toggle("pop") }
                    val shown = if (open) p.topTracks else p.topTracks.take(POPULAR_SHORT)
                    itemsIndexed(shown, key = { i, t -> "pop-$i-${t.id}" }) { i, t ->
                        WebTrackRow(
                            track = t,
                            number = "${i + 1}",
                            current = t.id == currentTrackId,
                            onClick = { onPlayTracks(p.topTracks, i) },
                            onOptions = { onTrackOptions(t) },
                        )
                    }
                }
                if (p.albums.isNotEmpty()) {
                    val open = "albums" in expanded
                    header("h-albums", "Albums", seeAll(p.albums.size > 2, open)) { toggle("albums") }
                    if (open) webGrid(p.albums, "alb") { c, m -> AlbumCard(c, m, onOpenCollection) }
                    else cardRow("albums", p.albums) { c, m -> AlbumCard(c, m, onOpenCollection) }
                }
                if (p.singles.isNotEmpty()) {
                    val open = "singles" in expanded
                    header("h-singles", "Singles et EP", seeAll(p.singles.size > 2, open)) { toggle("singles") }
                    // Rangée défilante ; « Tout voir » la déplie en grille complète.
                    if (open) webGrid(p.singles, "sgl") { c, m -> AlbumCard(c, m, onOpenCollection) }
                    else cardRow("singles", p.singles) { c, m -> AlbumCard(c, m, onOpenCollection) }
                }
                if (p.related.isNotEmpty()) {
                    val open = "related" in expanded
                    header("h-related", "Artistes similaires", seeAll(p.related.size > 2, open)) { toggle("related") }
                    val artistCard: @Composable (ApiCard, Modifier) -> Unit = { c, m ->
                        WebCollectionCard(
                            title = c.title, image = c.image, round = true, modifier = m,
                            onClick = { onOpenArtist(ArtistNav(c.ref, c.title, c.image)) },
                        )
                    }
                    if (open) webGrid(p.related, "rel", artistCard) else cardRow("related", p.related.take(12), artistCard)
                }
                if (p.bio != null || p.links.isNotEmpty()) {
                    header("h-about", "À propos")
                    item(key = "about") { About(p) }
                }
                if (p.topCities.isNotEmpty()) {
                    header("h-cities", "Où on l'écoute")
                    item(key = "cities") {
                        Panel {
                            p.topCities.forEachIndexed { i, c ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${i + 1}.", color = PageText3, fontSize = 15.sp, fontFamily = SpotyFonts.Text, modifier = Modifier.width(26.dp))
                                    flagEmoji(c.country)?.let { Text(it, fontSize = 17.sp, modifier = Modifier.padding(end = 8.dp)) }
                                    Text(c.city, color = SpotyColors.TextPrimary, fontSize = 15.sp, fontFamily = SpotyFonts.Text, fontWeight = FontWeight.Bold)
                                    Text(
                                        "  · ${fullCount(c.listeners)} auditeurs",
                                        color = PageText3, fontSize = 13.sp, fontFamily = SpotyFonts.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
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
private fun ArtistHero(p: ArtistPage, fallbackImage: String?) {
    Box(Modifier.fillMaxWidth().height(330.dp)) {
        AsyncImage(
            model = p.banner ?: p.image ?: fallbackImage,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().background(PageSurface),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x33000000), 0.45f to Color(0x55121212), 1f to SpotyColors.Background),
            ),
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (p.banner != null && p.image != null) {
                AsyncImage(
                    model = p.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(84.dp).clip(CircleShape).border(BorderStroke(2.dp, Color(0x33FFFFFF)), CircleShape),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Kicker("Artiste")
                Text(
                    p.name, color = Color.White, fontSize = 34.sp, lineHeight = 38.sp,
                    fontFamily = SpotyFonts.Display, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val stats = listOfNotNull(
                    p.monthlyListeners.takeIf { it > 0 }?.let { "${fullCount(it)} auditeurs mensuels" },
                    p.followers.takeIf { it > 0 }?.let { "${fullCount(it)} abonnés" },
                )
                if (stats.isNotEmpty()) Text(stats.joinToString(" · "), color = Color(0xFFE5E5E5), fontSize = 13.sp, fontFamily = SpotyFonts.Text)
            }
        }
    }
}

@Composable
private fun About(p: ArtistPage) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Panel {
        p.bio?.let { bio ->
            Text(
                bio, color = Color(0xFFD4D4D4), fontSize = 14.sp, lineHeight = 22.sp, fontFamily = SpotyFonts.Text,
                maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis,
            )
            if (bio.length > 300) {
                Text(
                    if (expanded) "Moins" else "Lire la suite", color = SpotyColors.TextPrimary, fontSize = 14.sp,
                    fontFamily = SpotyFonts.Text, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp),
                )
            }
        }
        if (p.links.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                p.links.forEach { (name, url) ->
                    Text(
                        name.lowercase(Locale.FRENCH).replaceFirstChar { it.titlecase(Locale.FRENCH) },
                        color = SpotyColors.TextPrimary, fontSize = 13.sp, fontFamily = SpotyFonts.Text, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF2A2A2A))
                            .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(PageSurface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun AlbumCard(c: ApiCard, m: Modifier, onOpenCollection: (CollectionNav) -> Unit) {
    WebCollectionCard(
        title = c.title, image = c.image, subtitle = c.subtitle, explicit = c.explicit, stack = true, modifier = m,
        onClick = { onOpenCollection(CollectionNav(c.kind.ifBlank { "album" }, c.ref, c.title, c.image)) },
    )
}

private const val POPULAR_SHORT = 5

/** Libellé du lien à droite du titre de section : « Tout voir », « Moins », ou rien si tout est déjà affiché. */
private fun seeAll(more: Boolean, open: Boolean): String? = when {
    open -> "Moins"
    more -> "Tout voir"
    else -> null
}

private fun LazyListScope.header(key: String, title: String, link: String? = null, onLink: () -> Unit = {}) = item(key = key) {
    Spacer(Modifier.height(30.dp))
    WebSectionHeader(title, link, onLink)
    Spacer(Modifier.height(14.dp))
}

/** Rangée défilante de vignettes (singles, artistes similaires). */
private fun LazyListScope.cardRow(key: String, cards: List<ApiCard>, content: @Composable (ApiCard, Modifier) -> Unit) = item(key = key) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(cards, key = { it.ref }) { content(it, Modifier.width(150.dp)) }
    }
}

/** Drapeau emoji d'un code pays ISO à deux lettres (« NG » → 🇳🇬), null si le code est invalide. */
private fun flagEmoji(code: String): String? {
    if (code.length != 2 || !code.all { it.isLetter() }) return null
    return code.uppercase(Locale.ROOT).map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}
