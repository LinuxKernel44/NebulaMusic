@file:OptIn(ExperimentalLayoutApi::class)

package com.nebulamusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebulamusic.app.R
import com.nebulamusic.app.data.*
import com.nebulamusic.app.glass.GlassIconButton

@Composable
private fun ActionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (primary) Accent else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (primary) Color.Black else Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (primary) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
fun AlbumScreen(vm: MainViewModel, id: String, nav: Nav, onBack: () -> Unit, onMenu: (Track) -> Unit) {
    val (state, reload) = rememberLoad(vm, id) { it.album(id) }
    val env = rememberTrackEnv(vm)
    val backdrop = glassBackdrop()
    val bottomInset = LocalBottomInset.current
    var headerH by remember { mutableStateOf(80.dp) }
    var albumStar by remember(id) { mutableStateOf<Boolean?>(null) }

    Box(Modifier.fillMaxSize()) {
        ScreenBackdrop {
            LoadView(state, reload) { (album, tracks) ->
                val star = albumStar ?: album.starred
                val discs = tracks.map { it.discNo }.filter { it > 0 }.distinct().size
                val best = tracks.maxByOrNull { qualityRank(it) }
                TrackColumn(
                    tracks, vm, env, onMenu, numbered = true,
                    contentPadding = PaddingValues(top = headerH, bottom = bottomInset + 8.dp),
                    header = {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Cover(album.coverArt, Modifier.fillMaxWidth(0.62f).aspectRatio(1f), 20.dp, px = 700)
                            Spacer(Modifier.height(14.dp))
                            Text(album.name, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, textAlign = TextAlign.Center, maxLines = 2)
                            if (album.artist.isNotBlank()) Text(
                                album.artist, color = Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = album.artistId != null) { album.artistId?.let(nav.openArtist) }.padding(4.dp)
                            )
                            Text(
                                listOfNotNull(
                                    album.year.takeIf { it > 0 }?.toString(), album.genre,
                                    pluralStringResource(R.plurals.n_songs, tracks.size, tracks.size),
                                    fmtSec(tracks.sumOf { it.durationSec }),
                                    if (discs > 1) stringResource(R.string.n_discs, discs) else null,
                                ).joinToString(" · "),
                                color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center
                            )
                            best?.let { Text(qualityLabel(it), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                            Spacer(Modifier.height(12.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionChip(Icons.Rounded.PlayArrow, stringResource(R.string.play_all), primary = true) { vm.play(tracks, 0) }
                                ActionChip(Icons.Rounded.Shuffle, stringResource(R.string.shuffle_play)) { vm.play(tracks.shuffled(), 0) }
                                val ctx = androidx.compose.ui.platform.LocalContext.current
                                val allDownloaded = tracks.isNotEmpty() && tracks.all { it.id in env.dls }
                                val downloading = tracks.any { it.id in env.progress }
                                when {
                                    downloading -> ActionChip(Icons.Rounded.Downloading, stringResource(R.string.downloading)) { }
                                    allDownloaded -> ActionChip(Icons.Rounded.DeleteOutline, stringResource(R.string.delete_downloaded)) {
                                        vm.deleteDownloads(tracks)
                                        android.widget.Toast.makeText(ctx, R.string.downloads_removed, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    else -> ActionChip(Icons.Rounded.Download, stringResource(R.string.download)) { vm.download(tracks) }
                                }
                                ActionChip(if (star) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(if (star) R.string.rm_fav else R.string.add_fav)) {
                                    albumStar = !star; vm.toggleStarContainer(album.id, StarKind.ALBUM, !star)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                )
            }
        }
        TopScrim(headerH, Modifier.align(Alignment.TopCenter))
        BackHeader("", onBack, backdrop, Modifier.align(Alignment.TopCenter).measureHeight { headerH = it + 8.dp }.statusBarsPadding())
    }
}

private fun qualityRank(t: Track) = (if (t.isLossless) 1_000_000 else 0) + t.bitDepth * 1000 + t.samplingRate / 100 + t.bitRate

@Composable
fun ArtistScreen(vm: MainViewModel, id: String, nav: Nav, onBack: () -> Unit, onMenu: (Track) -> Unit) {
    val (state, reload) = rememberLoad(vm, id) { s ->
        val (artist, albums) = s.artist(id)
        Triple(artist, albums, s.artistInfo(id))
    }
    val top by produceState<List<Track>>(emptyList(), state) {
        val a = (state as? Load.Ok)?.value?.first ?: return@produceState
        value = runCatching { vm.api { it.topSongs(a.name) } }.getOrDefault(emptyList()).take(8)
    }
    val env = rememberTrackEnv(vm)
    val backdrop = glassBackdrop()
    val bottomInset = LocalBottomInset.current
    var headerH by remember { mutableStateOf(80.dp) }
    val ls = rememberKeptListState("artist")
    var bioOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        ScreenBackdrop {
            LoadView(state, reload) { (artist, albums, info) ->
                RegisterSelectable(top)
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), state = ls, contentPadding = PaddingValues(top = headerH, bottom = bottomInset + 8.dp)) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                CircleCover(info.image?.let { null } ?: artist.coverArt, Modifier.size(150.dp))
                                Spacer(Modifier.height(12.dp))
                                Text(artist.name, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, textAlign = TextAlign.Center)
                                Text(pluralStringResource(R.plurals.n_albums, albums.size, albums.size), color = Muted, fontSize = 13.sp)
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ActionChip(Icons.Rounded.PlayArrow, stringResource(R.string.play_all), primary = true) {
                                        scope.launch { playArtist(vm, albums, false) }
                                    }
                                    ActionChip(Icons.Rounded.Shuffle, stringResource(R.string.shuffle_play)) {
                                        scope.launch { playArtist(vm, albums, true) }
                                    }
                                }
                                if (info.biography.isNotBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        info.biography, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp,
                                        maxLines = if (bioOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.clickable { bioOpen = !bioOpen }
                                    )
                                }
                            }
                        }
                        if (top.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.top_songs)) }
                            itemsIndexed(top, key = { i, t -> "top${t.id}#$i" }) { i, t ->
                                TrackRow(
                                    t, env.ui.trackId == t.id, env.ui.isPlaying && env.ui.trackId == t.id, t.id in env.favs,
                                    env.dls.containsKey(t.id), env.progress[t.id], onClick = { vm.play(top, i) }, onMenu = { onMenu(t) }
                                )
                            }
                        }
                        if (albums.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.lib_albums)) }
                            items(albums.sortedByDescending { it.year }, key = { "al${it.id}" }) { a -> AlbumRow(a) { nav.openAlbum(a.id) } }
                        }
                        if (info.similar.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.similar_artists)) }
                            item {
                                LazyRow(state = rememberKeptListState("similar"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    items(info.similar.filter { it.id.isNotBlank() }, key = { it.id }) { a ->
                                        Column(Modifier.width(90.dp).clip(RoundedCornerShape(12.dp)).clickable { nav.openArtist(a.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                            CircleCover(a.coverArt, Modifier.size(80.dp))
                                            Text(a.name, fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ListScrollbar(ls, headerH, bottomInset + 8.dp)
                }
            }
        }
        TopScrim(headerH, Modifier.align(Alignment.TopCenter))
        BackHeader("", onBack, backdrop, Modifier.align(Alignment.TopCenter).measureHeight { headerH = it + 8.dp }.statusBarsPadding())
    }
}

private suspend fun playArtist(vm: MainViewModel, albums: List<Album>, shuffle: Boolean) {
    val tracks = runCatching {
        vm.api { s -> albums.sortedBy { it.year }.flatMap { s.album(it.id).second } }
    }.getOrDefault(emptyList())
    if (tracks.isNotEmpty()) vm.play(if (shuffle) tracks.shuffled() else tracks, 0)
}

@Composable
fun PlaylistScreen(
    vm: MainViewModel, id: String, name: String, onBack: () -> Unit, onMenu: (Track) -> Unit,
    onRename: (String, String) -> Unit, version: Int = 0,
) {
    val (state, reload) = rememberLoad(vm, id to version) { it.playlist(id) }
    val env = rememberTrackEnv(vm)
    val backdrop = glassBackdrop()
    val bottomInset = LocalBottomInset.current
    var headerH by remember { mutableStateOf(80.dp) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        ScreenBackdrop {
            LoadView(state, reload) { (pl, tracks) ->
                if (tracks.isEmpty()) Box(Modifier.padding(top = headerH)) { EmptyText(stringResource(R.string.empty_playlist)) }
                else {
                    val ls = rememberKeptListState("playlist")
                    RegisterSelectable(tracks)
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.fillMaxSize(), state = ls, contentPadding = PaddingValues(top = headerH, bottom = bottomInset + 8.dp)) {
                            item {
                                Column {
                                    Text(
                                        pluralStringResource(R.plurals.n_songs, tracks.size, tracks.size) + " · " + fmtSec(tracks.sumOf { it.durationSec }),
                                        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                    PlayAllRow(tracks, vm)
                                }
                            }
                            itemsIndexed(tracks, key = { i, t -> "${t.id}#$i" }) { i, t ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TrackRow(
                                        t, env.ui.trackId == t.id, env.ui.isPlaying && env.ui.trackId == t.id, t.id in env.favs,
                                        env.dls.containsKey(t.id), env.progress[t.id], onClick = { vm.play(tracks, i) },
                                        onMenu = { onMenu(t) }, modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { vm.removeFromPlaylist(pl.id, i) { reload() } }) {
                                        Icon(Icons.Rounded.RemoveCircleOutline, null, tint = Muted)
                                    }
                                }
                            }
                        }
                        ListScrollbar(ls, headerH, bottomInset + 8.dp)
                    }
                }
            }
        }
        TopScrim(headerH, Modifier.align(Alignment.TopCenter))
        BackHeader(name, onBack, backdrop, Modifier.align(Alignment.TopCenter).measureHeight { headerH = it + 8.dp }.statusBarsPadding()) {
            GlassIconButton({ onRename(id, name) }, backdrop, size = 46.dp) { Icon(Icons.Rounded.Edit, stringResource(R.string.rename), tint = Color.White) }
            GlassIconButton({ confirmDelete = true }, backdrop, size = 46.dp) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), tint = Color.White) }
        }
        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = { TextButton({ vm.deletePlaylist(id); confirmDelete = false; onBack() }) { Text(stringResource(R.string.delete), color = Accent) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
            title = { Text(stringResource(R.string.delete_playlist_title)) },
            text = { Text(stringResource(R.string.delete_playlist_msg, name)) },
            containerColor = SurfaceHi
        )
    }
}

