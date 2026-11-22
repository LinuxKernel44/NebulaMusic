@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nebulamusic.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebulamusic.app.R
import com.nebulamusic.app.data.*
import kotlin.math.hypot

/** Display levels of a Library category: 1 = list, 2..4 = grid with that many columns. */
const val MIN_LEVEL = 1
const val MAX_LEVEL = 4

/**
 * Pinch with two fingers to change the number of columns by one step: spread = bigger covers (fewer columns), pinch = smaller.
 * Ignored when a scroll or fling is running when the second finger lands, so scrolling never triggers it.
 */
private fun Modifier.pinchLevels(level: State<Int>, onLevel: (Int) -> Unit, scrolling: () -> Boolean) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var base = 0f
        var active = false
        var ignored = false
        var stepped = false
        do {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val down = ev.changes.filter { it.pressed }
            if (down.size >= 2 && !ignored) {
                val d = hypot(down[0].position.x - down[1].position.x, down[0].position.y - down[1].position.y)
                if (!active) {
                    if (scrolling()) ignored = true else { active = true; base = d }
                } else if (base > 0f && !stepped) {
                    // One step per pinch: lift the fingers and pinch again to go further.
                    val r = d / base
                    if (r > 1.25f && level.value > MIN_LEVEL) { onLevel(level.value - 1); stepped = true }
                    else if (r < 0.8f && level.value < MAX_LEVEL) { onLevel(level.value + 1); stepped = true }
                }
                if (active) ev.changes.forEach { it.consume() }
            }
        } while (ev.changes.any { it.pressed })
    }
}

/**
 * One Library category as a list (level 1) or a grid of 2..4 columns, with pinch-to-resize. Rows and tiles are supplied
 * by the caller; [header] spans the whole width on top.
 */
@Composable
fun <T> LibraryCollection(
    level: Int, onLevel: (Int) -> Unit, items: List<T>, itemKey: (Int, T) -> Any, stateKey: String,
    headerH: Dp, bottom: Dp, modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null, onEnd: (() -> Unit)? = null,
    row: @Composable (Int, T) -> Unit, tile: @Composable (Int, T, Int) -> Unit,
) {
    val cols = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
    val listState = rememberKeptListState(stateKey)
    val gridState = rememberKeptGridState("$stateKey-grid")
    val levelState = rememberUpdatedState(cols)
    val onLevelState = rememberUpdatedState(onLevel)

    if (onEnd != null) {
        val near by remember {
            derivedStateOf {
                if (levelState.value == 1) listState.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 12 }
                else gridState.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 12 }
            }
        }
        LaunchedEffect(near, items.size) { if (near) onEnd() }
    }

    // Keep the same spot when switching between the list and a grid.
    var prev by remember { mutableIntStateOf(cols) }
    LaunchedEffect(cols) {
        if ((prev == 1) != (cols == 1)) {
            val idx = if (prev == 1) listState.firstVisibleItemIndex else gridState.firstVisibleItemIndex
            if (cols == 1) listState.scrollToItem(idx) else gridState.scrollToItem(idx)
        }
        prev = cols
    }

    Box(
        modifier.fillMaxSize().pinchLevels(levelState, { onLevelState.value(it) }) {
            if (levelState.value == 1) listState.isScrollInProgress else gridState.isScrollInProgress
        }
    ) {
        if (cols == 1) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = headerH, bottom = bottom)) {
                if (header != null) item(key = "lib-header") { header() }
                itemsIndexed(items, key = { i, t -> itemKey(i, t) }) { i, t -> row(i, t) }
            }
            ListScrollbar(listState, headerH, bottom)
        } else {
            LazyVerticalGrid(
                state = gridState, columns = GridCells.Fixed(cols),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = headerH, bottom = bottom),
                horizontalArrangement = Arrangement.spacedBy(if (cols >= 4) 8.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (cols >= 4) 6.dp else 10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (header != null) item(key = "lib-header", span = { GridItemSpan(maxLineSpan) }) { header() }
                itemsIndexed(items, key = { i, t -> itemKey(i, t) }) { i, t ->
                    Box(Modifier.animateItem()) { tile(i, t, cols) }
                }
            }
            GridScrollbar(gridState, headerH, bottom)
        }
    }
}

private fun titleSize(cols: Int) = when (cols) { 2 -> 15.sp; 3 -> 13.sp; else -> 11.sp }
private fun subSize(cols: Int) = when (cols) { 2 -> 12.sp; 3 -> 11.sp; else -> 9.sp }
private fun corner(cols: Int) = when (cols) { 2 -> 14.dp; 3 -> 12.dp; else -> 9.dp }

