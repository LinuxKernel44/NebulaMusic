@file:OptIn(ExperimentalMaterial3Api::class)

package com.nebulamusic.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebulamusic.app.R
import com.nebulamusic.app.data.Album
import com.nebulamusic.app.glass.GlassButton
import com.nebulamusic.app.glass.GlassIconButton
import com.nebulamusic.app.glass.glass

/** Where the screens can send the user. */
class Nav(
    val openAlbum: (String) -> Unit,
    val openArtist: (String) -> Unit,
    val openPlaylist: (String, String) -> Unit,
    val openGenre: (String) -> Unit,
    val openSettings: () -> Unit,
    val openSearch: () -> Unit,
    val openLibrary: (LibSection) -> Unit,
    val openCover: (String?) -> Unit = {},
    val openLiked: () -> Unit = {},
)

@Composable
fun HomeScreen(vm: MainViewModel, nav: Nav) {
    val home by vm.home.collectAsState()
    val conn by vm.conn.collectAsState()
    val backdrop = glassBackdrop()
    val bottomInset = LocalBottomInset.current
    var headerH by remember { mutableStateOf(120.dp) }
    val loading = home is Load.Loading
    val listState = rememberKeptListState("home")

    Box(Modifier.fillMaxSize()) {
        ScreenBackdrop {
            PullToRefreshBox(isRefreshing = false, onRefresh = { vm.retryConnection() }, modifier = Modifier.fillMaxSize()) {
                LoadView(home, onRetry = vm::retryConnection) { data ->
                    val sections = listOf(
                        R.string.home_recent to data.recent, R.string.home_newest to data.newest,
                        R.string.home_frequent to data.frequent, R.string.home_random to data.random,
                    ).filter { it.second.isNotEmpty() }
                    if (sections.isEmpty()) Box(Modifier.padding(top = headerH)) { EmptyText(stringResource(R.string.empty_library)) }
                    else Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(top = headerH, bottom = bottomInset + 16.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            for ((title, albums) in sections) {
                                item(key = title) {
                                    Column {
                                        SectionHeader(stringResource(title))
                                        AlbumRowStrip(albums, nav.openAlbum, stateKey = stringResource(title))
                                    }
                                }
                            }
                        }
                        ListScrollbar(listState, headerH, bottomInset + 16.dp)
                    }
                }
                if (conn == MainViewModel.Conn.OFFLINE && home !is Load.Ok) {
                    // Offline: the downloads still work.
                    Box(Modifier.fillMaxSize().padding(bottom = bottomInset + 24.dp), contentAlignment = Alignment.BottomCenter) {
                        Button(
                            onClick = { nav.openLibrary(LibSection.DOWNLOADS) },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
                        ) { Icon(Icons.Rounded.DownloadDone, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.open_downloads)) }
                    }
                }
            }
        }

        TopScrim(headerH, Modifier.align(Alignment.TopCenter))
        Column(Modifier.align(Alignment.TopCenter).measureHeight { headerH = it + 6.dp }.statusBarsPadding()) {
            Row(
                Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("NEBULA", fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                GlassIconButton({ vm.playRandom() }, backdrop, size = 42.dp) {
                    Icon(Icons.Rounded.Shuffle, stringResource(R.string.shuffle_play), tint = Color.White, modifier = Modifier.size(22.dp))
                }
                GlassIconButton(nav.openSettings, backdrop, size = 42.dp) {
                    Icon(Icons.Rounded.Settings, stringResource(R.string.settings), tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth()
                    .glass(backdrop, blurRadius = 4.dp, lensHeight = 12.dp, lensAmount = 22.dp)
                    .clickable(onClick = nav.openSearch)
                    .height(54.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Search, null, tint = Color.White.copy(alpha = 0.75f))
                Spacer(Modifier.width(10.dp))
                BasicText(
                    stringResource(R.string.search_hint), maxLines = 1,
                    style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                )
            }
        }
        if (loading) Unit
    }
}

@Composable
fun AlbumRowStrip(albums: List<Album>, onOpen: (String) -> Unit, stateKey: String = "strip") {
    LazyRow(state = rememberKeptListState("strip-$stateKey"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(albums, key = { it.id }) { a -> AlbumCard(a, Modifier.width(150.dp), onClick = { onOpen(a.id) }) }
    }
}
