@file:OptIn(ExperimentalLayoutApi::class)

package com.nebulamusic.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.nebulamusic.app.R
import com.nebulamusic.app.data.ServerConfig
import com.nebulamusic.app.data.Track
import com.nebulamusic.app.glass.GlassDefaults
import com.nebulamusic.app.glass.GlassDialog
import com.nebulamusic.app.glass.GlassSheet
import com.nebulamusic.app.glass.LocalGlassBackdrop
import kotlinx.coroutines.launch

private sealed interface PlaylistDialog {
    data class Create(val firstTracks: List<String>) : PlaylistDialog
    data class Rename(val id: String, val name: String) : PlaylistDialog
    data class ConfirmImport(val uri: android.net.Uri) : PlaylistDialog
}

/** Screens stacked on top of the current tab. */
private sealed interface Dest {
    data class Album(val id: String) : Dest
    data class Artist(val id: String) : Dest
    data class Playlist(val id: String, val name: String) : Dest
    data class Genre(val name: String) : Dest
    data object Settings : Dest
    data class Login(val serverId: String?) : Dest
}

@Composable
fun AppRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var section by rememberSaveable { mutableStateOf(LibSection.ALBUMS) }
    val stack = remember { mutableStateListOf<Dest>() }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var pickerTracks by remember { mutableStateOf<List<Track>?>(null) }
    var queueOpen by remember { mutableStateOf(false) }
    var audioInfoOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<PlaylistDialog?>(null) }
    var lastMenuTrack by remember { mutableStateOf<Track?>(null) }
    var lastPicker by remember { mutableStateOf<List<Track>>(emptyList()) }
    var lastDialog by remember { mutableStateOf<PlaylistDialog?>(null) }
    if (menuTrack != null) lastMenuTrack = menuTrack
    if (pickerTracks != null) lastPicker = pickerTracks!!
    if (dialog != null) lastDialog = dialog

    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun toast(r: Int) = android.widget.Toast.makeText(ctx, r, android.widget.Toast.LENGTH_LONG).show()

    val servers by vm.servers.collectAsState()
    val session by vm.session.collectAsState()
    val conn by vm.conn.collectAsState()
    val ui by vm.player.state.collectAsState()

    // First launch (or after the last server was removed): the login screen is the whole app.
    val needsLogin = servers.isEmpty()
    // Wrong password / user: ask for the credentials again (once per failure).
    var authPrompted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(conn, session) {
        val id = session?.server?.id
        if (conn == MainViewModel.Conn.AUTH && id != null && authPrompted != id) {
            authPrompted = id
            if (stack.none { it is Dest.Login }) stack.add(Dest.Login(id))
        }
    }

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) vm.exportBackup(uri) { r -> toast(if (r.isSuccess) R.string.export_done else R.string.export_failed) } }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) dialog = PlaylistDialog.ConfirmImport(uri) }

    // Multi-select + save to folder
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var pendingExport by remember { mutableStateOf<List<Track>?>(null) }
    val visibleLists = remember { HashMap<Any, List<Track>>() }
    fun selectedTracks() = visibleLists.values.flatten().filter { it.id in selected }.distinctBy { it.id }
    fun clearSelection() { selecting = false; selected = emptySet() }

    fun runExport(list: List<Track>, tree: android.net.Uri) {
        android.widget.Toast.makeText(ctx, ctx.getString(R.string.exporting, list.size), android.widget.Toast.LENGTH_SHORT).show()
        vm.saveToFolder(list, tree) { r ->
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.export_result, r.ok, r.ok + r.failed), android.widget.Toast.LENGTH_LONG).show()
        }
    }
    fun startShare(list: List<Track>) {
        if (list.isEmpty()) return
        android.widget.Toast.makeText(ctx, ctx.getString(R.string.share_preparing, list.size), android.widget.Toast.LENGTH_SHORT).show()
        vm.prepareShare(list) { uris ->
            if (uris.isEmpty()) {
                android.widget.Toast.makeText(ctx, R.string.share_failed, android.widget.Toast.LENGTH_LONG).show()
                return@prepareShare
            }
            if (uris.size < list.size) android.widget.Toast.makeText(ctx, ctx.getString(R.string.share_skipped, list.size - uris.size), android.widget.Toast.LENGTH_LONG).show()
            val send = android.content.Intent(
                if (uris.size == 1) android.content.Intent.ACTION_SEND else android.content.Intent.ACTION_SEND_MULTIPLE
            ).apply {
                type = "audio/*"
                if (uris.size == 1) putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
                else putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                clipData = android.content.ClipData.newRawUri(null, uris[0]).also { c -> uris.drop(1).forEach { c.addItem(android.content.ClipData.Item(it)) } }
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(android.content.Intent.createChooser(send, null))
            if (selecting) clearSelection()
        }
    }
    val folderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val list = pendingExport
        pendingExport = null
        if (uri != null) {
            if (!vm.exportAsk.value) vm.setExportDir(uri) // fixed-folder mode: remembered for good
            if (list != null) runExport(list, uri)
            if (list != null && selecting) clearSelection()
        }
    }
    fun startExport(list: List<Track>) {
        if (list.isEmpty()) return
        val saved = vm.savedExportDir()
        if (saved != null) { runExport(list, saved); if (selecting) clearSelection() }
        else { pendingExport = list; folderLauncher.launch(null) }
    }
    val selection = remember(selecting, selected) {
        SelectionState(
            selecting, selected,
            start = { id -> selecting = true; selected = setOf(id) },
            toggle = { id ->
                selected = if (id in selected) selected - id else selected + id
                if (selected.isEmpty()) selecting = false // un-ticking the last one leaves selection mode
            },
            clear = { clearSelection() },
            setVisible = { k, list -> if (list == null) visibleLists.remove(k) else visibleLists[k] = list }
        )
    }
    // Moving to another screen drops the selection.
    LaunchedEffect(tab, stack.size, playerOpen) { clearSelection() }

    val imeVisible = WindowInsets.isImeVisible
    val density = LocalDensity.current
    val sheetOpen = menuTrack != null || pickerTracks != null || queueOpen || audioInfoOpen || dialog != null

    BackHandler(enabled = selecting) { clearSelection() }
    BackHandler(enabled = menuTrack != null) { menuTrack = null }
    BackHandler(enabled = pickerTracks != null) { pickerTracks = null }
    BackHandler(enabled = queueOpen) { queueOpen = false }
    BackHandler(enabled = audioInfoOpen) { audioInfoOpen = false }
    BackHandler(enabled = dialog != null) { dialog = null }
    BackHandler(enabled = playerOpen && !sheetOpen) { playerOpen = false }
    BackHandler(enabled = !playerOpen && !sheetOpen && !selecting && stack.isNotEmpty() && !needsLogin) { stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = !playerOpen && !sheetOpen && !selecting && stack.isEmpty() && tab != Tab.HOME) { tab = Tab.HOME }

    // ---- Theme from the artwork of the loaded track (even when paused). No cover / nothing loaded: default dark. ----
    val coverUrl = remember(ui.track?.coverArt, session) { session?.coverUrl(ui.track?.coverArt, 300) }
    var art by remember { mutableStateOf<ArtTheme?>(null) }
    LaunchedEffect(coverUrl) {
        if (coverUrl == null) { art = null; return@LaunchedEffect }
        val (c, blur) = loadArtInfo(ctx, coverUrl)
        art = if (blur != null) ArtTheme(coverUrl, accentFrom(c), blur) else null
    }
    val accent by animateColorAsState(art?.accent ?: GlassDefaults.DefaultAccent, tween(700), label = "accent")
    SideEffect { GlassDefaults.accentState = accent }

    // Two independent glass sources: the app screens, and the full-screen player.
    val backdrop = rememberLayerBackdrop()
    val playerBackdrop = rememberLayerBackdrop()

    // Player presentation: 0 = fully open, 1 = fully closed. Follows the finger while swiping down.
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(1f) }
    val covering by remember { derivedStateOf { progress.value <= 0.001f } }
    var playerComposed by remember { mutableStateOf(false) }
    var rootHeight by remember { mutableFloatStateOf(2000f) }
    LaunchedEffect(playerOpen) {
        if (playerOpen) {
            playerComposed = true
            progress.animateTo(0f, tween(340))
        } else {
            progress.animateTo(1f, tween((300 * (1f - progress.value) + 120).toInt()))
            playerComposed = false
        }
    }
    // The player cannot show without a loaded track.
    LaunchedEffect(ui.track == null) { if (ui.track == null && playerOpen) playerOpen = false }

    var bottomPx by remember { mutableIntStateOf(0) }
    val bottomInset = if (imeVisible) 0.dp else with(density) { bottomPx.toDp() }
    val overlayBackdrop = rememberLayerBackdrop()

    val nav = remember {
        Nav(
            openAlbum = { stack.add(Dest.Album(it)) },
            openArtist = { stack.add(Dest.Artist(it)) },
            openPlaylist = { id, name -> stack.add(Dest.Playlist(id, name)) },
            openGenre = { stack.add(Dest.Genre(it)) },
            openSettings = { if (stack.none { it is Dest.Settings }) stack.add(Dest.Settings) },
            openSearch = { stack.clear(); tab = Tab.SEARCH },
            openLibrary = { s -> stack.clear(); section = s; tab = Tab.LIBRARY },
        )
    }
    var plVersion by remember { mutableIntStateOf(0) }

    CompositionLocalProvider(
        LocalGlassBackdrop provides backdrop,
        LocalBottomInset provides bottomInset,
        LocalSelection provides selection,
        LocalArtTheme provides art,
        LocalSession provides session,
    ) {
        Box(Modifier.fillMaxSize().background(Bg).onSizeChanged { rootHeight = it.height.toFloat() }) {
          Box(Modifier.fillMaxSize().layerBackdrop(overlayBackdrop).background(Bg)) {
            Box(Modifier.fillMaxSize().imePadding().graphicsLayer { alpha = if (covering) 0f else 1f }) {
                // Only one screen at a time may own the shared glass backdrop layer.
                val top = stack.lastOrNull()
                if (needsLogin) LoginScreen(vm, null, canCancel = false, onDone = {}, onCancel = {})
                else when (top) {
                    is Dest.Login -> LoginScreen(
                        vm, servers.firstOrNull { it.id == top.serverId }, canCancel = true,
                        onDone = { stack.removeAt(stack.lastIndex); vm.retryConnection() }, onCancel = { stack.removeAt(stack.lastIndex) }
                    )
                    Dest.Settings -> SettingsScreen(
                        vm, onBack = { stack.removeAt(stack.lastIndex) },
                        onPickFolder = { pendingExport = null; folderLauncher.launch(null) },
                        onAddServer = { stack.add(Dest.Login(null)) },
                        onEditServer = { stack.add(Dest.Login(it.id)) },
                        onExport = { exportLauncher.launch("nebulamusic-settings.json") },
                        onImport = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                    )
                    is Dest.Album -> AlbumScreen(vm, top.id, nav, { stack.removeAt(stack.lastIndex) }, { menuTrack = it })
                    is Dest.Artist -> ArtistScreen(vm, top.id, nav, { stack.removeAt(stack.lastIndex) }, { menuTrack = it })
                    is Dest.Playlist -> PlaylistScreen(
                        vm, top.id, top.name, { stack.removeAt(stack.lastIndex) }, { menuTrack = it },
                        onRename = { id, name -> dialog = PlaylistDialog.Rename(id, name) }, version = plVersion
                    )
                    is Dest.Genre -> GenreScreen(vm, top.name, nav, { stack.removeAt(stack.lastIndex) })
                    null -> when (tab) {
                        Tab.HOME -> HomeScreen(vm, nav)
                        Tab.SEARCH -> SearchScreen(vm, nav) { menuTrack = it }
                        Tab.LIBRARY -> LibraryScreen(
                            vm, nav, section, { section = it }, onMenu = { menuTrack = it },
                            onNewPlaylist = { dialog = PlaylistDialog.Create(emptyList()) },
                        )
                    }
                }
            }

            // Floating glass: mini player + tabs (sibling of the content, so it never samples itself)
            if (!imeVisible && !covering && !needsLogin && stack.lastOrNull() !is Dest.Login) {
                BottomScrim(bottomInset, Modifier.align(Alignment.BottomCenter))
                Column(
                    Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomPx = it.height }
                        .navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selecting) SelectionBar(
                        selected.size, backdrop,
                        allSelected = visibleLists.values.flatten().map { it.id }.toSet().let { it.isNotEmpty() && selected.containsAll(it) },
                        onSelectAll = {
                            val all = visibleLists.values.flatten().map { it.id }.toSet()
                            if (all.isNotEmpty() && selected.containsAll(all)) clearSelection() else selected = selected + all
                        },
                        onCancel = { clearSelection() },
                        onSave = { startExport(selectedTracks()) },
                        onShare = { startShare(selectedTracks()) },
                        onPlaylist = { pickerTracks = selectedTracks() },
                        onDownload = {
                            val todo = selectedTracks()
                            vm.download(todo)
                            android.widget.Toast.makeText(ctx, ctx.getString(R.string.download_started, todo.size), android.widget.Toast.LENGTH_SHORT).show()
                            clearSelection()
                        }
                    )
                    if (ui.track != null) GlassMiniPlayer(vm, backdrop, onOpen = { playerOpen = true })
                    AppTabs(tab, onSelect = { tab = it; stack.clear() }, backdrop = backdrop)
                }
            }

            if (playerComposed) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        val p = progress.value
                        translationY = p * size.height
                        scaleX = 1f - 0.08f * p
                        scaleY = 1f - 0.08f * p
                        alpha = 1f - 0.6f * p
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        shape = RoundedCornerShape((36f * p).dp)
                        clip = true
                    }
                        .pointerInput(Unit) { detectTapGestures { } }
                ) {
                    PlayerScreen(
                        vm,
                        onClose = { playerOpen = false },
                        onNav = { tab = it; stack.clear(); playerOpen = false },
                        onMenu = { menuTrack = it },
                        onQueue = { queueOpen = true },
                        onAudioInfo = { audioInfoOpen = true },
                        playerBackdrop = playerBackdrop,
                        currentTab = tab,
                        onSwipeDelta = { d ->
                            val next = (progress.value + d / rootHeight).coerceIn(0f, 1f)
                            scope.launch { progress.snapTo(next) }
                        },
                        onSwipeEnd = { velocity ->
                            if (progress.value > 0.2f || velocity > 1500f) playerOpen = false
                            else scope.launch { progress.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f)) }
                        },
                    )
                }
            }
          }
            // ---- Glass sheets and dialogs (always on top, sampling whichever screen is in front) ----
            GlassSheet(visible = menuTrack != null, onDismiss = { menuTrack = null }, backdrop = overlayBackdrop) {
                lastMenuTrack?.let { t ->
                    TrackMenuContent(
                        t, vm, onDismiss = { menuTrack = null }, onAddToPlaylist = { pickerTracks = listOf(it) },
                        onSave = { startExport(listOf(it)) },
                        onShare = { startShare(listOf(it)) },
                        onSelect = { selecting = true; selected = setOf(it.id) },
                        onGoAlbum = { stack.add(Dest.Album(it)); playerOpen = false },
                        onGoArtist = { stack.add(Dest.Artist(it)); playerOpen = false },
                    )
                }
            }
            GlassSheet(visible = pickerTracks != null, onDismiss = { pickerTracks = null }, backdrop = overlayBackdrop) {
                if (lastPicker.isNotEmpty()) PlaylistPickerContent(
                    lastPicker, vm,
                    onDone = { pickerTracks = null; clearSelection() },
                    onCreate = { dialog = PlaylistDialog.Create(lastPicker.map { it.id }); pickerTracks = null }
                )
            }
            GlassSheet(visible = queueOpen, onDismiss = { queueOpen = false }, backdrop = overlayBackdrop) { QueueContent(vm) }
            GlassSheet(visible = audioInfoOpen, onDismiss = { audioInfoOpen = false }, backdrop = overlayBackdrop) { AudioInfoContent() }
            GlassDialog(visible = dialog != null, onDismiss = { dialog = null }, backdrop = overlayBackdrop) {
                when (val d = lastDialog) {
                    is PlaylistDialog.Create -> NameDialogContent(
                        stringResource(R.string.new_playlist), "", stringResource(R.string.create), overlayBackdrop,
                        onConfirm = { vm.createPlaylist(it, d.firstTracks); if (d.firstTracks.size > 1) clearSelection(); dialog = null }, onDismiss = { dialog = null }
                    )
                    is PlaylistDialog.Rename -> NameDialogContent(
                        stringResource(R.string.rename), d.name, stringResource(R.string.ok), overlayBackdrop,
                        onConfirm = { newName ->
                            vm.renamePlaylist(d.id, newName)
                            val i = stack.indexOfLast { it is Dest.Playlist && it.id == d.id }
                            if (i >= 0) stack[i] = Dest.Playlist(d.id, newName.trim().ifEmpty { "Playlist" })
                            plVersion++
                            dialog = null
                        }, onDismiss = { dialog = null }
                    )
                    is PlaylistDialog.ConfirmImport -> ConfirmDialogContent(
                        stringResource(R.string.import_title), stringResource(R.string.import_message),
                        stringResource(R.string.import_confirm), overlayBackdrop,
                        onConfirm = {
                            dialog = null
                            vm.importBackup(d.uri) { r -> toast(if (r.isSuccess) R.string.import_done else R.string.import_failed) }
                        },
                        onDismiss = { dialog = null }
                    )
                    null -> {}
                }
            }
        }
    }
}