@Composable
private fun TileText(title: String, subtitle: String, cols: Int, center: Boolean = false, titleColor: Color = Color.White) {
    val align = if (center) TextAlign.Center else TextAlign.Start
    Text(
        title, color = titleColor, fontWeight = FontWeight.Bold, fontSize = titleSize(cols), maxLines = 1, overflow = TextOverflow.Ellipsis,
        textAlign = align, modifier = Modifier.fillMaxWidth().padding(top = 5.dp, start = 2.dp, end = 2.dp)
    )
    if (subtitle.isNotBlank()) Text(
        subtitle, color = Muted, fontSize = subSize(cols), maxLines = 1, overflow = TextOverflow.Ellipsis,
        textAlign = align, modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
    )
}

@Composable
fun AlbumTile(a: Album, cols: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(corner(cols))).combinedClickable(onClick = onClick)) {
        Cover(a.coverArt, Modifier.fillMaxWidth().aspectRatio(1f), corner(cols), px = if (cols >= 3) 300 else 400)
        TileText(a.name, listOf(a.artist, a.year.takeIf { it > 0 }?.toString()).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "), cols)
    }
}

@Composable
fun ArtistTile(a: Artist, cols: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(corner(cols))).combinedClickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircleCover(a.coverArt, Modifier.fillMaxWidth().aspectRatio(1f))
        TileText(
            a.name, if (a.albumCount > 0 && cols < 4) pluralStringResource(R.plurals.n_albums, a.albumCount, a.albumCount) else "", cols, center = true
        )
    }
}

@Composable
fun PlaylistTile(p: Playlist, cols: Int, pinned: Boolean = false, onTogglePin: (() -> Unit)? = null, onClick: () -> Unit) {
    SwipeToPin(onTogglePin != null, pinned, { onTogglePin?.invoke() }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(corner(cols))).combinedClickable(onClick = onClick)) {
            Box {
                if (p.id == LIKED_ID) LikedCover(Modifier.fillMaxWidth().aspectRatio(1f), corner(cols))
                else Cover(p.coverArt, Modifier.fillMaxWidth().aspectRatio(1f), corner(cols), px = if (cols >= 3) 300 else 400)
                if (pinned || p.id == LIKED_ID) Icon(
                    Icons.Rounded.PushPin, null, tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(if (cols >= 4) 14.dp else 18.dp)
                )
            }
            TileText(p.name, pluralStringResource(R.plurals.n_songs, p.songCount, p.songCount), cols)
        }
    }
}

/** Genres have no artwork: a square tile whose colour comes from the name. */
@Composable
fun GenreTile(g: Genre, cols: Int, onClick: () -> Unit) {
    val hue = ((g.name.fold().hashCode() and 0x7fffffff) % 360).toFloat()
    val c1 = Color.hsv(hue, 0.55f, 0.55f)
    val c2 = Color.hsv((hue + 40f) % 360f, 0.65f, 0.28f)
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(corner(cols)))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(c1, c2)))
            .combinedClickable(onClick = onClick).padding(if (cols >= 4) 6.dp else 10.dp)
    ) {
        Icon(Icons.Rounded.Sell, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.TopEnd).size(if (cols >= 4) 14.dp else 20.dp))
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(g.name, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (titleSize(cols).value + 1f).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (cols < 4) Text(pluralStringResource(R.plurals.n_songs, g.songCount, g.songCount), color = Color.White.copy(alpha = 0.8f), fontSize = subSize(cols), maxLines = 1)
        }
    }
}

@Composable
fun TrackTile(t: Track, cols: Int, current: Boolean, playing: Boolean, onClick: () -> Unit) {
    val sel = LocalSelection.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val picked = sel.active && t.id in sel.selected
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(corner(cols))).combinedClickable(
            onClick = { if (sel.active) sel.toggle(t.id) else onClick() },
            onLongClick = {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                if (sel.active) sel.toggle(t.id) else sel.start(t.id)
            }
        )
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Cover(t.coverArt, Modifier.fillMaxSize(), corner(cols), px = if (cols >= 3) 300 else 400)
            if (picked) PickedMark(Modifier.fillMaxSize().clip(RoundedCornerShape(corner(cols))))
            else if (current) Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(corner(cols))).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center
            ) { EqBars(playing, Color.White, if (cols >= 4) 18.dp else 28.dp) }
        }
        TileText(t.title, t.artist, cols, titleColor = if (current) Accent else Color.White)
    }
}
