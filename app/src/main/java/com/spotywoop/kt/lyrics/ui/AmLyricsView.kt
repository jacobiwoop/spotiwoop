package com.spotywoop.kt.lyrics.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.PaddingValues
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotywoop.kt.data.AppSettings
import com.spotywoop.kt.data.TransliterationMode
import com.spotywoop.kt.lyrics.GoogleLyricsService
import com.spotywoop.kt.lyrics.LyricsContributor
import com.spotywoop.kt.lyrics.LyricsExport
import com.spotywoop.kt.lyrics.LyricsLine
import com.spotywoop.kt.lyrics.LyricsProviders
import com.spotywoop.kt.lyrics.LyricsSourceResult
import com.spotywoop.kt.lyrics.LyricsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * Élément affiché dans la liste : soit une ligne de paroles, soit un intervalle instrumental.
 */
sealed class LyricsListItem {
    data class LineItem(val uiData: LineUiData) : LyricsListItem()
    data class GapItem(val gap: InstrumentalGap) : LyricsListItem()
}

/**
 * Vue complète de paroles synchronisées mot par mot, porteuse de tout le rendu et de toute
 * l'animation de am-lyrics (AmLyrics.ts) en Jetpack Compose natif.
 */
@Composable
fun AmLyricsView(
    lyrics: List<LyricsLine>,
    clock: PlaybackClock,
    modifier: Modifier = Modifier,
    currentSource: LyricsSourceResult? = null,
    availableSources: List<LyricsSourceResult> = emptyList(),
    songwriters: String? = null,
    onSeek: (Long) -> Unit = {},
    onSwitchSource: () -> Unit = {},
    hidePlayedLines: Boolean = false,
    reducedMotion: Boolean = false,
    lineMotion: String = "spring",
    noBlur: Boolean = false,
    showControlsHeader: Boolean = true,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Options d'affichage interactives
    val appTranslitMode by AppSettings.transliterationMode.collectAsStateWithLifecycle()
    var showRomanization by remember(appTranslitMode) {
        mutableStateOf(appTranslitMode != TransliterationMode.OFF)
    }
    var showTranslation by remember { mutableStateOf(false) }
    var translatedLines by remember(lyrics) { mutableStateOf<List<LyricsLine>?>(null) }

    // Application dynamique de la traduction si demandée
    val activeLyrics = remember(lyrics, showTranslation, translatedLines) {
        if (showTranslation && translatedLines != null) translatedLines!! else lyrics
    }

    // Prétraitement des lignes et détection des pauses instrumentales
    val items = remember(activeLyrics) {
        val list = mutableListOf<LyricsListItem>()
        val gaps = findAllInstrumentalGaps(activeLyrics)
        val gapsByInsertIndex = gaps.associateBy { it.insertBeforeIndex }

        gapsByInsertIndex[0]?.let { list.add(LyricsListItem.GapItem(it)) }

        activeLyrics.forEachIndexed { index, line ->
            val uiData = buildLineUiData(line, index, activeLyrics)
            list.add(LyricsListItem.LineItem(uiData))
            gapsByInsertIndex[index + 1]?.let { list.add(LyricsListItem.GapItem(it)) }
        }
        list
    }

    val isUnsynced = remember(activeLyrics) {
        activeLyrics.isNotEmpty() && activeLyrics.all { it.timestamp == 0L && it.endtime == 0L }
    }

    val listState = rememberLazyListState()
    var containerHeightPx by remember { mutableIntStateOf(0) }

    // Détection du défilement manuel utilisateur avec pause d'auto-scroll de 5 s
    var isUserScrolling by remember { mutableStateOf(false) }

    // Seul le doigt compte : isScrollInProgress vaut aussi pour le défilement automatique, qui s'annulerait lui-même.
    val isDragged by listState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(isDragged) {
        if (isDragged) {
            isUserScrolling = true
        } else if (isUserScrolling) {
            delay(LyricsUiConstants.USER_SCROLL_RESUME_DELAY_MS)
            isUserScrolling = false
        }
    }

    // Calcul de la ligne active
    val activeLineIndices by remember(activeLyrics, clock.timeMs) {
        derivedStateOf {
            if (isUnsynced) emptyList()
            else findActiveLineIndices(activeLyrics, clock.timeMs)
        }
    }

    val primaryActiveIndex = remember(activeLineIndices) {
        activeLineIndices.firstOrNull() ?: -1
    }

    // Défilement automatique vers la ligne active avec alignement à 12% du sommet
    LaunchedEffect(primaryActiveIndex, isUserScrolling) {
        if (primaryActiveIndex >= 0 && !isUserScrolling && !isUnsynced && containerHeightPx > 0) {
            val itemIndex = items.indexOfFirst {
                it is LyricsListItem.LineItem && it.uiData.index == primaryActiveIndex
            }
            if (itemIndex >= 0) {
                val scrollOffset = -(containerHeightPx * LyricsUiConstants.SCROLL_PADDING_TOP_RATIO).toInt()
                if (reducedMotion || lineMotion == "instant") {
                    listState.scrollToItem(itemIndex, scrollOffset)
                } else {
                    listState.animateScrollToItem(itemIndex, scrollOffset)
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                containerHeightPx = coordinates.size.height
            },
    ) {
        // En-tête des commandes (Source, Romanisation, Traduction, Partage)
        if (showControlsHeader) {
            LyricsHeaderBar(
                source = currentSource?.source,
                showRomanization = showRomanization,
                showTranslation = showTranslation,
                onToggleRomanization = {
                    showRomanization = !showRomanization
                },
                onToggleTranslation = {
                    showTranslation = !showTranslation
                    if (showTranslation && translatedLines == null) {
                        coroutineScope.launch {
                            translatedLines = GoogleLyricsService.applyTranslation(lyrics, "fr")
                        }
                    }
                },
                onShare = { type ->
                    val content = when (type) {
                        "lrc" -> LyricsExport.generateLRC(
                            lines = activeLyrics,
                            title = currentSource?.metadata?.title,
                            artist = currentSource?.metadata?.artist,
                            album = currentSource?.metadata?.album,
                            source = currentSource?.source,
                        )
                        "plain" -> LyricsExport.generatePlain(activeLyrics)
                        "ttml" -> LyricsExport.generateTTML(activeLyrics)
                        else -> LyricsExport.generatePlain(activeLyrics)
                    }
                    shareLyrics(context, content, "Partager les paroles ($type)")
                },
            )
        }

        // Liste déroulante des paroles
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds(), // la ligne active grossit : sans ça, elle déborde sur l'en-tête
            contentPadding = PaddingValues(top = 16.dp),
        ) {
            itemsIndexed(
                items = items,
                key = { _, item ->
                    when (item) {
                        is LyricsListItem.LineItem -> "line-${item.uiData.index}"
                        is LyricsListItem.GapItem -> "gap-${item.gap.insertBeforeIndex}-${item.gap.gapStart}"
                    }
                },
            ) { _, item ->
                when (item) {
                    is LyricsListItem.GapItem -> {
                        InstrumentalGapRow(
                            gap = item.gap,
                            clock = clock,
                        )
                    }
                    is LyricsListItem.LineItem -> {
                        val lineIndex = item.uiData.index
                        val isActive = lineIndex in activeLineIndices
                        val distance = if (primaryActiveIndex >= 0) abs(lineIndex - primaryActiveIndex) else 99

                        // Gestion de hidePlayedLines
                        val isPlayed = hidePlayedLines &&
                            computeLineHighlightEndTime(activeLyrics, lineIndex) <= clock.timeMs

                        if (!isPlayed) {
                            LyricsLineRow(
                                lineUi = item.uiData,
                                clock = clock,
                                isActive = isActive,
                                isUnsynced = isUnsynced,
                                distance = distance,
                                isUserScrolling = isUserScrolling,
                                noBlur = noBlur,
                                reducedMotion = reducedMotion,
                                showRomanization = showRomanization,
                                onSeek = onSeek,
                            )
                        }
                    }
                }
            }

            // Bas de page (footer) : source, changement de source, auteurs, contributeurs
            item(key = "lyrics-footer") {
                LyricsFooter(
                    currentSource = currentSource,
                    availableSources = availableSources,
                    songwriters = songwriters ?: currentSource?.songwriters,
                    contributors = currentSource?.contributors,
                    onSwitchSource = onSwitchSource,
                )
            }
        }
    }
}

/**
 * Une ligne de parole complète avec gestion du chant principal et des chœurs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricsLineRow(
    lineUi: LineUiData,
    clock: PlaybackClock,
    isActive: Boolean,
    isUnsynced: Boolean,
    distance: Int,
    isUserScrolling: Boolean,
    noBlur: Boolean,
    reducedMotion: Boolean,
    showRomanization: Boolean,
    onSeek: (Long) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    // Atténuation de l'opacité selon la distance par rapport à la ligne active
    val targetOpacity = when {
        isUnsynced -> 1.0f
        isUserScrolling -> 0.85f
        isActive -> 1.0f
        distance == 1 -> 0.80f
        distance == 2 -> 0.70f
        distance == 3 -> 0.58f
        else -> 0.46f
    }

    val animatedOpacity by animateFloatAsState(
        targetValue = targetOpacity,
        animationSpec = tween(LyricsUiConstants.SCROLL_ANIMATION_DURATION_MS.toInt()),
        label = "line_opacity",
    )

    // Échelle : active = 1.0, inactive = 0.98
    val targetScale = if (isActive || isUnsynced) 1.0f else LyricsUiConstants.INACTIVE_LINE_SCALE
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(LyricsUiConstants.SCROLL_ANIMATION_DURATION_MS.toInt()),
        label = "line_scale",
    )

    // Flou selon la distance (sauf si noBlur ou défilement utilisateur)
    val blurRadius = when {
        noBlur || isUserScrolling || isActive || isUnsynced -> 0.dp
        distance == 1 -> LyricsUiConstants.BLUR_AMOUNT_NEAR_DP.dp
        distance == 2 -> 1.0.dp
        distance == 3 -> 1.7.dp
        else -> LyricsUiConstants.BLUR_AMOUNT_DP.dp
    }

    val alignment = if (lineUi.isOppositeAgent) Alignment.End else Alignment.Start
    val transformOrigin = if (lineUi.isOppositeAgent || lineUi.isRtl) {
        TransformOrigin(1f, 0.5f)
    } else {
        TransformOrigin(0f, 0.5f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (lineUi.isOppositeAgent) 32.dp else 20.dp,
                end = if (lineUi.isOppositeAgent) 20.dp else 32.dp,
                top = 6.dp,
                bottom = (LyricsUiConstants.LINE_SPACING_DP / 2).dp,
            )
            .graphicsLayer {
                alpha = animatedOpacity
                scaleX = animatedScale
                scaleY = animatedScale
                this.transformOrigin = transformOrigin
            }
            .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !isUnsynced && lineUi.line.timestamp > 0L,
            ) {
                onSeek(lineUi.line.timestamp)
            },
        horizontalAlignment = alignment,
    ) {
        // Chœurs placés avant le chant principal
        if (lineUi.line.backgroundText.isNotEmpty() && lineUi.backgroundPlacement == BackgroundPlacement.BEFORE) {
            BackgroundVocalsBlock(
                words = lineUi.backgroundWords,
                clock = clock,
                isActiveLine = isActive,
                isOpposite = lineUi.isOppositeAgent,
                reducedMotion = reducedMotion,
            )
            if (showRomanization && !lineUi.bgTransliteratedText.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "(${lineUi.bgTransliteratedText})",
                    color = Color.White.copy(alpha = 0.50f),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = if (lineUi.isOppositeAgent) TextAlign.End else TextAlign.Start,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (lineUi.isLineSyncedOnly) {
            // Pas de temps par mot : toute la ligne s'allume d'un coup, en 0,2 s comme sur le web (fade-in-line).
            val color by animateColorAsState(
                targetValue = if (isActive || isUnsynced) Color.White else Color.White.copy(alpha = 0.55f),
                animationSpec = tween(200),
                label = "line_color",
            )
            Text(
                text = lineUi.line.text.joinToString("") { it.text },
                color = color,
                style = TextStyle(
                    fontSize = LyricsUiConstants.FONT_SIZE_BASE_SP.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                ),
                modifier = Modifier.fillMaxWidth(),
                textAlign = if (lineUi.isOppositeAgent) TextAlign.End else TextAlign.Start,
            )
        } else {
            // Ligne principale (FlowRow pour retour à la ligne naturel des mots)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (lineUi.isOppositeAgent) Arrangement.End else Arrangement.Start,
                verticalArrangement = Arrangement.Center,
            ) {
                lineUi.mainWords.forEach { word ->
                    LyricsWordItem(
                        word = word,
                        clock = clock,
                        isActiveLine = isActive,
                        isBackground = false,
                        fontSize = LyricsUiConstants.FONT_SIZE_BASE_SP.sp,
                        reducedMotion = reducedMotion,
                    )
                }
            }
        }

        // Sous-titre de translittération en alphabet latin (Romaji)
        if (showRomanization && !lineUi.transliteratedText.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            val subtitleColor by animateColorAsState(
                targetValue = if (isActive || isUnsynced) Color.White.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.42f),
                animationSpec = tween(200),
                label = "subtitle_color",
            )
            Text(
                text = lineUi.transliteratedText,
                color = subtitleColor,
                style = TextStyle(
                    fontSize = (LyricsUiConstants.FONT_SIZE_BASE_SP * 0.52f).sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                ),
                modifier = Modifier.fillMaxWidth(),
                textAlign = if (lineUi.isOppositeAgent) TextAlign.End else TextAlign.Start,
            )
        }

        // Chœurs placés après le chant principal
        if (lineUi.line.backgroundText.isNotEmpty() && lineUi.backgroundPlacement == BackgroundPlacement.AFTER) {
            Spacer(Modifier.height(8.dp))
            BackgroundVocalsBlock(
                words = lineUi.backgroundWords,
                clock = clock,
                isActiveLine = isActive,
                isOpposite = lineUi.isOppositeAgent,
                reducedMotion = reducedMotion,
            )
            if (showRomanization && !lineUi.bgTransliteratedText.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "(${lineUi.bgTransliteratedText})",
                    color = Color.White.copy(alpha = 0.50f),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = if (lineUi.isOppositeAgent) TextAlign.End else TextAlign.Start,
                )
            }
        }
    }
}

/**
 * Bloc de chœurs (background vocals) avec taille réduite (0.65em) et apparition animée.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackgroundVocalsBlock(
    words: List<WordUiData>,
    clock: PlaybackClock,
    isActiveLine: Boolean,
    isOpposite: Boolean,
    reducedMotion: Boolean,
) {
    AnimatedVisibility(
        visible = isActiveLine,
        enter = fadeIn(tween(650)) + expandVertically(tween(650)),
        exit = fadeOut(tween(450)) + shrinkVertically(tween(450)),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isOpposite) Arrangement.End else Arrangement.Start,
        ) {
            words.forEach { word ->
                LyricsWordItem(
                    word = word,
                    clock = clock,
                    isActiveLine = isActiveLine,
                    isBackground = true,
                    fontSize = (LyricsUiConstants.FONT_SIZE_BASE_SP * LyricsUiConstants.BG_VOCAL_FONT_SCALE).sp,
                    reducedMotion = reducedMotion,
                )
            }
        }
    }
}

/**
 * Barre de contrôle supérieure (Source, Romanisation, Traduction, Export partage).
 */
@Composable
private fun LyricsHeaderBar(
    source: String?,
    showRomanization: Boolean,
    showTranslation: Boolean,
    onToggleRomanization: () -> Unit,
    onToggleTranslation: () -> Unit,
    onShare: (String) -> Unit,
) {
    var shareMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Badge de source active
        val label = if (!source.isNullOrBlank()) {
            LyricsProviders.getDisplaySourceLabel(source)
        } else "Recherche..."

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // Boutons de commande
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Bouton Romanisation
            IconButton(onClick = onToggleRomanization) {
                Text(
                    text = "Aa",
                    color = if (showRomanization) Color.White else Color.White.copy(alpha = 0.45f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Bouton Traduction
            IconButton(onClick = onToggleTranslation) {
                Icon(
                    imageVector = Icons.Rounded.Translate,
                    contentDescription = "Traduction",
                    tint = if (showTranslation) Color.White else Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.size(20.dp),
                )
            }

            // Bouton Partage / Export
            Box {
                IconButton(onClick = { shareMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "Partager les paroles",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp),
                    )
                }

                DropdownMenu(
                    expanded = shareMenuExpanded,
                    onDismissRequest = { shareMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Partager en texte brut") },
                        onClick = {
                            shareMenuExpanded = false
                            onShare("plain")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Partager en LRC") },
                        onClick = {
                            shareMenuExpanded = false
                            onShare("lrc")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Partager en TTML") },
                        onClick = {
                            shareMenuExpanded = false
                            onShare("ttml")
                        },
                    )
                }
            }
        }
    }
}

/**
 * Bas de page (Footer) : source, changement de source, auteurs et contributeurs.
 */
@Composable
private fun LyricsFooter(
    currentSource: LyricsSourceResult?,
    availableSources: List<LyricsSourceResult>,
    songwriters: String?,
    contributors: List<LyricsContributor>?,
    onSwitchSource: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Ligne de source et bouton pour changer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Source des paroles",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
                Text(
                    text = currentSource?.source?.let { LyricsProviders.getDisplaySourceLabel(it) } ?: "Automatique",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (availableSources.size > 1) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable(onClick = onSwitchSource)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SwapHoriz,
                            contentDescription = "Changer de source",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Changer (${availableSources.size})",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // Auteurs / compositeurs
        if (!songwriters.isNullOrBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Auteurs-compositeurs",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
                Text(
                    text = songwriters,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                )
            }
        }

        // Contributeurs (ex: Spicy Lyrics / lrc.red)
        if (!contributors.isNullOrEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Contributeurs",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    contributors.take(5).forEach { contributor ->
                        if (!contributor.avatar.isNullOrBlank()) {
                            AsyncImage(
                                model = contributor.avatar,
                                contentDescription = contributor.username,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = contributor.username.take(1).uppercase(),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    Text(
                        text = contributors.joinToString(", ") { it.username },
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                    )
                }
            }
        }

        // Espacement final
        Spacer(Modifier.height(120.dp))
    }
}

/**
 * Lance l'intention de partage Android standard (ACTION_SEND).
 */
private fun shareLyrics(context: Context, text: String, title: String) {
    if (text.isBlank()) return
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, text)
        type = "text/plain"
    }
    val chooser = Intent.createChooser(sendIntent, title).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}
