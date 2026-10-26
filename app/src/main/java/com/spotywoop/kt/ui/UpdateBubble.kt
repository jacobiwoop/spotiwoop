package com.spotywoop.kt.ui

import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spotywoop.kt.data.AppUpdateManager
import com.spotywoop.kt.data.UpdateStatus
import com.spotywoop.kt.ui.theme.SpotyColors
import kotlin.math.roundToInt

private val BubbleSize = 60.dp
private val RingWidth = 4.dp
private val WaitRed = Color(0xFFE53935)

/**
 * Bulle de mise à jour, déplaçable au doigt, visible sur tous les écrans tant que la dernière
 * version n'est pas installée.
 * - À télécharger, ou téléchargée mais pas installée : l'anneau tourne, 95 % rouge et 5 % blanc.
 * - Pendant le téléchargement : l'anneau vert se remplit au vrai pourcentage.
 * Un appui lance le téléchargement, ou l'installation quand le fichier est prêt.
 */
@Composable
fun UpdateBubble(modifier: Modifier = Modifier) {
    val status by AppUpdateManager.status.collectAsStateWithLifecycle()
    val latest by AppUpdateManager.latest.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val info = when (val s = status) {
        is UpdateStatus.Available -> s.info
        is UpdateStatus.Downloading -> s.info ?: latest
        is UpdateStatus.ReadyToInstall -> s.info ?: latest
        is UpdateStatus.Error -> latest // relancer après une erreur
        else -> null
    }
    if (info == null) return

    // Fin du téléchargement vue dans l'appli : on enchaîne sur l'installation, comme avant.
    var previous by remember { mutableStateOf<UpdateStatus?>(null) }
    LaunchedEffect(status) {
        val s = status
        if (previous is UpdateStatus.Downloading && s is UpdateStatus.ReadyToInstall) {
            AppUpdateManager.launchInstaller(context, s.apkFile)
        }
        previous = s
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val sizePx = with(density) { BubbleSize.toPx() }
        val maxX = constraints.maxWidth - sizePx
        val maxY = constraints.maxHeight - sizePx
        // Position de départ : à droite, au-dessus du mini-lecteur et de la barre de navigation.
        var x by rememberSaveable { mutableFloatStateOf(-1f) }
        var y by rememberSaveable { mutableFloatStateOf(-1f) }
        if (x < 0f) {
            x = maxX - with(density) { 12.dp.toPx() }
            y = maxY * 0.72f
        }
        // Lâchée, la bulle se range contre le bord le plus proche.
        var dragging by remember { mutableStateOf(false) }
        val shownX by animateFloatAsState(
            targetValue = x,
            animationSpec = if (dragging) tween(0) else tween(220),
            label = "bubbleX",
        )

        val downloading = status as? UpdateStatus.Downloading
        Box(
            Modifier
                .offset { IntOffset(shownX.roundToInt(), y.roundToInt()) }
                .size(BubbleSize)
                .shadow(10.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xFF1C1C1C))
                .semantics {
                    contentDescription = when {
                        downloading != null -> "Mise à jour ${info.tagName} : téléchargement ${(downloading.progress * 100).toInt()} %"
                        status is UpdateStatus.ReadyToInstall -> "Installer la mise à jour ${info.tagName}"
                        else -> "Télécharger la mise à jour ${info.tagName}"
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            x = if (x + sizePx / 2 < (maxX + sizePx) / 2) 12.dp.toPx() else maxX - 12.dp.toPx()
                        },
                        onDragCancel = { dragging = false },
                    ) { change, delta ->
                        change.consume()
                        x = (x + delta.x).coerceIn(0f, maxX)
                        y = (y + delta.y).coerceIn(0f, maxY)
                    }
                }
                .pointerInput(info, status) {
                    detectTapGestures {
                        when (val s = status) {
                            is UpdateStatus.ReadyToInstall -> AppUpdateManager.launchInstaller(context, s.apkFile)
                            is UpdateStatus.Downloading -> Toast.makeText(
                                context, "Téléchargement de ${info.tagName} : ${(s.progress * 100).toInt()} %", Toast.LENGTH_SHORT,
                            ).show()
                            else -> {
                                Toast.makeText(context, "Téléchargement de ${info.tagName} lancé", Toast.LENGTH_SHORT).show()
                                AppUpdateManager.downloadAndInstall(context, info)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (downloading != null) ProgressRing(downloading.progress) else WaitingRing()
            if (downloading != null && downloading.progress > 0f) {
                Text(
                    "${(downloading.progress * 100).toInt()}%",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Icon(
                    if (status is UpdateStatus.ReadyToInstall) Icons.Rounded.InstallMobile else Icons.Rounded.Download,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

/** Anneau qui tourne : 95 % rouge, 5 % blanc, pour signaler une action à faire. */
@Composable
private fun WaitingRing() {
    val rotation by rememberInfiniteTransition(label = "wait").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )
    Canvas(Modifier.fillMaxSize().padding(RingWidth / 2)) {
        val stroke = Stroke(width = RingWidth.toPx(), cap = StrokeCap.Butt)
        val arcSize = Size(size.width, size.height)
        rotate(rotation) {
            drawArc(WaitRed, startAngle = -90f, sweepAngle = 342f, useCenter = false, topLeft = Offset.Zero, size = arcSize, style = stroke)
            drawArc(Color.White, startAngle = 252f, sweepAngle = 18f, useCenter = false, topLeft = Offset.Zero, size = arcSize, style = stroke)
        }
    }
}

/** Anneau de progression : piste grise, partie remplie au vert de l'appli. */
@Composable
private fun ProgressRing(progress: Float) {
    val shown by animateFloatAsState(progress, tween(350), label = "progress")
    Canvas(Modifier.fillMaxSize().padding(RingWidth / 2)) {
        val stroke = Stroke(width = RingWidth.toPx(), cap = StrokeCap.Round)
        val arcSize = Size(size.width, size.height)
        drawArc(Color(0xFF3A3A3A), 0f, 360f, false, Offset.Zero, arcSize, style = stroke)
        drawArc(SpotyColors.Accent, -90f, 360f * shown, false, Offset.Zero, arcSize, style = stroke)
    }
}
