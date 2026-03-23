package com.nuvio.tv.ui.util

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts the dominant color from an image URL using Coil + Android Palette.
 * Returns a saturated, moderately dark color suitable for ambient background glow on TV.
 */
@Composable
fun rememberDominantColor(
    imageUrl: String?,
    context: Context,
    defaultColor: Color = Color(0xFF0D0D0F)
): Color {
    var dominantColor by remember { mutableStateOf(defaultColor) }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrBlank()) {
            dominantColor = defaultColor
            return@LaunchedEffect
        }
        dominantColor = extractDominantColor(context, imageUrl, defaultColor)
    }

    return dominantColor
}

private suspend fun extractDominantColor(
    context: Context,
    imageUrl: String,
    defaultColor: Color
): Color = withContext(Dispatchers.IO) {
    try {
        val loader = ImageLoader(context)
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .allowHardware(false)
            .size(200, 200)
            .build()
        val result = loader.execute(request)
        if (result is SuccessResult) {
            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
            if (bitmap != null) {
                val palette = Palette.from(bitmap)
                    .maximumColorCount(24)
                    .generate()
                // Prefer vibrant/muted swatches for a rich ambient glow
                val swatch = palette.vibrantSwatch
                    ?: palette.mutedSwatch
                    ?: palette.darkVibrantSwatch
                    ?: palette.darkMutedSwatch
                    ?: palette.lightMutedSwatch
                    ?: palette.dominantSwatch
                if (swatch != null) {
                    val rgb = swatch.rgb
                    val r = ((rgb shr 16) and 0xFF) / 255f
                    val g = ((rgb shr 8) and 0xFF) / 255f
                    val b = (rgb and 0xFF) / 255f
                    // Keep color at ~45% brightness for visible ambient glow
                    Color(
                        red = r * 0.45f,
                        green = g * 0.45f,
                        blue = b * 0.45f,
                        alpha = 1f
                    )
                } else defaultColor
            } else defaultColor
        } else defaultColor
    } catch (_: Exception) {
        defaultColor
    }
}