@Composable
private fun QueueContent(vm: MainViewModel) {
    val ui by vm.player.state.collectAsState()
    Text(
        stringResource(R.string.queue), fontWeight = FontWeight.Bold, fontSize = 18.sp,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp)
    )
    val queueState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(Unit) { if (ui.index > 2) queueState.scrollToItem(ui.index - 1) }
    Box(Modifier.heightIn(max = 480.dp)) {
        LazyColumn(Modifier.heightIn(max = 480.dp), state = queueState) {
            itemsIndexed(ui.queue, key = { i, t -> "${t.id}#$i" }) { i, t ->
                val current = i == ui.index
                Row(
                    Modifier.fillMaxWidth().clickable { vm.player.skipTo(i) }
                        .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(46.dp)) {
                        Cover(t.coverArt, Modifier.fillMaxSize(), 10.dp, px = 100)
                        if (current) Box(
                            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) { EqBars(ui.isPlaying, Accent, 24.dp) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = if (current) Accent else Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(t.subtitle, color = Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    if (!current) androidx.compose.material3.IconButton(onClick = { vm.player.removeFromQueue(i) }) {
                        Icon(Icons.Rounded.Close, null, tint = Muted)
                    }
                }
            }
        }
        ListScrollbar(queueState)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SelectionBar(
    count: Int, backdrop: com.kyant.backdrop.Backdrop, allSelected: Boolean, onSelectAll: () -> Unit,
    onCancel: () -> Unit, onSave: () -> Unit, onShare: () -> Unit, onPlaylist: () -> Unit, onDownload: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            com.nebulamusic.app.glass.GlassIconButton(onCancel, backdrop, size = 44.dp) { Icon(Icons.Rounded.Close, null, tint = Color.White) }
            Text(stringResource(R.string.n_selected, count), fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f), maxLines = 1)
            com.nebulamusic.app.glass.GlassButton(onSelectAll, backdrop, height = 44.dp) {
                Icon(if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (allSelected) R.string.deselect_all else R.string.select_all), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.nebulamusic.app.glass.GlassIconButton(onPlaylist, backdrop, size = 48.dp) { Icon(Icons.Rounded.PlaylistAdd, null, tint = Color.White) }
            com.nebulamusic.app.glass.GlassIconButton(onShare, backdrop, size = 48.dp) { Icon(Icons.Rounded.Share, null, tint = Color.White) }
            com.nebulamusic.app.glass.GlassIconButton(onDownload, backdrop, size = 48.dp) { Icon(Icons.Rounded.DownloadForOffline, null, tint = Color.White) }
            com.nebulamusic.app.glass.GlassButton(onSave, backdrop, modifier = Modifier.weight(1f), height = 48.dp, tint = GlassDefaults.Accent) {
                Icon(Icons.Rounded.SaveAlt, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.save), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
            }
        }
    }
}
