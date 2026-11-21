package com.nebulamusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.nebulamusic.app.glass.LocalGlassBackdrop

/** Height taken at the bottom by the floating mini-player + tab bar, so lists can pad for it. */
val LocalBottomInset = compositionLocalOf { 0.dp }

@Composable
fun glassBackdrop(): LayerBackdrop = LocalGlassBackdrop.current ?: error("No glass backdrop provided")

/**
 * The layer every floating glass element samples. It must be a sibling of the floating elements,
 * never their parent, otherwise the glass would refract itself.
 */
@Composable
fun ScreenBackdrop(modifier: Modifier = Modifier.fillMaxSize(), content: @Composable BoxScope.() -> Unit) {
    Box(modifier.layerBackdrop(glassBackdrop()).background(Bg)) {
        BackgroundArt()
        content()
    }
}

// ------------------------------------------------------------------------------------------------
// Rows used inside glass menus and sheets
// ------------------------------------------------------------------------------------------------

@Composable
fun GlassMenuRow(
    icon: ImageVector?,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    minWidth: Dp = 0.dp,
    fill: Boolean = true,
    vPad: Dp = 14.dp,
) {
    Row(
        modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier.widthIn(min = minWidth))
            .clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = vPad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color)
            Spacer(Modifier.width(16.dp))
        }
        BasicText(text, style = TextStyle(color = color, fontSize = 16.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

@Composable
fun GlassSheetTitle(title: String, subtitle: String? = null, content: @Composable (() -> Unit)? = null) {
    Row(Modifier.padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        content?.let { it(); Spacer(Modifier.width(14.dp)) }
        Column {
            BasicText(
                title, maxLines = 1,
                style = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            )
            if (subtitle != null) BasicText(
                subtitle, maxLines = 1, style = TextStyle(color = Muted, fontSize = 13.sp)
            )
        }
    }
}

@Composable
fun GlassDivider() {
    Box(
        Modifier.padding(vertical = 6.dp, horizontal = 18.dp).fillMaxWidth().height(1.dp)
            .background(Color.White.copy(alpha = 0.12f))
    )
}

/** Soft fade behind a floating header so titles stay readable over scrolling content. */
@Composable
fun TopScrim(height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(height + 28.dp).background(
            Brush.verticalGradient(listOf(Bg.copy(alpha = 0.92f), Bg.copy(alpha = 0.72f), Color.Transparent))
        )
    )
}

/** Soft fade behind the floating bottom controls. */
@Composable
fun BottomScrim(height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(height + 36.dp).background(
            Brush.verticalGradient(listOf(Color.Transparent, Bg.copy(alpha = 0.7f), Bg.copy(alpha = 0.92f)))
        )
    )
}
