package com.nebulamusic.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val HIT_W = 30.dp      // touch target width (the visible bar is much thinner)
private val HIT_H = 72.dp      // touch target height = the biggest the handle gets
private val BAR_H = 52.dp      // resting handle length
private val BAR_H_ACTIVE = 68.dp
private val BAR_W = 5.dp
private val BAR_W_ACTIVE = 12.dp

/** Draggable scrollbar handle for a lazy list. Place it in the same Box as the list. */
@Composable
fun BoxScope.ListScrollbar(state: LazyListState, topPadding: Dp = 0.dp, bottomPadding: Dp = 0.dp) {
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    FastScrollbar(
        total = { state.layoutInfo.totalItemsCount },
        visible = { state.layoutInfo.visibleItemsInfo.size },
        first = { state.firstVisibleItemIndex },
        firstFraction = {
            val it0 = state.layoutInfo.visibleItemsInfo.firstOrNull()
            if (it0 == null || it0.size <= 0) 0f else (state.firstVisibleItemScrollOffset.toFloat() / it0.size).coerceIn(0f, 1f)
        },
        atStart = { !state.canScrollBackward },
        atEnd = { !state.canScrollForward },
        scrolling = { state.isScrollInProgress },
        scrollTo = { i -> job?.cancel(); job = scope.launch { state.scrollToItem(i) } },
        topPadding = topPadding, bottomPadding = bottomPadding,
    )
}

/** Draggable scrollbar handle for a lazy grid. Place it in the same Box as the grid. */
@Composable
fun BoxScope.GridScrollbar(state: LazyGridState, topPadding: Dp = 0.dp, bottomPadding: Dp = 0.dp) {
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    FastScrollbar(
        total = { state.layoutInfo.totalItemsCount },
        visible = { state.layoutInfo.visibleItemsInfo.size },
        first = { state.firstVisibleItemIndex },
        firstFraction = {
            val it0 = state.layoutInfo.visibleItemsInfo.firstOrNull()
            if (it0 == null || it0.size.height <= 0) 0f
            else (state.firstVisibleItemScrollOffset.toFloat() / it0.size.height).coerceIn(0f, 1f)
        },
        atStart = { !state.canScrollBackward },
        atEnd = { !state.canScrollForward },
        scrolling = { state.isScrollInProgress },
        scrollTo = { i -> job?.cancel(); job = scope.launch { state.scrollToItem(i) } },
        topPadding = topPadding, bottomPadding = bottomPadding,
    )
}

@Composable
private fun BoxScope.FastScrollbar(
    total: () -> Int,
    visible: () -> Int,
    first: () -> Int,
    firstFraction: () -> Float,
    atStart: () -> Boolean,
    atEnd: () -> Boolean,
    scrolling: () -> Boolean,
    scrollTo: (Int) -> Unit,
    topPadding: Dp,
    bottomPadding: Dp,
) {
    val canScroll by remember { derivedStateOf { total() > visible() && visible() > 0 } }

    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    val isScrolling = scrolling()

    // Shown while scrolling or dragging; hides a moment after.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(isScrolling, dragging) {
        if (isScrolling || dragging) shown = true else { delay(1200); shown = false }
    }
    // Grows while the handle is used; shrinks back after the finger lets go, with a short delay first.
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(dragging) {
        if (dragging) expanded = true else { delay(700); expanded = false }
    }
    val alpha by animateFloatAsState(if (shown || dragging) 1f else 0f, tween(if (shown) 120 else 300), label = "sbAlpha")
    val grow by animateFloatAsState(if (expanded) 1f else 0f, tween(if (expanded) 220 else 380), label = "sbGrow")

    // A drag must not outlive the handle (e.g. list replaced under the finger).
    DisposableEffect(Unit) { onDispose { dragging = false } }
    if (!canScroll) return

    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier.align(Alignment.CenterEnd).fillMaxSize().padding(top = topPadding, bottom = bottomPadding)
    ) {
        val trackPx = with(density) { (maxHeight - HIT_H).coerceAtLeast(1.dp).toPx() }
        val maxFirst = (total() - visible()).coerceAtLeast(1)
        val scrollFrac = when {
            atStart() -> 0f
            atEnd() -> 1f
            else -> ((first() + firstFraction()) / maxFirst).coerceIn(0f, 1f)
        }
        val frac = if (dragging) dragFrac else scrollFrac
        var placedTopPx by remember { mutableFloatStateOf(0f) }
        val active = alpha > 0.05f

        Box(
            Modifier.align(Alignment.TopEnd)
                .offset(y = with(density) { (trackPx * frac).toDp() })
                .width(HIT_W).height(HIT_H)
                .onGloballyPositioned { placedTopPx = it.positionInParent().y }
                .drawBehind {
                    val w = (BAR_W + (BAR_W_ACTIVE - BAR_W) * grow).toPx()
                    val h = (BAR_H + (BAR_H_ACTIVE - BAR_H) * grow).toPx()
                    drawRoundRect(
                        Color.White.copy(alpha = (0.55f + 0.35f * grow) * alpha),
                        topLeft = Offset(size.width - w - 4.dp.toPx(), (size.height - h) / 2f),
                        size = Size(w, h), cornerRadius = CornerRadius(w / 2f),
                    )
                }
                // Only this small box takes touches: everything else keeps scrolling the list normally.
                .then(if (active) Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val grab = down.position.y
                        dragging = true
                        try {
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Main)
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) { ch.consume(); break }
                                ch.consume()
                                // Handle top = finger position (in the track) minus where on the handle it was grabbed.
                                val top = placedTopPx + ch.position.y - grab
                                val f = (top / trackPx).coerceIn(0f, 1f)
                                dragFrac = f
                                val n = total()
                                val mf = (n - visible()).coerceAtLeast(0)
                                scrollTo(if (f >= 0.995f) (n - 1).coerceAtLeast(0) else (f * mf).toInt())
                            }
                        } finally {
                            dragging = false
                        }
                    }
                } else Modifier)
        )
    }
}
