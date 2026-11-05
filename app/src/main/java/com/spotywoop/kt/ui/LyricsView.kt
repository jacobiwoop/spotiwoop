package com.spotywoop.kt.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotywoop.kt.data.LyricsResult
import com.spotywoop.kt.lyrics.LyricsLine
import com.spotywoop.kt.lyrics.LyricsRepository
import com.spotywoop.kt.lyrics.LyricsState
import com.spotywoop.kt.lyrics.Syllable
import com.spotywoop.kt.lyrics.ui.AmLyricsView
import com.spotywoop.kt.lyrics.ui.rememberPlaybackClock
import com.spotywoop.kt.ui.theme.SpotyColors
import kotlinx.coroutines.launch

/**
 * Convertit les paroles ligne par ligne rapides de [LyricsResult] en modèle complet [LyricsLine].
 */
fun LyricsResult.toLyricsLines(): List<LyricsLine> {
    return lines.mapIndexed { index, lyricLine ->
        val nextTime = lines.getOrNull(index + 1)?.timeMs ?: (lyricLine.timeMs + 4000L)
        LyricsLine(
            text = listOf(
                Syllable(
                    text = lyricLine.text,
                    part = false,
                    timestamp = lyricLine.timeMs,
                    endtime = nextTime,
                    lineSynced = true,
                ),
            ),
            timestamp = lyricLine.timeMs,
            endtime = nextTime,
            isWordSynced = false,
        )
    }
}

/**
 * Carte d'aperçu des paroles intégrée dans le défilement du lecteur (style Spotify Mobile),
 * animée avec l'horloge fluide et le surlignage mot par mot.
 */
@Composable
fun LyricsCard(
    lyrics: LyricsResult? = null,
    richLyrics: LyricsState = LyricsState(),
    loading: Boolean = false,
    currentPositionMs: Long = 0L,
    isPlaying: Boolean = false,
    dominantColor: Color = Color(0xFF1E1E1E),
    onSeek: (Long) -> Unit = {},
    onOpenFullScreen: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cardColor = remember(dominantColor) {
        dominantColor.copy(alpha = 0.95f)
    }

    val coroutineScope = rememberCoroutineScope()
    val clock = rememberPlaybackClock(positionMs = currentPositionMs, isPlaying = isPlaying)

    // Priorité aux paroles riches de la cascade, avec repli immédiat sur l'API rapide du site
    val effectiveLines = remember(richLyrics.lyrics, lyrics) {
        when {
            !richLyrics.lyrics.isNullOrEmpty() -> richLyrics.lyrics
            lyrics != null && lyrics.lines.isNotEmpty() -> lyrics.toLyricsLines()
            else -> null
        }
    }

    val isLoading = loading && effectiveLines == null

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cardColor)
            .padding(20.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Aperçu des paroles",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.3f))
                        .clickable(onClick = onOpenFullScreen),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Fullscreen,
                        contentDescription = "Plein écran",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            when {
                isLoading -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                effectiveLines.isNullOrEmpty() -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(90.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Aucune parole disponible",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 15.sp,
                        )
                    }
                }
                else -> {
                    // Aperçu avec défilement et surlignage mot par mot
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                    ) {
                        AmLyricsView(
                            lyrics = effectiveLines,
                            clock = clock,
                            currentSource = richLyrics.currentSource,
                            availableSources = richLyrics.availableSources,
                            songwriters = richLyrics.songwriters,
                            onSeek = { targetMs ->
                                clock.seekTo(targetMs)
                                onSeek(targetMs)
                            },
                            onSwitchSource = {
                                coroutineScope.launch {
                                    LyricsRepository.switchSource()
                                }
                            },
                            showControlsHeader = false,
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = onOpenFullScreen,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                        ),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            text = "Afficher les paroles",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Vue plein écran des paroles synchronisées (Overlay modal natif complet am-lyrics).
 */
@Composable
fun FullScreenLyricsDialog(
    lyrics: LyricsResult? = null,
    richLyrics: LyricsState = LyricsState(),
    currentPositionMs: Long = 0L,
    isPlaying: Boolean = false,
    dominantColor: Color = Color(0xFF1E1E1E),
    onClose: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    val clock = rememberPlaybackClock(positionMs = currentPositionMs, isPlaying = isPlaying)

    val effectiveLines = remember(richLyrics.lyrics, lyrics) {
        when {
            !richLyrics.lyrics.isNullOrEmpty() -> richLyrics.lyrics
            lyrics != null && lyrics.lines.isNotEmpty() -> lyrics.toLyricsLines()
            else -> emptyList()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SpotyColors.Background)
            .background(
                Brush.verticalGradient(
                    listOf(dominantColor, dominantColor.copy(alpha = 0.9f), Color(0xFF121212)),
                ),
            )
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            // Barre supérieure avec bouton de fermeture
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Paroles",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Fermer",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (effectiveLines.isNotEmpty()) {
                AmLyricsView(
                    lyrics = effectiveLines,
                    clock = clock,
                    currentSource = richLyrics.currentSource,
                    availableSources = richLyrics.availableSources,
                    songwriters = richLyrics.songwriters,
                    onSeek = { targetMs ->
                        clock.seekTo(targetMs)
                        onSeek(targetMs)
                    },
                    onSwitchSource = {
                        coroutineScope.launch {
                            LyricsRepository.switchSource()
                        }
                    },
                    showControlsHeader = true,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Aucune parole disponible",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}
