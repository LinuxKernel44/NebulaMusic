@file:OptIn(ExperimentalLayoutApi::class)

package com.nebulamusic.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebulamusic.app.R
import com.nebulamusic.app.data.Track
import com.nebulamusic.app.glass.GlassButton
import com.nebulamusic.app.glass.GlassIconButton

@Composable
fun TrackColumn(
    tracks: List<Track>,
    vm: MainViewModel,
    env: TrackEnv,
    onMenu: (Track) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 8.dp),
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    numbered: Boolean = false,
    onEndReached: (() -> Unit)? = null,
    stateKey: String = "tracks",
) {
    val listState = rememberKeptListState(stateKey)
    RegisterSelectable(tracks)
    if (onEndReached != null) {
        val near by remember { derivedStateOf { listState.layoutInfo.let { l -> (l.visibleItemsInfo.lastOrNull()?.index ?: 0) >= l.totalItemsCount - 12 } } }
        LaunchedEffect(near, tracks.size) { if (near) onEndReached() }
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = contentPadding) {
            if (header != null) item { header() }
            itemsIndexed(tracks, key = { i, t -> "${t.id}#$i" }) { i, t ->
                TrackRow(
                    t, current = env.ui.trackId == t.id, playing = env.ui.isPlaying && env.ui.trackId == t.id,
                    favorite = t.id in env.favs, downloaded = env.dls.containsKey(t.id), progress = env.progress[t.id],
                    onClick = { vm.play(tracks, i) }, onMenu = { onMenu(t) },
                    showCover = !numbered, numberLabel = if (numbered) (if (t.trackNo > 0) t.trackNo.toString() else (i + 1).toString()) else null,
                )
            }
            if (footer != null) item { footer() }
        }
        ListScrollbar(listState, contentPadding.calculateTopPadding(), contentPadding.calculateBottomPadding())
    }
}

@Composable
fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
fun EmptyText(text: String) = Centered {
    Text(text, color = Muted, textAlign = TextAlign.Center, fontSize = 15.sp)
}

/** Spinner / error + retry / content, depending on a [Load] state. */
@Composable
fun <T> LoadView(state: Load<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        is Load.Loading -> Centered {
            CircularProgressIndicator(color = Accent)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.loading), color = Muted)
        }
        is Load.Err -> Centered {
            Text(state.message, color = Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)) {
                Text(stringResource(R.string.retry))
            }
        }
        is Load.Ok -> content(state.value)
    }
}

/** Runs [block] against the server for [key] and exposes the result; [reload] runs it again. */
@Composable
fun <T> rememberLoad(vm: MainViewModel, key: Any?, block: suspend (com.nebulamusic.app.data.Session) -> T): Pair<Load<T>, () -> Unit> {
    var tick by remember { mutableIntStateOf(0) }
    val state by produceState<Load<T>>(Load.Loading, key, tick) {
        value = Load.Loading
        value = try { Load.Ok(vm.api(block)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { vm.errorOf(e) }
    }
    return state to { tick++ }
}

/** Measures a floating header (status bar included) so the list underneath can pad for it. */
@Composable
fun Modifier.measureHeight(onHeight: (Dp) -> Unit): Modifier {
    val density = LocalDensity.current
    return this.onSizeChanged { onHeight(with(density) { it.height.toDp() }) }
}

@Composable
fun GlassTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, letterSpacing = 0.5.sp, modifier = modifier)
}

@Composable
fun GlassLabel(text: String, bold: Boolean = true, size: Int = 15) {
    BasicText(
        text, maxLines = 1,
        style = TextStyle(color = Color.White, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp))
}

/** Header with a back arrow, a title and optional trailing buttons. */
@Composable
fun BackHeader(
    title: String, onBack: () -> Unit, backdrop: com.kyant.backdrop.Backdrop,
    modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GlassIconButton(onBack, backdrop, size = 46.dp) { Icon(Icons.Rounded.ArrowBack, null, tint = Color.White) }
        Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        trailing()
    }
}

@Composable
fun PlayAllRow(tracks: List<Track>, vm: MainViewModel) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { if (tracks.isNotEmpty()) vm.play(tracks, 0) },
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
        ) {
            Icon(Icons.Rounded.PlayArrow, null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.play_all), fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = { if (tracks.isNotEmpty()) vm.play(tracks.shuffled(), 0) }) {
            Icon(Icons.Rounded.Shuffle, null, tint = Color.White)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.shuffle_play), color = Color.White)
        }
    }
}

/** Spotify-style action row: quiet icons on the left, shuffle and a big round play button on the right. */
@Composable
fun ActionBar(onShuffle: () -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier, leading: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Spacer(Modifier.weight(1f))
        BarIcon(Icons.Rounded.Shuffle, stringResource(R.string.shuffle_play), Color.White, onShuffle)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.size(62.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Accent).clickable(onClick = onPlay),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.play_all), tint = Color.Black, modifier = Modifier.size(38.dp)) }
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
fun BarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(28.dp)) }
}

/** Download / downloading / delete-downloaded icon for a list of songs. */
@Composable
fun DownloadAction(tracks: List<Track>, vm: MainViewModel, env: TrackEnv) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val allDownloaded = tracks.isNotEmpty() && tracks.all { it.id in env.dls }
    val downloading = tracks.any { it.id in env.progress }
    when {
        downloading -> BarIcon(Icons.Rounded.Downloading, stringResource(R.string.downloading), Accent) { }
        allDownloaded -> BarIcon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete_downloaded), Accent) {
            vm.deleteDownloads(tracks)
            android.widget.Toast.makeText(ctx, R.string.downloads_removed, android.widget.Toast.LENGTH_SHORT).show()
        }
        else -> BarIcon(Icons.Rounded.Download, stringResource(R.string.download), Color.White) { vm.download(tracks) }
    }
}
