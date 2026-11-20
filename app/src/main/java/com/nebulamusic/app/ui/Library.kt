@file:OptIn(ExperimentalLayoutApi::class)

package com.nebulamusic.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebulamusic.app.R
import com.nebulamusic.app.data.*
import com.nebulamusic.app.glass.GlassChip
import com.nebulamusic.app.glass.GlassDefaults
import com.nebulamusic.app.glass.GlassIconButton
import com.nebulamusic.app.glass.GlassMenu

enum class LibSection { ARTISTS, ALBUMS, SONGS, GENRES, PLAYLISTS, FAVORITES, DOWNLOADS }

private fun LibSection.label() = when (this) {
    LibSection.ARTISTS -> R.string.lib_artists
    LibSection.ALBUMS -> R.string.lib_albums
    LibSection.SONGS -> R.string.lib_songs
    LibSection.GENRES -> R.string.lib_genres
    LibSection.PLAYLISTS -> R.string.lib_playlists
    LibSection.FAVORITES -> R.string.lib_favorites
    LibSection.DOWNLOADS -> R.string.lib_downloads
}

private fun AlbumSort.label() = when (this) {
    AlbumSort.NAME -> R.string.sort_name
    AlbumSort.ARTIST -> R.string.sort_artist
    AlbumSort.NEWEST -> R.string.sort_newest
    AlbumSort.RECENT -> R.string.sort_recent
    AlbumSort.FREQUENT -> R.string.sort_frequent
    AlbumSort.RANDOM -> R.string.sort_random
    AlbumSort.YEAR -> R.string.sort_year
    AlbumSort.STARRED -> R.string.sort_starred
}

