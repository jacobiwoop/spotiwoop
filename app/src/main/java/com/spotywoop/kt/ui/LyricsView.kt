package com.spotywoop.kt.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotywoop.kt.data.LyricsResult
import com.spotywoop.kt.ui.theme.SpotyColors

/**
 * Carte d'aperçu des paroles intégrée dans le défilement du lecteur (style Spotify Mobile).
 */
@Composable
fun LyricsCard(
    lyrics: LyricsResult?,
    loading: Boolean,
    currentPositionMs: Long,
    dominantColor: Color,
    onSeek: (Long) -> Unit,
    onOpenFullScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardColor = remember(dominantColor) {
        dominantColor.copy(alpha = 0.95f)
    }

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
                loading -> {
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
                lyrics == null || lyrics.lines.isEmpty() -> {
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
                    val activeIndex by remember(lyrics, currentPositionMs) {
                        derivedStateOf {
                            if (!lyrics.isSynced) -1
                            else lyrics.lines.indexOfLast { it.timeMs <= currentPositionMs }
                        }
                    }

                    val listState = rememberLazyListState()

                    LaunchedEffect(activeIndex) {
                        if (activeIndex in lyrics.lines.indices) {
                            listState.animateScrollToItem(
                                index = (activeIndex - 1).coerceAtLeast(0),
                            )
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                    ) {
                        itemsIndexed(lyrics.lines) { index, line ->
                            val isActive = index == activeIndex
                            val textColor = when {
                                !lyrics.isSynced -> Color.White.copy(alpha = 0.9f)
                                isActive -> Color.White
                                else -> Color.White.copy(alpha = 0.45f)
                            }
                            val fontWeight = if (isActive || !lyrics.isSynced) FontWeight.Bold else FontWeight.SemiBold
                            val fontSize = if (isActive) 20.sp else 18.sp

                            Text(
                                text = line.text.ifBlank { "♪" },
                                color = textColor,
                                fontSize = fontSize,
                                fontWeight = fontWeight,
                                textAlign = TextAlign.Start,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = lyrics.isSynced && line.timeMs > 0) {
                                        onSeek(line.timeMs)
                                    }
                                    .padding(vertical = 8.dp),
                            )
                        }
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
 * Vue plein écran des paroles synchronisées (Modal overlay style Spotify).
 */
@Composable
fun FullScreenLyricsDialog(
    lyrics: LyricsResult?,
    currentPositionMs: Long,
    dominantColor: Color,
    onClose: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val listState = rememberLazyListState()

    val activeIndex by remember(lyrics, currentPositionMs) {
        derivedStateOf {
            if (lyrics == null || !lyrics.isSynced) -1
            else lyrics.lines.indexOfLast { it.timeMs <= currentPositionMs }
        }
    }

    LaunchedEffect(activeIndex) {
        if (lyrics != null && activeIndex in lyrics.lines.indices) {
            listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SpotyColors.Background)
            .background(
                Brush.verticalGradient(
                    listOf(dominantColor, dominantColor.copy(alpha = 0.9f), Color(0xFF121212))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Paroles",
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

            Spacer(Modifier.height(24.dp))

            if (lyrics != null && lyrics.lines.isNotEmpty()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(lyrics.lines) { index, line ->
                        val isActive = index == activeIndex
                        val textColor = when {
                            !lyrics.isSynced -> Color.White.copy(alpha = 0.9f)
                            isActive -> Color.White
                            else -> Color.White.copy(alpha = 0.4f)
                        }
                        val fontWeight = if (isActive || !lyrics.isSynced) FontWeight.Bold else FontWeight.SemiBold
                        val fontSize = if (isActive) 24.sp else 20.sp

                        Text(
                            text = line.text.ifBlank { "♪" },
                            color = textColor,
                            fontSize = fontSize,
                            fontWeight = fontWeight,
                            lineHeight = (fontSize.value * 1.3).sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = lyrics.isSynced && line.timeMs > 0) {
                                    onSeek(line.timeMs)
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
