package com.nebulamusic.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.nebulamusic.app.data.Session

/**
 * Theme derived from the artwork of the track that is loaded in the player (even when paused):
 * [blur] is a tiny pre-blurred copy of the cover drawn behind every screen, [accent] tints the controls.
 */
class ArtTheme(val key: String, val accent: Color, val blur: ImageBitmap)

/** Null = nothing is loaded, or the track has no usable cover: the default dark background is used. */
val LocalArtTheme = compositionLocalOf<ArtTheme?> { null }

/** The connection used to build cover URLs. */
val LocalSession = compositionLocalOf<Session?> { null }

/** Makes a palette colour readable as an accent on a dark background (keeps its hue). */
fun accentFrom(c: Color?): Color {
    if (c == null) return com.nebulamusic.app.glass.GlassDefaults.DefaultAccent
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), hsl)
    hsl[1] = hsl[1].coerceIn(0.45f, 0.9f)
    hsl[2] = hsl[2].coerceIn(0.62f, 0.78f)
    return Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
}

/** Default dark background, or the blurred artwork of the current track (cross-faded when the track changes). */
@Composable
fun BackgroundArt(modifier: Modifier = Modifier.fillMaxSize()) {
    val art = LocalArtTheme.current
    Crossfade(art, animationSpec = tween(700), label = "bg", modifier = modifier) { a ->
        if (a == null) Box(Modifier.fillMaxSize().background(Bg))
        else Box(Modifier.fillMaxSize().background(Bg)) {
            Image(
                a.blur, null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize()
            )
            // Keeps text readable whatever the cover looks like.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.30f), 0.5f to Bg.copy(alpha = 0.42f), 1f to Bg.copy(alpha = 0.80f)
                    )
                )
            )
        }
    }
}
