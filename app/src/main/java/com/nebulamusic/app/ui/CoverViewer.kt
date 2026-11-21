package com.nebulamusic.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Full-screen album art. The picture follows the finger when dragged vertically (shrinking while the black backdrop
 * fades) and either flies off or springs back on release, like the full player. Tap, back or a swipe closes it.
 */
@Composable
fun CoverViewer(coverArt: String?, onClosed: () -> Unit) {
    val session = LocalSession.current
    val url = remember(coverArt, session) { session?.coverUrl(coverArt, 1400) }
    val scope = rememberCoroutineScope()
    val enter = remember { Animatable(1f) } // 1 = hidden, 0 = shown
    val drag = remember { Animatable(0f) }  // vertical offset in px
    var height by remember { mutableFloatStateOf(2000f) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { enter.animateTo(0f, tween(280)) }

    fun closeFade() {
        if (closing) return
        closing = true
        scope.launch { enter.animateTo(1f, tween(220)); onClosed() }
    }
    fun closeFling(dir: Float) {
        if (closing) return
        closing = true
        scope.launch { drag.animateTo(dir * height, tween(240)); onClosed() }
    }
    BackHandler { closeFade() }

    Box(
        Modifier.fillMaxSize().onSizeChanged { height = it.height.toFloat() }
            .graphicsLayer { alpha = 1f - enter.value.coerceIn(0f, 1f) }
    ) {
        // Separate fading scrim so the picture itself keeps full opacity while it is dragged.
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = 1f - (abs(drag.value) / (height * 0.45f)).coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.96f))
        )
        Box(
            Modifier.fillMaxSize()
                .draggable(
                    rememberDraggableState { d -> if (!closing) scope.launch { drag.snapTo(drag.value + d) } },
                    Orientation.Vertical,
                    onDragStopped = { v ->
                        if (closing) return@draggable
                        val far = abs(drag.value) > height * 0.12f || abs(v) > 1200f
                        if (far) closeFling(if (abs(v) > 1200f) sign(v) else sign(drag.value))
                        else scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 400f)) }
                    }
                )
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { closeFade() },
            contentAlignment = Alignment.Center
        ) {
            if (url != null) AsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    val f = (abs(drag.value) / height).coerceIn(0f, 1f)
                    translationY = drag.value
                    val s = (1f - 0.3f * f) * (0.9f + 0.1f * (1f - enter.value.coerceIn(0f, 1f)))
                    scaleX = s; scaleY = s
                }
            )
        }
    }
}
