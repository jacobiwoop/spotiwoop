package com.spotywoop.kt.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.spotywoop.kt.R
import com.spotywoop.kt.data.TrackResult
import com.spotywoop.kt.ui.theme.SpotyColors
import com.spotywoop.kt.ui.theme.SpotyFonts
import java.util.Locale

// Briques visuelles reprises du site web (version mobile) : mêmes tailles, couleurs et polices.

private val Surface2 = Color(0xFF1F1F1F)
private val Text3 = Color(0xFF8A8A8A)

/**
 * Barre du haut du site : logo et champ de recherche arrondi. Sans [onQueryChange], le champ est
 * un simple bouton qui ouvre l'écran Recherche.
 */
@Composable
fun WebTopBar(
    query: String = "",
    onQueryChange: ((String) -> Unit)? = null,
    onSubmit: () -> Unit = {},
    onClick: () -> Unit = {},
    onOpenSettings: () -> Unit,
    loading: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_spotywoop_logo_wide),
                contentDescription = "Spotywoop",
                modifier = Modifier.height(38.dp).width(71.dp),
            )
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            Row(
                Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1A1A1A))
                    .border(1.dp, if (focused) SpotyColors.Accent else Color(0xFF2A2A2A), CircleShape)
                    .then(if (onQueryChange == null) Modifier.clickable(onClick = onClick) else Modifier)
                    .padding(start = 14.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = SpotyColors.TextSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("Artistes, titres, albums…", color = SpotyColors.TextSecondary, fontSize = 15.sp, fontFamily = SpotyFonts.Text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (onQueryChange != null) {
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = TextStyle(color = SpotyColors.TextPrimary, fontSize = 15.sp, fontFamily = SpotyFonts.Text, fontWeight = FontWeight.Medium),
                            cursorBrush = SolidColor(SpotyColors.Accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                            interactionSource = interaction,
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        )
                    }
                }
                when {
                    loading -> CircularProgressIndicator(color = SpotyColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    query.isNotEmpty() && onQueryChange != null -> Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Effacer",
                        tint = SpotyColors.TextSecondary,
                        modifier = Modifier.size(20.dp).clip(CircleShape).clickable { onQueryChange("") },
                    )
                }
            }
            // Le site n'a pas de réglages ; l'appli garde ici l'accès aux paramètres.
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Settings, contentDescription = "Paramètres", tint = SpotyColors.TextSecondary, modifier = Modifier.size(22.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF222222)))
    }
}

/** Puces de filtre défilantes : la puce active est verte. */
@Composable
fun WebChips(items: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.first }) { _, (value, label) ->
            val active = value == selected
            Box(
                Modifier
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(if (active) SpotyColors.Accent else Color.Transparent)
                    .border(1.dp, if (active) SpotyColors.Accent else Color(0xFF333333), CircleShape)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (active) Color(0xFF111111) else Color(0xFFE5E5E5), fontSize = 14.sp, fontFamily = SpotyFonts.Text, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** Titre de section en capitales, avec un lien à droite (« Tout voir »…). */
@Composable
fun WebSectionHeader(title: String, link: String? = null, onLink: () -> Unit = {}, leading: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let { it(); Spacer(Modifier.width(12.dp)) }
        Text(
            title.uppercase(Locale.FRENCH),
            color = SpotyColors.TextPrimary,
            fontSize = 19.sp,
            lineHeight = 24.sp,
            fontFamily = SpotyFonts.Display,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.2.sp,
            modifier = Modifier.weight(1f),
        )
        if (link != null) {
            Text(
                link.uppercase(Locale.FRENCH),
                color = SpotyColors.TextSecondary,
                fontSize = 13.sp,
                fontFamily = SpotyFonts.Display,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onLink).padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
fun ExplicitTag() {
    Box(
        Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(SpotyColors.Explicit),
        contentAlignment = Alignment.Center,
    ) {
        Text("E", color = Color.White, fontSize = 10.sp, lineHeight = 10.sp, fontFamily = SpotyFonts.Text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BeninFlag(modifier: Modifier = Modifier.width(30.dp).height(20.dp)) {
    Row(modifier.clip(RoundedCornerShape(3.dp))) {
        Box(Modifier.fillMaxHeight().weight(0.4f).background(Color(0xFF008751)))
        Column(Modifier.fillMaxHeight().weight(0.6f)) {
            Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFFCD116)))
            Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFE8112D)))
        }
    }
}

/** Nombre court à la française : 40,8 M, 90,3 k, 20 M. */
fun compactCount(n: Long): String {
    fun fmt(v: Double, unit: String) = String.format(Locale.FRENCH, "%.1f", v).removeSuffix(",0") + " " + unit
    return when {
        n >= 1_000_000_000 -> fmt(n / 1e9, "Md")
        n >= 1_000_000 -> fmt(n / 1e6, "M")
        n >= 1_000 -> fmt(n / 1e3, "k")
        else -> n.toString()
    }
}

@Composable
private fun CardTitle(title: String, explicit: Boolean, center: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (center) Arrangement.Center else Arrangement.Start,
    ) {
        Text(
            title,
            color = SpotyColors.TextPrimary,
            fontSize = 16.sp,
            fontFamily = SpotyFonts.Display,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (explicit) {
            Spacer(Modifier.width(6.dp))
            ExplicitTag()
        }
    }
}

@Composable
private fun CardSub(text: String) {
    Text(text, color = SpotyColors.TextSecondary, fontSize = 14.sp, fontFamily = SpotyFonts.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Vignette de titre : pochette, artiste au-dessus du titre (comme « Titres tendance »). */
@Composable
fun WebTrackCard(track: TrackResult, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AsyncImage(
            model = track.cover,
            contentDescription = track.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Surface2),
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            CardSub(track.artists)
            CardTitle(track.name, track.isExplicit)
        }
    }
}

/**
 * Vignette d'album, de playlist ou d'artiste. [stack] dessine la pile de disques des albums,
 * [round] la photo ronde des artistes.
 */
@Composable
fun WebCollectionCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    meta: String? = null,
    explicit: Boolean = false,
    stack: Boolean = false,
    round: Boolean = false,
) {
    val shape = if (round) CircleShape else RoundedCornerShape(8.dp)
    Column(modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            if (stack) {
                Box(Modifier.fillMaxSize().padding(2.dp).offset(x = 6.dp, y = (-6).dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2A)))
            }
            AsyncImage(
                model = image,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape).background(Surface2),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (subtitle != null && !round) CardSub(subtitle)
            CardTitle(title, explicit, center = round)
            if (meta != null) Text(meta, color = Text3, fontSize = 13.sp, fontFamily = SpotyFonts.Text, maxLines = 1)
        }
    }
}

