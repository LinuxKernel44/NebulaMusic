@file:OptIn(ExperimentalMaterial3Api::class)

package com.nebulamusic.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import com.nebulamusic.app.glass.GlassButton
import com.nebulamusic.app.glass.GlassDefaults
import com.nebulamusic.app.glass.GlassTextField
import com.nebulamusic.app.R
import com.nebulamusic.app.data.*
import com.nebulamusic.app.player.PlayerUi

fun fmtMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun fmtSec(sec: Int) = fmtMs(sec * 1000L)

/** "FLAC 24/96", "MP3 320 kbps"... */
fun qualityLabel(t: Track): String {
    val fmt = t.format
    val spec = when {
        t.isLossless && t.bitDepth > 0 && t.samplingRate > 0 ->
            "${t.bitDepth}/${(t.samplingRate / 1000f).toString().removeSuffix(".0")}"
        t.bitRate > 0 -> "${t.bitRate} kbps"
        else -> ""
    }
    return listOf(fmt, spec).filter { it.isNotBlank() }.joinToString(" ")
}

/** Cover art from the server (or a neutral placeholder when the item has none). */
@Composable
fun Cover(coverArt: String?, modifier: Modifier = Modifier, corner: Dp = 12.dp, px: Int = 300) {
    val session = LocalSession.current
    val url = remember(coverArt, session) { session?.coverUrl(coverArt, px) }
    Box(modifier.clip(RoundedCornerShape(corner)).background(SurfaceHi), contentAlignment = Alignment.Center) {
        if (url == null) Icon(Icons.Rounded.MusicNote, null, tint = Muted.copy(alpha = 0.7f), modifier = Modifier.fillMaxSize(0.45f))
        else AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun CircleCover(coverArt: String?, modifier: Modifier = Modifier) {
    Cover(coverArt, modifier.clip(CircleShape), 100.dp)
}

/** Everything a list row needs to know about playback, favorites and downloads. */
class TrackEnv(
    val ui: PlayerUi,
    val favs: Set<String>,
    val dls: Map<String, String>,
    val progress: Map<String, Float>,
)

@Composable
fun rememberTrackEnv(vm: MainViewModel): TrackEnv {
    val ui by vm.player.state.collectAsState()
    val favs by vm.starred.collectAsState()
    val dls by vm.localPaths.collectAsState()
    val progress by vm.downloadProgress.collectAsState()
    return TrackEnv(ui, favs, dls, progress)
}

@Composable
fun QualityBadge(t: Track) {
    if (!t.isLossless) return
    val hi = t.isHiRes
    Text(
        if (hi) "Hi-Res" else "Lossless", color = if (hi) Color.Black else Color.White.copy(alpha = 0.85f),
        fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (hi) Accent else Color.White.copy(alpha = 0.16f)).padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    current: Boolean,
    playing: Boolean,
    favorite: Boolean,
    downloaded: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    showCover: Boolean = true,
    numberLabel: String? = null,
) {
    val sel = LocalSelection.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val picked = sel.active && track.id in sel.selected
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (picked) Accent.copy(alpha = 0.22f) else if (current) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .combinedClickable(
                onClick = { if (sel.active) sel.toggle(track.id) else onClick() },
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    if (sel.active) sel.toggle(track.id) else sel.start(track.id)
                }
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCover) {
            Box(Modifier.size(58.dp)) {
                Cover(track.coverArt, Modifier.fillMaxSize(), 14.dp)
                if (picked) PickedMark(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)))
                else if (current) {
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) { EqBars(playing, Color.White, 26.dp) }
                }
            }
        } else {
            Box(Modifier.width(34.dp).height(40.dp), contentAlignment = Alignment.Center) {
                if (picked) Icon(Icons.Rounded.CheckCircle, null, tint = Accent, modifier = Modifier.size(24.dp))
                else if (current) EqBars(playing, Accent, 24.dp)
                else Text(numberLabel ?: "", color = Muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.width(if (showCover) 14.dp else 8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, color = if (current && !showCover) Accent else Color.White, fontWeight = FontWeight.Bold,
                fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (showCover) Text(
                track.subtitle, color = Color.White.copy(alpha = 0.82f), fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            ) else if (track.artist.isNotBlank()) Text(
                track.artist, color = Color.White.copy(alpha = 0.72f), fontWeight = FontWeight.Medium,
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    listOf(fmtSec(track.durationSec), qualityLabel(track)).filter { it.isNotBlank() }.joinToString(" | "),
                    color = Muted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1
                )
                QualityBadge(track)
                if (favorite) Icon(Icons.Rounded.Favorite, null, tint = Accent, modifier = Modifier.size(13.dp))
                if (progress != null) {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = Accent)
                } else if (downloaded) {
                    Icon(Icons.Rounded.DownloadDone, null, tint = Muted, modifier = Modifier.size(14.dp))
                }
            }
        }
        if (!sel.active) IconButton(onClick = onMenu) {
            Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = Muted)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumCard(album: Album, modifier: Modifier = Modifier, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onClick, onLongClick = onLong)
    ) {
        Cover(album.coverArt, Modifier.fillMaxWidth().aspectRatio(1f), 14.dp)
        Text(
            album.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp)
        )
        Text(
            listOf(album.artist, album.year.takeIf { it > 0 }?.toString()).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
            color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
        )
    }
}