@Composable
fun LibraryScreen(
    vm: MainViewModel, nav: Nav, section: LibSection, onSection: (LibSection) -> Unit,
    onMenu: (Track) -> Unit, onNewPlaylist: () -> Unit,
) {
    val env = rememberTrackEnv(vm)
    val sel = LocalSelection.current
    LaunchedEffect(section) { sel.clear() }
    LaunchedEffect(section) { if (section == LibSection.FAVORITES) vm.refreshFavorites() }
    val backdrop = glassBackdrop()
    val bottomInset = LocalBottomInset.current
    var headerH by remember { mutableStateOf(120.dp) }
    val bottom = bottomInset + 8.dp
    val pad = PaddingValues(top = headerH, bottom = bottom)
    var sortMenu by remember { mutableStateOf(false) }
    val albumSort by vm.albumSort.collectAsState()

    Box(Modifier.fillMaxSize()) {
        ScreenBackdrop {
            when (section) {
                LibSection.ARTISTS -> {
                    val st by vm.artists.collectAsState()
                    LoadView(st, vm::refreshArtists) { list ->
                        if (list.isEmpty()) Box(Modifier.padding(top = headerH)) { EmptyText(stringResource(R.string.empty_library)) }
                        else {
                            val ls = rememberKeptListState("artists")
                            Box(Modifier.fillMaxSize()) {
                                LazyColumn(Modifier.fillMaxSize(), state = ls, contentPadding = pad) {
                                    items(list, key = { it.id }) { a -> ArtistRow(a) { nav.openArtist(a.id) } }
                                }
                                ListScrollbar(ls, headerH, bottom)
                            }
                        }
                    }
                }

                LibSection.ALBUMS -> {
                    val items by vm.albums.items.collectAsState()
                    val st by vm.albums.state.collectAsState()
                    val gs = rememberKeptGridState("albums")
                    val near by remember { derivedStateOf { gs.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 10 } } }
                    LaunchedEffect(near, items.size) { if (near) vm.albums.more() }
                    if (items.isEmpty()) LoadView(if (st is Load.Ok) Load.Ok(Unit) else st, { vm.albums.reset() }) {
                        Box(Modifier.padding(top = headerH)) { EmptyText(stringResource(R.string.empty_library)) }
                    } else Box(Modifier.fillMaxSize()) {
                        LazyVerticalGrid(
                            state = gs, columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = headerH, bottom = bottom),
                            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(items, key = { it.id }) { a -> AlbumCard(a, onClick = { nav.openAlbum(a.id) }) }
                        }
                        GridScrollbar(gs, headerH, bottom)
                    }
                }

                LibSection.SONGS -> {
                    val items by vm.songs.items.collectAsState()
                    val st by vm.songs.state.collectAsState()
                    if (items.isEmpty()) LoadView(if (st is Load.Ok) Load.Ok(Unit) else st, { vm.songs.reset() }) {
                        Box(Modifier.padding(top = headerH)) { EmptyText(stringResource(R.string.empty_library)) }
                    } else TrackColumn(items, vm, env, onMenu, contentPadding = pad, onEndReached = vm.songs::more, stateKey = "songs")
                }

                LibSection.GENRES -> {
                    val st by vm.genres.collectAsState()
                    LoadView(st, vm::refreshGenres) { list ->
                        val ls = rememberKeptListState("genres")
                        Box(Modifier.fillMaxSize()) {
                            LazyColumn(Modifier.fillMaxSize(), state = ls, contentPadding = pad) {
                                items(list, key = { it.name }) { g ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { nav.openGenre(g.name) }.padding(horizontal = 20.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.Sell, null, tint = Accent)
                                        Spacer(Modifier.width(16.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(g.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                                            Text(
                                                pluralStringResource(R.plurals.n_songs, g.songCount, g.songCount) + " · " +
                                                    pluralStringResource(R.plurals.n_albums, g.albumCount, g.albumCount),
                                                color = Muted, fontSize = 13.sp
                                            )
                                        }
                                        Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
                                    }
                                }
                            }
                            ListScrollbar(ls, headerH, bottom)
                        }
                    }
                }

                LibSection.PLAYLISTS -> {
                    val st by vm.playlists.collectAsState()
                    LoadView(st, vm::refreshPlaylists) { list ->
                        val ls = rememberKeptListState("playlists")
                        Box(Modifier.fillMaxSize()) {
                            LazyColumn(Modifier.fillMaxSize(), state = ls, contentPadding = pad) {
                                item {
                                    Row(
                                        Modifier.fillMaxWidth().clickable { onNewPlaylist() }.padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = GlassDefaults.Accent) }
                                        Spacer(Modifier.width(12.dp))
                                        Text(stringResource(R.string.new_playlist), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    }
                                }
                                if (list.isEmpty()) item { Text(stringResource(R.string.empty_playlists), color = Muted, modifier = Modifier.padding(16.dp)) }
                                items(list, key = { it.id }) { p -> PlaylistRow(p) { nav.openPlaylist(p.id, p.name) } }
                            }
                            ListScrollbar(ls, headerH, bottom)
                        }
                    }
                }

                LibSection.FAVORITES -> {
                    val st by vm.favorites.collectAsState()
                    LoadView(st, vm::refreshFavorites) { fav ->
                        if (fav.isEmpty) Box(Modifier.padding(top = headerH)) { EmptyText(stringResource(R.string.empty_favs)) }
                        else TrackColumn(
                            fav.tracks, vm, env, onMenu, contentPadding = pad, stateKey = "favorites",
                            header = {
                                Column {
                                    if (fav.tracks.isNotEmpty()) PlayAllRow(fav.tracks, vm)
                                    if (fav.albums.isNotEmpty()) { SectionHeader(stringResource(R.string.lib_albums)); AlbumRowStrip(fav.albums, nav.openAlbum, stateKey = "fav") }
                                    if (fav.artists.isNotEmpty()) {
                                        SectionHeader(stringResource(R.string.lib_artists))
                                        fav.artists.forEach { a -> ArtistRow(a) { nav.openArtist(a.id) } }
                                    }
                                    if (fav.tracks.isNotEmpty() && (fav.albums.isNotEmpty() || fav.artists.isNotEmpty())) SectionHeader(stringResource(R.string.lib_songs))
                                }
                            }
                        )
                    }
                }

                LibSection.DOWNLOADS -> {
                    val downloaded by vm.downloadedTracks.collectAsState()
                    val bytes by vm.downloadBytes.collectAsState()
                    val inProgress = env.progress.keys.toList()
                    if (downloaded.isEmpty() && inProgress.isEmpty()) Box(Modifier.padding(top = headerH)) { EmptyText(stringResource(R.string.empty_downloads)) }
                    else TrackColumn(
                        downloaded, vm, env, onMenu, contentPadding = pad, stateKey = "downloads",
                        header = {
                            Column {
                                if (inProgress.isNotEmpty()) DownloadingSection(inProgress.size, env.progress)
                                if (downloaded.isNotEmpty()) {
                                    Text(
                                        pluralStringResource(R.plurals.n_songs, downloaded.size, downloaded.size) + " · " + fmtBytes(bytes),
                                        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                    PlayAllRow(downloaded, vm)
                                }
                            }
                        }
                    )
                }
            }
        }

        TopScrim(headerH, Modifier.align(Alignment.TopCenter))
        Column(Modifier.align(Alignment.TopCenter).measureHeight { headerH = it + 8.dp }.statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassTitle(stringResource(R.string.nav_library), Modifier.weight(1f))
                if (section == LibSection.ALBUMS) GlassIconButton({ sortMenu = !sortMenu }, backdrop, size = 40.dp) {
                    Icon(Icons.Rounded.Sort, stringResource(R.string.sort_by), tint = Color.White)
                }
                GlassIconButton(nav.openSettings, backdrop, size = 40.dp) {
                    Icon(Icons.Rounded.Settings, stringResource(R.string.settings), tint = Color.White)
                }
            }
            LazyRow(state = rememberKeptListState("chips"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibSection.entries) { s -> GlassChip(stringResource(s.label()), section == s, { onSection(s) }, backdrop) }
            }
        }

        GlassMenu(
            visible = sortMenu, onDismiss = { sortMenu = false }, backdrop = backdrop,
            alignment = Alignment.TopEnd, modifier = Modifier.statusBarsPadding().padding(top = 64.dp, end = 12.dp)
        ) {
            for (s in AlbumSort.entries) GlassMenuRow(
                null, stringResource(s.label()), { vm.albumSort.value = s; sortMenu = false },
                color = if (s == albumSort) GlassDefaults.Accent else Color.White, fill = false, minWidth = 190.dp,
            )
        }
    }
}

fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "%d KB".format(b / 1024)
}

/** Tracks being downloaded right now, with an overall progress bar. */
@Composable
private fun DownloadingSection(count: Int, progress: Map<String, Float>) {
    val avg = if (progress.isEmpty()) 0f else progress.values.sum() / progress.size
    val p by androidx.compose.animation.core.animateFloatAsState(avg, label = "dl")
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.downloading_now), color = Muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("$count · ${(p * 100).toInt()}%", color = Muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { p }, color = GlassDefaults.Accent, trackColor = Color.White.copy(alpha = 0.14f),
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
        )
    }
}
