package com.nebulamusic.app.ui

import androidx.compose.animation.animateColorAsState
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
fun LyricsPanel(vm: MainViewModel, track: Track, position: State<Long>, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var attempt by remember { mutableIntStateOf(0) }
    val state by produceState<LyricsUi>(LyricsUi.Loading, track.id, attempt) {
        value = LyricsUi.Loading
        value = try {
            vm.lyricsFor(track)?.let { LyricsUi.Ready(it) } ?: LyricsUi.None
        } catch (e: CancellationException) { throw e } catch (e: Exception) { LyricsUi.Failed }
    }
    Box(modifier.background(Color.Black.copy(alpha = 0.64f))) {
        when (val s = state) {
            LyricsUi.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White, strokeWidth = 3.dp)
            LyricsUi.None -> Text(stringResource(R.string.lyrics_none), color = Muted, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            LyricsUi.Failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.lyrics_failed), color = Muted, fontSize = 16.sp)
                TextButton({ attempt++ }) { Text(stringResource(R.string.retry), color = Accent) }
            }
            is LyricsUi.Ready -> LyricsLines(s.lyrics, track.id, position, onSeek)
        }
    }
}

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
    LaunchedEffect(ls) {
        snapshotFlow { ls.isScrollInProgress }.collect { if (it && !programmatic) holdUntil = System.currentTimeMillis() + 4000 }
    }
    var first by remember(trackId) { mutableStateOf(true) }
    LaunchedEffect(current, lyrics) {
        if (current < 0 || System.currentTimeMillis() < holdUntil) return@LaunchedEffect
        programmatic = true
        try {
            val offset = -(ls.layoutInfo.viewportSize.height / 4)
            if (first) ls.scrollToItem(current, offset) else ls.animateScrollToItem(current, offset)
            first = false
        } finally { programmatic = false }
    }
    LazyColumn(
        Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
            drawContent()
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 0.09f to Color.Black, 0.88f to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn
            )
        },
        state = ls,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 76.dp, bottom = 120.dp),
    ) {
        itemsIndexed(lines) { i, line ->
            val active = if (lyrics.synced) i == current else true
            val color by animateColorAsState(
                if (!lyrics.synced) Color.White.copy(alpha = 0.92f) else if (active) Color.White else Color.White.copy(alpha = 0.4f), tween(250), label = "lyric"
            )
            val seek = line.startMs
            if (line.text.isBlank()) Text(
                if (lyrics.synced && active) "♪" else " ", color = color, fontSize = 22.sp, textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(vertical = if (lyrics.synced) 8.dp else 6.dp)
            ) else Text(
                line.text, color = color, fontSize = if (lyrics.synced) 26.sp else 19.sp, lineHeight = if (lyrics.synced) 33.sp else 26.sp,
                fontWeight = if (lyrics.synced) FontWeight.Bold else FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
                    .then(if (seek != null) Modifier.clickable(interactionSource = null, indication = null) { onSeek(seek) } else Modifier)
                    .padding(vertical = if (lyrics.synced) 8.dp else 3.dp)
            )
        }
    }
}