@Composable
fun AlbumRow(album: Album, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(album.coverArt, Modifier.size(58.dp), 14.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(album.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(album.artist, album.year.takeIf { it > 0 }?.toString()).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
    }
}

@Composable
fun ArtistRow(artist: Artist, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircleCover(artist.coverArt, Modifier.size(54.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (artist.albumCount > 0) Text(
                pluralStringResource(R.plurals.n_albums, artist.albumCount, artist.albumCount), color = Muted, fontSize = 13.sp
            )
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
    }
}

@Composable
fun PlaylistRow(p: Playlist, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(p.coverArt, Modifier.size(54.dp), 14.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.n_songs, p.songCount, p.songCount), color = Muted, fontSize = 13.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
    }
}

@Composable
private fun pluralStringResource(id: Int, count: Int, vararg args: Any): String =
    androidx.compose.ui.res.pluralStringResource(id, count, *args)

/** Multi-select mode: while active, taps on tracks toggle their selection instead of playing them. */
class SelectionState(val active: Boolean, val selected: Set<String>, val start: (String) -> Unit, val toggle: (String) -> Unit,
    val clear: () -> Unit = { },
    /** Lists currently on screen register their ids here (null = gone), so "select all" knows what to pick. */
    val setVisible: (Any, List<Track>?) -> Unit = { _, _ -> })

/** Tells the selection which tracks this list shows (for "select all"). */
@Composable
fun RegisterSelectable(tracks: List<Track>) {
    val sel = LocalSelection.current
    val key = remember { Any() }
    DisposableEffect(tracks) {
        sel.setVisible(key, tracks)
        onDispose { sel.setVisible(key, null) }
    }
}

val LocalSelection = compositionLocalOf { SelectionState(false, emptySet(), { }, { }, { }) }

/** Round tick shown on the cover of a selected track. */
@Composable
internal fun PickedMark(modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.CheckCircle, null, tint = Accent, modifier = Modifier.size(30.dp))
    }
}

