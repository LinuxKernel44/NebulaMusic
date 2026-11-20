package com.nebulamusic.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebulamusic.app.R
import com.nebulamusic.app.data.SearchResults
import com.nebulamusic.app.data.Track
import com.nebulamusic.app.glass.GlassTextField

@Composable
fun SearchScreen(vm: MainViewModel, nav: Nav, onMenu: (Track) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<Load<SearchResults>?>(null) }
    val env = rememberTrackEnv(vm)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val backdrop = glassBackdrop()
    val bottomInset = LocalBottomInset.current
    var headerH by remember { mutableStateOf(80.dp) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { result = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        result = Load.Loading
        result = try { Load.Ok(vm.api { it.search(q) }) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { vm.errorOf(e) }
    }

    Box(Modifier.fillMaxSize()) {
        ScreenBackdrop {
            when (val r = result) {
                null -> Box(Modifier.padding(top = headerH)) { EmptyText(stringResource(R.string.search_prompt)) }
                is Load.Loading -> Box(Modifier.fillMaxSize().padding(top = headerH), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
                is Load.Err -> Box(Modifier.padding(top = headerH)) { EmptyText(r.message) }
                is Load.Ok -> if (r.value.isEmpty) Box(Modifier.padding(top = headerH)) { EmptyText(stringResource(R.string.empty_search)) }
                else TrackColumn(
                    r.value.tracks, vm, env, onMenu,
                    contentPadding = PaddingValues(top = headerH, bottom = bottomInset + 8.dp),
                    header = {
                        Column {
                            if (r.value.artists.isNotEmpty()) {
                                SectionHeader(stringResource(R.string.lib_artists))
                                r.value.artists.take(5).forEach { a -> ArtistRow(a) { nav.openArtist(a.id) } }
                            }
                            if (r.value.albums.isNotEmpty()) {
                                SectionHeader(stringResource(R.string.lib_albums))
                                AlbumRowStrip(r.value.albums, nav.openAlbum)
                            }
                            if (r.value.tracks.isNotEmpty()) SectionHeader(stringResource(R.string.lib_songs))
                        }
                    }
                )
            }
        }

        TopScrim(headerH, Modifier.align(Alignment.TopCenter))
        Column(Modifier.align(Alignment.TopCenter).measureHeight { headerH = it + 8.dp }.statusBarsPadding().padding(top = 8.dp)) {
            GlassTextField(
                value = query, onValueChange = { query = it },
                placeholder = stringResource(R.string.search_hint), backdrop = backdrop,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                focusRequester = focus, imeAction = ImeAction.Search, onImeAction = { keyboard?.hide() },
                leading = { Icon(Icons.Rounded.Search, null, tint = Color.White.copy(alpha = 0.7f)) },
                trailing = {
                    if (query.isNotEmpty()) Icon(
                        Icons.Rounded.Close, null, tint = Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { query = "" }.padding(4.dp)
                    )
                },
            )
        }
    }
}
