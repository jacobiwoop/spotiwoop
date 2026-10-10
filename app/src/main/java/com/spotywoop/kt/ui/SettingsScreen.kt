package com.spotywoop.kt.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.spotywoop.kt.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotywoop.kt.BuildConfig
import com.spotywoop.kt.data.AppUpdateManager
import com.spotywoop.kt.data.UpdateStatus
import com.spotywoop.kt.ui.theme.SpotyColors

@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(SpotyColors.Background)
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.ArrowBack, "Retour", tint = SpotyColors.TextPrimary)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Paramètres",
                color = SpotyColors.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // --- SECTION MISE À JOUR (IN-APP UPDATER) ---
        UpdateCard()

        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_spotywoop_logo_badge),
                contentDescription = "Logo Spotywoop",
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Spotywoop",
                color = SpotyColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Version 1.0.0 (Native HQ)",
                color = SpotyColors.TextMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun UpdateCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val updateStatus by AppUpdateManager.status.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SpotyColors.Surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    "Mises à jour",
                    color = SpotyColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Version actuelle : v${BuildConfig.VERSION_NAME}",
                    color = SpotyColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }

            when (updateStatus) {
                is UpdateStatus.Checking -> {
                    CircularProgressIndicator(
                        color = SpotyColors.SpotifyGreen,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                }
                is UpdateStatus.Downloading -> { }
                else -> {
                    IconButton(
                        onClick = { AppUpdateManager.checkForUpdates(silent = false) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "Vérifier",
                            tint = SpotyColors.SpotifyGreen,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        when (val s = updateStatus) {
            is UpdateStatus.Available -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF162B1D), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.SystemUpdate,
                            contentDescription = null,
                            tint = SpotyColors.SpotifyGreen,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Nouvelle version : ${s.info.tagName}",
                            color = SpotyColors.SpotifyGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (s.info.changelog.isNotBlank()) {
                        Text(
                            text = s.info.changelog,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = { AppUpdateManager.downloadAndInstall(context, s.info) },
                        colors = ButtonDefaults.buttonColors(containerColor = SpotyColors.SpotifyGreen),
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            "Télécharger et Installer (${s.info.assetSize / 1024 / 1024} Mo)",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            is UpdateStatus.Downloading -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val pct = (s.progress * 100).toInt()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Téléchargement de la mise à jour...", color = SpotyColors.TextSecondary, fontSize = 12.sp)
                        Text("$pct%", color = SpotyColors.SpotifyGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = SpotyColors.SpotifyGreen,
                        trackColor = Color.DarkGray,
                    )
                }
            }
            is UpdateStatus.ReadyToInstall -> {
                Button(
                    onClick = { AppUpdateManager.launchInstaller(context, s.apkFile) },
                    colors = ButtonDefaults.buttonColors(containerColor = SpotyColors.SpotifyGreen),
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Ouvrir l'installateur Android", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
            is UpdateStatus.UpToDate -> {
                Text(
                    "✓ Votre application est à jour",
                    color = Color(0xFF81C784),
                    fontSize = 12.sp,
                )
            }
            is UpdateStatus.Error -> {
                Text(
                    s.message,
                    color = Color(0xFFE57373),
                    fontSize = 12.sp,
                )
            }
            is UpdateStatus.Checking -> {
                Text(
                    "Recherche des mises à jour sur GitHub...",
                    color = SpotyColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }
            else -> {}
        }
    }
}
