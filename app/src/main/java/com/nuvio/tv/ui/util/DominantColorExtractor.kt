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
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** In-memory cache so we never re-extract the same URL twice per session. */
private val dominantColorCache = ConcurrentHashMap<String, Color>()

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
    var dominantColor by remember { mutableStateOf(
        if (imageUrl != null) dominantColorCache[imageUrl] ?: defaultColor else defaultColor
    ) }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrBlank()) {
            dominantColor = defaultColor
            return@LaunchedEffect
        }
        // Return cached result immediately if available
        dominantColorCache[imageUrl]?.let {
            dominantColor = it
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
        // Use the app-wide Coil singleton — NOT a new ImageLoader per call
        val loader = context.imageLoader
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .allowHardware(false)
            .size(80, 80) // Tiny size is enough for palette extraction
            .memoryCacheKey("dominant_color_$imageUrl")
            .diskCacheKey("dominant_color_$imageUrl")
            .build()
        val result = loader.execute(request)
        if (result is SuccessResult) {
            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
            if (bitmap != null) {
                val palette = Palette.from(bitmap)
                    .maximumColorCount(8) // 8 is plenty for ambient glow, much faster than 24
                    .generate()
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
                    val color = Color(
                        red = r * 0.28f,
                        green = g * 0.28f,
                        blue = b * 0.28f,
                        alpha = 1f
                    )
                    dominantColorCache[imageUrl] = color
                    color
                } else defaultColor
            } else defaultColor
        } else defaultColor
    } catch (_: Exception) {
        defaultColor
    }
}
