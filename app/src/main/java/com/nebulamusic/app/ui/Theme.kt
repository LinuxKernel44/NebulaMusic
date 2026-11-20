@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.nebulamusic.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.nebulamusic.app.R

/** Violet by default, then tinted by the artwork of the playing track. */
val Accent: Color get() = com.nebulamusic.app.glass.GlassDefaults.Accent
val Bg = Color(0xFF0A0A0D)
val SurfaceLo = Color(0xFF131318)
val SurfaceHi = Color(0xFF1F1F27)
val Muted = Color(0xFF9A9AA8)
val CardDark = Color(0xFF1C1C1E)

/** Geometric rounded sans close to the one used by the reference player. */
val Figtree = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        Font(R.font.figtree, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

private val Scheme get() = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    background = Bg,
    onBackground = Color.White,
    surface = SurfaceLo,
    onSurface = Color.White,
    surfaceVariant = SurfaceHi,
    onSurfaceVariant = Muted,
    surfaceContainer = SurfaceLo,
    surfaceContainerHigh = SurfaceHi,
    surfaceContainerHighest = SurfaceHi,
)

private fun Typography.withFamily(f: FontFamily) = copy(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f), headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f), bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f),
)

@Composable
fun NebulaTheme(content: @Composable () -> Unit) {
    // The app is intentionally dark-only, like the reference player.
    MaterialTheme(colorScheme = Scheme, typography = Typography().withFamily(Figtree)) {
        Surface(color = Bg, contentColor = Color.White) {
            ProvideTextStyle(TextStyle(fontFamily = Figtree), content)
        }
    }
}