@Composable
fun GenreScreen(vm: MainViewModel, genre: String, nav: Nav, onBack: () -> Unit) {
    val (state, reload) = rememberLoad(vm, genre) { it.albums(AlbumSort.NAME, 0, 200, genre) }
    val backdrop = glassBackdrop()
    val bottomInset = LocalBottomInset.current
    var headerH by remember { mutableStateOf(80.dp) }
    Box(Modifier.fillMaxSize()) {
        ScreenBackdrop {
            LoadView(state, reload) { albums ->
                if (albums.isEmpty()) Box(Modifier.padding(top = headerH)) { EmptyText(stringResource(R.string.empty_library)) }
                else LazyVerticalGrid(
                    state = rememberKeptGridState("genre"), columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = headerH, bottom = bottomInset + 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) { items(albums, key = { it.id }) { a -> AlbumCard(a, onClick = { nav.openAlbum(a.id) }) } }
            }
        }
        TopScrim(headerH, Modifier.align(Alignment.TopCenter))
        BackHeader(genre, onBack, backdrop, Modifier.align(Alignment.TopCenter).measureHeight { headerH = it + 8.dp }.statusBarsPadding()) {
            GlassIconButton({ vm.playGenre(genre) }, backdrop, size = 46.dp) { Icon(Icons.Rounded.Shuffle, stringResource(R.string.shuffle_play), tint = Color.White) }
        }
    }
}