/** Rows of the track menu, rendered inside a [com.nebulamusic.app.glass.GlassSheet]. */
@Composable
fun TrackMenuContent(
    track: Track, vm: MainViewModel, onDismiss: () -> Unit, onAddToPlaylist: (Track) -> Unit,
    onSave: (Track) -> Unit = {}, onShare: (Track) -> Unit = {}, onSelect: (Track) -> Unit = {},
    onGoAlbum: (String) -> Unit = {}, onGoArtist: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val favs by vm.starred.collectAsState()
    val dls by vm.localPaths.collectAsState()
    val progress by vm.downloadProgress.collectAsState()
    val isFav = track.id in favs
    val downloaded = dls.containsKey(track.id)
    val downloading = progress.containsKey(track.id)

    GlassSheetTitle(track.title, track.subtitle) { Cover(track.coverArt, Modifier.size(52.dp), 12.dp) }
    GlassDivider()
    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
        GlassMenuRow(Icons.Rounded.SkipNext, stringResource(R.string.play_next), {
            vm.playNext(listOf(track)); Toast.makeText(ctx, R.string.will_play_next, Toast.LENGTH_SHORT).show(); onDismiss()
        })
        GlassMenuRow(Icons.Rounded.QueueMusic, stringResource(R.string.add_queue), {
            vm.addToQueue(listOf(track)); Toast.makeText(ctx, R.string.added_queue, Toast.LENGTH_SHORT).show(); onDismiss()
        })
        GlassMenuRow(
            if (isFav) Icons.Rounded.FavoriteBorder else Icons.Rounded.Favorite,
            stringResource(if (isFav) R.string.rm_fav else R.string.add_fav),
            { vm.toggleFavorite(track.id); onDismiss() }
        )
        GlassMenuRow(Icons.Rounded.PlaylistAdd, stringResource(R.string.add_playlist), { onAddToPlaylist(track); onDismiss() })
        track.albumId?.let { id -> GlassMenuRow(Icons.Rounded.Album, stringResource(R.string.go_album), { onGoAlbum(id); onDismiss() }) }
        track.artistId?.let { id -> GlassMenuRow(Icons.Rounded.Person, stringResource(R.string.go_artist), { onGoArtist(id); onDismiss() }) }
        when {
            downloaded -> GlassMenuRow(Icons.Rounded.DeleteOutline, stringResource(R.string.delete_download), { vm.deleteDownload(track); onDismiss() })
            downloading -> GlassMenuRow(Icons.Rounded.Downloading, stringResource(R.string.downloading), { })
            else -> GlassMenuRow(Icons.Rounded.Download, stringResource(R.string.download), { vm.download(listOf(track)); onDismiss() })
        }
        GlassMenuRow(Icons.Rounded.FolderOpen, stringResource(R.string.save_to_folder), { onSave(track); onDismiss() })
        GlassMenuRow(Icons.Rounded.Share, stringResource(R.string.share), { onShare(track); onDismiss() })
        GlassMenuRow(Icons.Rounded.Checklist, stringResource(R.string.select), { onSelect(track); onDismiss() })
    }
    Spacer(Modifier.height(6.dp))
}

/** Pick a playlist for one or several tracks (tick one or more playlists, then add). */
@Composable
fun PlaylistPickerContent(tracks: List<Track>, vm: MainViewModel, onDone: () -> Unit, onCreate: () -> Unit) {
    val ctx = LocalContext.current
    val pls by vm.playlists.collectAsState()
    val playlists = (pls as? Load.Ok)?.value.orEmpty()
    var chosen by remember { mutableStateOf(setOf<String>()) }
    GlassSheetTitle(
        stringResource(R.string.add_playlist),
        if (tracks.size == 1) tracks[0].title else stringResource(R.string.n_selected, tracks.size)
    )
    GlassDivider()
    GlassMenuRow(Icons.Rounded.Add, stringResource(R.string.new_playlist), onCreate)
    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        for (p in playlists) {
            val on = p.id in chosen
            GlassMenuRow(
                if (on) Icons.Rounded.CheckCircle else Icons.Rounded.QueueMusic, "${p.name}  ·  ${p.songCount}",
                { chosen = if (on) chosen - p.id else chosen + p.id },
                color = if (on) GlassDefaults.Accent else Color.White
            )
        }
    }
    if (chosen.isNotEmpty()) {
        GlassDivider()
        GlassMenuRow(Icons.Rounded.Check, stringResource(R.string.playlists_add_n, tracks.size, chosen.size), {
            vm.addAllToPlaylists(chosen, tracks.map { it.id })
            Toast.makeText(ctx, ctx.getString(R.string.playlists_added_n, tracks.size, chosen.size), Toast.LENGTH_SHORT).show()
            onDone()
        }, color = GlassDefaults.Accent)
    }
    Spacer(Modifier.height(6.dp))
}