/** Grille à deux colonnes dans une LazyColumn (la grille du site sur mobile). */
fun <T> LazyListScope.webGrid(items: List<T>, key: String, content: @Composable (T, Modifier) -> Unit) {
    val rows = items.chunked(2)
    rows.forEachIndexed { i, row ->
        item(key = "$key-$i") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = if (i < rows.lastIndex) 22.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                row.forEach { content(it, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Évolution dans un classement : flèche verte ou rouge et nombre de places. */
@Composable
private fun Trend(change: String) {
    val n = change.removePrefix("+").toIntOrNull() ?: return
    if (n == 0) return
    val up = n > 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (up) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
            contentDescription = if (up) "Monte de $n" else "Descend de ${-n}",
            tint = if (up) SpotyColors.Up else SpotyColors.Down,
            modifier = Modifier.size(12.dp),
        )
        Text("${kotlin.math.abs(n)}", color = if (up) SpotyColors.Up else SpotyColors.Down, fontSize = 12.sp, fontFamily = SpotyFonts.Text, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
    }
}

/**
 * Ligne de titre du site : numéro ou rang, pochette, titre et artiste, menu ⋮. Le titre en cours de
 * lecture est surligné en vert.
 */
@Composable
fun WebTrackRow(
    track: TrackResult,
    number: String?,
    current: Boolean,
    onClick: () -> Unit,
    onOptions: () -> Unit,
    rank: Boolean = false,
    change: String = "",
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (current) Color(0xFF16200C) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text(
                number,
                color = if (rank) SpotyColors.TextPrimary else Text3,
                fontSize = if (rank) 19.sp else 15.sp,
                fontFamily = SpotyFonts.Display,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                modifier = Modifier.width(26.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        AsyncImage(
            model = track.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)).background(Surface2),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    track.name,
                    color = if (current) SpotyColors.Accent else SpotyColors.TextPrimary,
                    fontSize = 15.sp,
                    fontFamily = SpotyFonts.Display,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (track.isExplicit) {
                    Spacer(Modifier.width(6.dp))
                    ExplicitTag()
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rank) Trend(change)
                Text(track.artists, color = SpotyColors.TextSecondary, fontSize = 13.sp, fontFamily = SpotyFonts.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onOptions, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Plus d'options pour ${track.name}", tint = SpotyColors.TextPrimary, modifier = Modifier.size(20.dp))
        }
    }
}

/** Message centré (état vide, erreur) avec un bouton facultatif. */
@Composable
fun WebState(title: String? = null, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) {
            Text(title, color = Color(0xFFBDBDBD), fontSize = 30.sp, fontFamily = SpotyFonts.Display, fontWeight = FontWeight.ExtraBold)
        }
        Text(text, color = SpotyColors.TextSecondary, fontSize = 15.sp, lineHeight = 22.sp, fontFamily = SpotyFonts.Text, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.clip(CircleShape).border(BorderStroke(1.dp, Color(0xFF444444)), CircleShape).clickable(onClick = onAction).padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(action, color = SpotyColors.TextPrimary, fontSize = 14.sp, fontFamily = SpotyFonts.Text, fontWeight = FontWeight.Bold)
            }
        }
    }
}
