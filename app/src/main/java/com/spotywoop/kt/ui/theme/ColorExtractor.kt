package com.spotywoop.kt.ui.theme

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ColorExtractor {
    private val cache = mutableMapOf<String, Color>()
    val DefaultDominant = Color(0xFF1B353D)

    suspend fun extractDominantColor(context: Context, url: String?): Color {
        if (url.isNullOrBlank()) return DefaultDominant
        cache[url]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val loader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false) // Nécessaire pour que Palette puisse lire les pixels
                    .size(120, 120) // Petite taille pour une extraction quasi-instantanée
                    .build()
                val result = (loader.execute(request) as? SuccessResult)?.drawable
                val bitmap = (result as? BitmapDrawable)?.bitmap
                if (bitmap != null) {
                    val palette = Palette.from(bitmap).generate()
                    val rgb = palette.vibrantSwatch?.rgb
                        ?: palette.dominantSwatch?.rgb
                        ?: palette.lightVibrantSwatch?.rgb
                        ?: palette.mutedSwatch?.rgb
                        ?: palette.darkVibrantSwatch?.rgb
                    if (rgb != null) {
                        val extracted = Color(rgb)
                        cache[url] = extracted
                        return@withContext extracted
                    }
                }
            } catch (_: Throwable) {
                // Utilise la couleur de repli
            }
            DefaultDominant
        }
    }
}