/** Content of the rename / create playlist dialog (shown inside a glass dialog). */
@Composable
fun NameDialogContent(
    title: String,
    initial: String,
    confirm: String,
    backdrop: com.kyant.backdrop.Backdrop,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    BasicText(title, style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold))
    Spacer(Modifier.height(14.dp))
    GlassTextField(
        value = text, onValueChange = { text = it }, placeholder = stringResource(R.string.playlist_name),
        backdrop = backdrop, modifier = Modifier.fillMaxWidth(), imeAction = ImeAction.Done,
        onImeAction = { onConfirm(text) },
    )
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        GlassButton(onDismiss, backdrop, height = 46.dp) {
            BasicText(stringResource(R.string.cancel), style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        }
        GlassButton({ onConfirm(text) }, backdrop, height = 46.dp, tint = GlassDefaults.Accent) {
            BasicText(confirm, style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
fun ConfirmDialogContent(
    title: String,
    message: String,
    confirm: String,
    backdrop: com.kyant.backdrop.Backdrop,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    BasicText(title, style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold))
    Spacer(Modifier.height(10.dp))
    BasicText(message, style = TextStyle(color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp))
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        GlassButton(onDismiss, backdrop, height = 46.dp) {
            BasicText(stringResource(R.string.cancel), style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        }
        GlassButton(onConfirm, backdrop, height = 46.dp, tint = GlassDefaults.Accent) {
            BasicText(confirm, style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        }
    }
}

/** "Now playing" equalizer: bars dance while playing and ease down to a flat line when paused. */
@Composable
fun EqBars(playing: Boolean, color: Color, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val amp = remember { androidx.compose.animation.core.Animatable(if (playing) 1f else 0f) }
    val phase = remember { androidx.compose.animation.core.Animatable(0f) }
    val still = com.nebulamusic.app.glass.GlassDefaults.light // lighter mode: bars keep their shape, nothing redraws
    LaunchedEffect(playing, still) {
        if (playing && still) { amp.snapTo(0.6f) }
        else if (playing) {
            launch { amp.animateTo(1f, androidx.compose.animation.core.tween(350)) }
            // ~6 steps per second: a 60 fps loop would redraw every glass layer on screen 60 times a second.
            while (true) { phase.snapTo(phase.value + 0.16f); kotlinx.coroutines.delay(160) }
        } else {
            amp.animateTo(0f, androidx.compose.animation.core.tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    val speeds = remember { floatArrayOf(1f, 2f, 1f, 3f, 2f) }
    val offsets = remember { floatArrayOf(0f, 1.3f, 2.6f, 0.7f, 4.1f) }
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val n = 5
        val barW = this.size.width * 0.12f
        val gap = (this.size.width * 0.8f - barW * n) / (n - 1)
        val left = this.size.width * 0.1f
        val minH = barW
        val maxH = this.size.height * 0.8f
        val a = amp.value
        val ph = phase.value
        for (i in 0 until n) {
            val w = 0.5f + 0.5f * kotlin.math.sin(2.0 * Math.PI * (ph * speeds[i]) + offsets[i]).toFloat()
            val h = minH + (maxH - minH) * a * (0.15f + 0.85f * w)
            val x = left + i * (barW + gap)
            drawRoundRect(
                color, androidx.compose.ui.geometry.Offset(x, (this.size.height - h) / 2f),
                androidx.compose.ui.geometry.Size(barW, h),
                androidx.compose.ui.geometry.CornerRadius(barW / 2f)
            )
        }
    }
}
