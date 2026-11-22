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

@Composable
fun LibraryScreen(
    vm: MainViewModel, nav: Nav, section: LibSection, onSection: (LibSection) -> Unit,
    onMenu: (Track) -> Unit, onNewPlaylist: () -> Unit, onAiPlaylist: () -> Unit = {},
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
    val sorts by vm.sorts.collectAsState()
    val spec = sorts[section] ?: SortSpec()

    Box(Modifier.fillMaxSize()) {
        ScreenBackdrop {
            val levels by vm.levels.collectAsState()
            val level = levels[section] ?: vm.levelOf(section)
            val setLevel = { l: Int -> vm.setLevel(section, l) }
            @Composable
            fun Tracks(tracks: List<Track>, key: String, header: (@Composable () -> Unit)? = null, onEnd: (() -> Unit)? = null) {
                RegisterSelectable(tracks)
                LibraryCollection(
                    level, setLevel, tracks, { i, t -> "${t.id}#$i" }, key, headerH, bottom, header = header, onEnd = onEnd,
                    row = { i, t ->
                        TrackRow(
                            t, current = env.ui.trackId == t.id, playing = env.ui.isPlaying && env.ui.trackId == t.id,
                            favorite = t.id in env.favs, downloaded = env.dls.containsKey(t.id), progress = env.progress[t.id],
                            onClick = { vm.play(tracks, i) }, onMenu = { onMenu(t) },
                        )
                    },
                    tile = { i, t, cols -> TrackTile(t, cols, env.ui.trackId == t.id, env.ui.isPlaying && env.ui.trackId == t.id) { vm.play(tracks, i) } },
                )
            }
            when (section) {
                LibSection.ARTISTS -> {
                    val st by vm.artists.collectAsState()
                    LoadView(st, vm::refreshArtists) { list ->
                        if (list.isEmpty()) Box(Modifier.padding(top = headerH)) { EmptyText(stringResource(R.string.empty_library)) }
                        else {
                            val sorted = remember(list, spec) { sortArtists(list, spec) }
                            LibraryCollection(
                                level, setLevel, sorted, { _, a -> a.id }, "artists", headerH, bottom,
                                row = { _, a -> ArtistRow(a) { nav.openArtist(a.id) } },
                                tile = { _, a, cols -> ArtistTile(a, cols) { nav.openArtist(a.id) } },
                            )
                        }
                    }
                }

                LibSection.ALBUMS -> {
                    val albumTile: @Composable (Int, Album, Int) -> Unit = { _, a, cols -> AlbumTile(a, cols) { nav.openAlbum(a.id) } }
                    val albumRow: @Composable (Int, Album) -> Unit = { _, a -> AlbumRow(a) { nav.openAlbum(a.id) } }
                    if (vm.albumsNative(spec)) {
                        val items by vm.albums.items.collectAsState()
                        val st by vm.albums.state.collectAsState()
                        if (items.isEmpty()) LoadView(if (st is Load.Ok) Load.Ok(Unit) else st, { vm.albums.reset() }) {
                            Box(Modifier.padding(top = headerH)) { EmptyText(stringResource(R.string.empty_library)) }
                        } else LibraryCollection(level, setLevel, items, { _, a -> a.id }, "albums", headerH, bottom, onEnd = vm.albums::more, row = albumRow, tile = albumTile)
                    } else {
                        val all by vm.albumsAll.collectAsState()
                        LaunchedEffect(Unit) { vm.loadAllAlbums() }
                        LoadView(all, { vm.loadAllAlbums(true) }) { list ->
                            if (list.isEmpty()) Box(Modifier.padding(top = headerH)) { EmptyText(stringResource(R.string.empty_library)) }
                            else LibraryCollection(
                                level, setLevel, remember(list, spec) { sortAlbums(list, spec) }, { _, a -> a.id }, "albums", headerH, bottom,
                                row = albumRow, tile = albumTile,
                            )
                        }
                    }
                }

                LibSection.SONGS -> {
                    if (vm.songsNative(spec)) {
                        val items by vm.songs.items.collectAsState()
                        val st by vm.songs.state.collectAsState()
                        if (items.isEmpty()) LoadView(if (st is Load.Ok) Load.Ok(Unit) else st, { vm.songs.reset() }) {
                            Box(Modifier.padding(top = headerH)) { EmptyText(stringResource(R.string.empty_library)) }
                        } else Tracks(items, "songs", onEnd = vm.songs::more)
                    } else {
                        val all by vm.songsAll.collectAsState()
                        LaunchedEffect(Unit) { vm.loadAllSongs() }
                        LoadView(all, { vm.loadAllSongs(true) }) { list ->
                            if (list.isEmpty()) Box(Modifier.padding(top = headerH)) { EmptyText(stringResource(R.string.empty_library)) }
                            else Tracks(remember(list, spec) { sortTracks(list, spec) }, "songs")
                        }
                    }
                }

                LibSection.GENRES -> {
                    val st by vm.genres.collectAsState()
                    LoadView(st, vm::refreshGenres) { list ->
                        val sorted = remember(list, spec) { sortGenres(list, spec) }
                        LibraryCollection(
                            level, setLevel, sorted, { _, g -> g.name }, "genres", headerH, bottom,
                            row = { _, g ->
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
                            },
                            tile = { _, g, cols -> GenreTile(g, cols) { nav.openGenre(g.name) } },
                        )
                    }
                }

                LibSection.PLAYLISTS -> {
                    val st by vm.playlists.collectAsState()
                    LoadView(st, vm::refreshPlaylists) { list ->
                        val sorted = remember(list, spec) { sortPlaylists(list, spec) }
                        LibraryCollection(
                            level, setLevel, sorted, { _, p -> p.id }, "playlists", headerH, bottom,
                            header = {
                                Column {
                                    Row(
                                        Modifier.fillMaxWidth().clickable { onNewPlaylist() }.padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = GlassDefaults.Accent) }
                                        Spacer(Modifier.width(12.dp))
                                        Text(stringResource(R.string.new_playlist), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    }
                                    Row(
                                        Modifier.fillMaxWidth().clickable { onAiPlaylist() }.padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.AutoAwesome, null, tint = GlassDefaults.Accent) }
                                        Spacer(Modifier.width(12.dp))
                                        Text(stringResource(R.string.ai_new), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    }
                                    if (list.isEmpty()) Text(stringResource(R.string.empty_playlists), color = Muted, modifier = Modifier.padding(16.dp))
                                }
                            },
                            row = { _, p -> PlaylistRow(p) { nav.openPlaylist(p.id, p.name) } },
                            tile = { _, p, cols -> PlaylistTile(p, cols) { nav.openPlaylist(p.id, p.name) } },
                        )
                    }
                }

                LibSection.FAVORITES -> {
                    val st by vm.favorites.collectAsState()
                    LoadView(st, vm::refreshFavorites) { fav ->
                        if (fav.isEmpty) Box(Modifier.padding(top = headerH)) { EmptyText(stringResource(R.string.empty_favs)) }
                        else Tracks(
                            remember(fav, spec) { sortTracks(fav.tracks, spec) }, "favorites",
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
                    else Tracks(
                        remember(downloaded, spec) { sortTracks(downloaded, spec) }, "downloads",
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
                GlassIconButton({ sortMenu = !sortMenu }, backdrop, size = 40.dp) {
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

        SortMenu(sortMenu, { sortMenu = false }, backdrop, section, spec) { vm.setSort(section, it) }
    }
}

@Composable
private fun AlbumGrid(items: List<Album>, gs: androidx.compose.foundation.lazy.grid.LazyGridState, nav: Nav, headerH: androidx.compose.ui.unit.Dp, bottom: androidx.compose.ui.unit.Dp) {
    Box(Modifier.fillMaxSize()) {
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
