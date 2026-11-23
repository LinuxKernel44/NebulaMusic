package com.nebulamusic.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBarsPadding
import com.nebulamusic.app.glass.GlassIconButton
import com.nebulamusic.app.glass.GlassDefaults
import com.kyant.backdrop.Backdrop
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebulamusic.app.R
import com.nebulamusic.app.data.Lyrics
import com.nebulamusic.app.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private sealed interface LyricsUi {
    data object Loading : LyricsUi
    data object None : LyricsUi
    data object Failed : LyricsUi
    data class Ready(val lyrics: Lyrics) : LyricsUi
}

/**
 * Lyrics of [track] shown over the player's artwork. Synced lyrics follow [position] (the current line is highlighted
 * and kept near the top third; tapping a line seeks there; scrolling by hand pauses the following for a few seconds).
 * Unsynced lyrics are plain scrollable text.
 */
@Composable
fun LyricsPanel(
    vm: MainViewModel, track: Track, position: State<Long>, onSeek: (Long) -> Unit, full: Boolean, onToggleFull: () -> Unit,
    backdrop: Backdrop, modifier: Modifier = Modifier,
) {
    var attempt by remember { mutableIntStateOf(0) }
    val state by produceState<LyricsUi>(LyricsUi.Loading, track.id, attempt) {
        value = LyricsUi.Loading
        value = try {
            vm.lyricsFor(track)?.let { LyricsUi.Ready(it) } ?: LyricsUi.None
        } catch (e: CancellationException) { throw e } catch (e: Exception) { LyricsUi.Failed }
    }
    Box(modifier) {
        when (val s = state) {
            LyricsUi.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White, strokeWidth = 3.dp)
            LyricsUi.None -> Text(stringResource(R.string.lyrics_none), color = Muted, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            LyricsUi.Failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.lyrics_failed), color = Muted, fontSize = 16.sp)
                TextButton({ attempt++ }) { Text(stringResource(R.string.retry), color = Accent) }
            }
            is LyricsUi.Ready -> LyricsLines(s.lyrics, track.id, position, onSeek)
        }
        // Fullscreen: hides the player controls so the lyrics fill the screen; the same button brings them back.
        GlassIconButton(
            onToggleFull, backdrop, size = 44.dp,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = if (full) 16.dp else 12.dp)
        ) {
            Icon(
                if (full) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                stringResource(if (full) R.string.lyrics_exit_full else R.string.lyrics_full), tint = Color.White, modifier = Modifier.size(24.dp)
            )
        }
    }
}

/** Apple-Music-style lyric lines: big bold left-aligned text, the current line sharp and white, the others grey and increasingly blurred. */
@Composable
private fun LyricsLines(lyrics: Lyrics, trackId: String, position: State<Long>, onSeek: (Long) -> Unit) {
    val lines = lyrics.lines
    val ls = rememberLazyListState()
    val current by remember(lyrics) {
        derivedStateOf {
            if (!lyrics.synced) -1 else {
                val p = position.value + 250 // light up a hair early: reading takes a moment
                lines.indexOfLast { (it.startMs ?: Long.MAX_VALUE) <= p }
            }
        }
    }
    var programmatic by remember { mutableStateOf(false) }
    var holdUntil by remember { mutableLongStateOf(0L) }
    // While the user browses by hand the lines are sharp so they can be read; the blur comes back with the auto-follow.
    var browsing by remember { mutableStateOf(false) }
    LaunchedEffect(ls) {
        snapshotFlow { ls.isScrollInProgress }.collect { if (it && !programmatic) { holdUntil = System.currentTimeMillis() + 4000; browsing = true } }
    }
    LaunchedEffect(holdUntil) {
        val wait = holdUntil - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        browsing = false
    }
    var first by remember(trackId) { mutableStateOf(true) }
    LaunchedEffect(current, lyrics) {
        if (current < 0 || System.currentTimeMillis() < holdUntil) return@LaunchedEffect
        programmatic = true
        try {
            val offset = -(ls.layoutInfo.viewportSize.height / 12)
            if (first) ls.scrollToItem(current, offset) else ls.animateScrollToItem(current, offset)
            first = false
        } finally { programmatic = false }
    }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(
        Modifier.fillMaxSize().padding(top = statusTop + 92.dp)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.07f to Color.Black, 0.9f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn
                )
            },
        state = ls,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 140.dp),
    ) {
        itemsIndexed(lines) { i, line ->
            // Before the first line starts, the first line is the upcoming one: sharp, but still grey.
            val distance = if (lyrics.synced) kotlin.math.abs(i - current.coerceAtLeast(0)) else 0
            val active = !lyrics.synced || i == current
            val blurTarget = if (!lyrics.synced || browsing) 0.dp else when (distance) { 0 -> 0.dp; 1 -> 1.5.dp; 2 -> 3.dp; 3 -> 4.5.dp; else -> 6.dp }
            val blurDp by animateDpAsState(blurTarget, tween(350), label = "lyricBlur")
            val color by animateColorAsState(
                if (!lyrics.synced) Color.White.copy(alpha = 0.92f) else if (active) Color.White else Color.White.copy(alpha = if (browsing) 0.7f else 0.42f),
                tween(300), label = "lyric"
            )
            val seek = line.startMs
            val fx = Modifier.blur(blurDp, BlurredEdgeTreatment.Unbounded)
            if (line.text.isBlank()) Text(
                if (lyrics.synced && active) "♪" else " ", color = color, fontSize = 30.sp,
                modifier = Modifier.fillMaxWidth().then(fx).padding(vertical = 10.dp)
            ) else Text(
                line.text, color = color, fontSize = if (lyrics.synced) 32.sp else 22.sp, lineHeight = if (lyrics.synced) 39.sp else 29.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
                    .then(fx)
                    .then(if (seek != null) Modifier.clickable(interactionSource = null, indication = null) { holdUntil = 0L; onSeek(seek) } else Modifier)
                    .padding(vertical = if (lyrics.synced) 11.dp else 5.dp)
            )
        }
    }
}

/** Top bar of the lyrics view: small cover, title and artist, then lyrics (on), like and more buttons. Drag it down to close the player. */
@Composable
fun LyricsHeader(
    track: Track, liked: Boolean, onLike: () -> Unit, onMenu: () -> Unit, onHideLyrics: () -> Unit,
    backdrop: Backdrop, swipe: DraggableState, onSwipeEnd: (Float) -> Unit, modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().statusBarsPadding()
            .draggable(swipe, Orientation.Vertical, onDragStopped = { onSwipeEnd(it) }),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.padding(top = 6.dp, bottom = 14.dp).size(width = 40.dp, height = 5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.45f)))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(track.coverArt, Modifier.size(58.dp), 8.dp, px = 200)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(track.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            GlassIconButton(onHideLyrics, backdrop, size = 40.dp, tint = GlassDefaults.Accent) {
                Icon(Icons.Rounded.Lyrics, stringResource(R.string.lyrics), tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(8.dp))
            GlassIconButton(onLike, backdrop, size = 40.dp, tint = if (liked) GlassDefaults.Accent else Color.Unspecified) {
                Icon(if (liked) Icons.Rounded.ThumbUp else Icons.Rounded.ThumbUpOffAlt, stringResource(R.string.like), tint = Color.White, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(8.dp))
            GlassIconButton(onMenu, backdrop, size = 40.dp) {
                Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}
