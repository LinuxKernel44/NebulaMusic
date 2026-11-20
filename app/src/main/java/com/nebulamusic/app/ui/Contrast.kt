package com.nebulamusic.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Near-black ink used for text on light backgrounds. */
val DarkInk = Color(0xFF0C0C10)

/**
 * Holds a small snapshot of what is really drawn behind the player's text (gradient, blurred
 * artwork, artwork fade...). Each text asks for the luminance of its own rectangle and picks the
 * most readable colour, black or white.
 */
class BackdropSampler {
    private var bitmap: Bitmap? = null
    private var srcW = 1f
    private var srcH = 1f

    /** Bumped every time a new snapshot is available so dependants recompute. */
    var version by mutableIntStateOf(0)
        private set

    suspend fun capture(layer: GraphicsLayer) {
        val full = runCatching { layer.toImageBitmap().asAndroidBitmap() }.getOrNull() ?: return
        val small = withContext(Dispatchers.Default) {
            val soft = full.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
            Bitmap.createScaledBitmap(soft, (soft.width / 6).coerceAtLeast(1), (soft.height / 6).coerceAtLeast(1), true)
                .also { srcW = soft.width.toFloat(); srcH = soft.height.toFloat() }
        } ?: return
        bitmap = small
        version++
    }

    /** Mean relative luminance (0..1) of [r], expressed in the snapshot's coordinate space. */
    fun luminance(r: Rect): Float? {
        val b = bitmap ?: return null
        val sx = b.width / srcW
        val sy = b.height / srcH
        val x0 = (r.left * sx).toInt().coerceIn(0, b.width - 1)
        val x1 = (r.right * sx).toInt().coerceIn(x0 + 1, b.width)
        val y0 = (r.top * sy).toInt().coerceIn(0, b.height - 1)
        val y1 = (r.bottom * sy).toInt().coerceIn(y0 + 1, b.height)
        var sum = 0f
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            sum += Color(b.getPixel(x, y)).luminance()
            n++
        }
        return if (n == 0) null else sum / n
    }
}

/**
 * A box whose content receives the best text colour (black or white) for the part of the
 * backdrop it sits on. The colour changes with a short animation.
 */
@Composable
fun Adaptive(
    sampler: BackdropSampler,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.(Color) -> Unit,
) {
    var rect by remember { mutableStateOf<Rect?>(null) }
    val ver = sampler.version
    val target = remember(rect, ver) {
        val l = rect?.let { sampler.luminance(it) }
        if (l != null && l > 0.5f) DarkInk else Color.White
    }
    val color by animateColorAsState(target, tween(280), label = "adaptiveFg")
    // The measured rectangle is the content's own bounds (not the whole row), so a short title is
    // judged only against the pixels it actually covers.
    Box(modifier, contentAlignment) {
        Box(Modifier.onGloballyPositioned { rect = it.boundsInRoot() }) { content(color) }
    }
}
