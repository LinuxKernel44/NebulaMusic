package com.nebulamusic.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nebulamusic.app.NebulaApp
import com.nebulamusic.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import java.io.File

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String, val auth: Boolean = false) : Load<Nothing>
}

/** A list fetched page by page from the server. */
class Paged<T>(private val vm: MainViewModel, private val pageSize: Int, private val fetch: suspend (Session, Int) -> List<T>) {
    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items.asStateFlow()
    private val _state = MutableStateFlow<Load<Unit>>(Load.Loading)
    val state: StateFlow<Load<Unit>> = _state.asStateFlow()
    private var end = false
    private var job: Job? = null
    private var generation = 0

    fun reset() {
        generation++
        job?.cancel(); job = null
        end = false
        _items.value = emptyList()
        _state.value = Load.Loading
        more()
    }

    fun more() {
        if (end || job?.isActive == true) return
        val gen = generation
        job = vm.viewModelScope.launch {
            val s = vm.session.value ?: return@launch
            try {
                val page = withContext(Dispatchers.IO) { fetch(s, _items.value.size) }
                if (gen != generation) return@launch
                if (page.size < pageSize) end = true
                _items.value = _items.value + page
                _state.value = Load.Ok(Unit)
            } catch (e: Exception) {
                if (gen == generation) _state.value = vm.errorOf(e)
            }
        }
    }
}

data class HomeData(
    val recent: List<Album> = emptyList(),
    val newest: List<Album> = emptyList(),
    val frequent: List<Album> = emptyList(),
    val random: List<Album> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as NebulaApp).container
    val player = c.player
    val session: StateFlow<Session?> = c.session
    val servers = c.servers.servers
    val activeServerId = c.servers.activeId
    val prefs = c.prefs
    val starred = c.starred
    val localPaths = c.localPaths
    val downloadProgress = c.downloads.progress

    fun errorOf(e: Throwable): Load.Err = when {
        e is SubsonicException -> Load.Err(e.message ?: "Server error", e.isAuth)
        e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException ->
            Load.Err("Cannot reach the server. Check the address and your connection.")
        else -> Load.Err(e.message ?: e.javaClass.simpleName)
    }

    // ---- Connection --------------------------------------------------------------------------
    enum class Conn { UNKNOWN, ONLINE, OFFLINE, AUTH }
    private val _conn = MutableStateFlow(Conn.UNKNOWN)
    val conn: StateFlow<Conn> = _conn.asStateFlow()
    private val _serverInfo = MutableStateFlow("")
    val serverInfo: StateFlow<String> = _serverInfo.asStateFlow()

    // ---- Library caches ----------------------------------------------------------------------
    private val _home = MutableStateFlow<Load<HomeData>>(Load.Loading)
    val home: StateFlow<Load<HomeData>> = _home.asStateFlow()

    private val _artists = MutableStateFlow<Load<List<Artist>>>(Load.Loading)
    val artists: StateFlow<Load<List<Artist>>> = _artists.asStateFlow()

    private val _genres = MutableStateFlow<Load<List<Genre>>>(Load.Loading)
    val genres: StateFlow<Load<List<Genre>>> = _genres.asStateFlow()

    private val _playlists = MutableStateFlow<Load<List<Playlist>>>(Load.Loading)
    val playlists: StateFlow<Load<List<Playlist>>> = _playlists.asStateFlow()

    private val _favorites = MutableStateFlow<Load<SearchResults>>(Load.Loading)
    val favorites: StateFlow<Load<SearchResults>> = _favorites.asStateFlow()

    val albumSort = MutableStateFlow(AlbumSort.NAME)
    val albums = Paged(this, 60) { s, off -> s.albums(albumSort.value, off, 60) }
    val songs = Paged(this, 100) { s, off -> s.allSongs(off, 100) }

    /** Songs saved for offline listening on the active server. */
    val downloadedTracks: StateFlow<List<Track>> = c.downloadRows
        .map { rows -> rows.mapNotNull { runCatching { TrackJson.decodeFromString<Track>(it.json) }.getOrNull() } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var loadedFor: String? = null

    init {
        viewModelScope.launch {
            session.collect { s ->
                if (s == null) { loadedFor = null; _conn.value = Conn.UNKNOWN; return@collect }
                if (loadedFor != s.server.id) { loadedFor = s.server.id; connect(s) }
            }
        }
        viewModelScope.launch { albumSort.drop(1).collect { albums.reset() } }
    }

    private suspend fun connect(s: Session) {
        _conn.value = Conn.UNKNOWN
        c.starred.value = emptySet()
        try {
            _serverInfo.value = withContext(Dispatchers.IO) { s.ping() }
            _conn.value = Conn.ONLINE
        } catch (e: Exception) {
            _conn.value = if (e is SubsonicException && e.isAuth) Conn.AUTH else Conn.OFFLINE
            _home.value = errorOf(e); _artists.value = errorOf(e); _genres.value = errorOf(e)
            _playlists.value = errorOf(e); _favorites.value = errorOf(e)
            return
        }
        albums.reset(); songs.reset()
        refreshAll()
    }

    fun refreshAll() {
        refreshHome(); refreshArtists(); refreshGenres(); refreshPlaylists(); refreshFavorites()
    }

    fun retryConnection() {
        val s = session.value ?: return
        viewModelScope.launch { loadedFor = null; loadedFor = s.server.id; connect(s) }
    }

    private fun <T> load(target: MutableStateFlow<Load<T>>, keepOld: Boolean = true, block: suspend (Session) -> T) {
        val s = session.value ?: return
        if (!keepOld || target.value !is Load.Ok) target.value = Load.Loading
        viewModelScope.launch {
            target.value = try { Load.Ok(withContext(Dispatchers.IO) { block(s) }) } catch (e: Exception) {
                if (target.value is Load.Ok && e !is SubsonicException) target.value else errorOf(e)
            }
        }
    }

    fun refreshHome() = load(_home) { s ->
        val recent = runCatching { s.albums(AlbumSort.RECENT, 0, 20) }.getOrDefault(emptyList())
        val newest = s.albums(AlbumSort.NEWEST, 0, 20)
        val frequent = runCatching { s.albums(AlbumSort.FREQUENT, 0, 20) }.getOrDefault(emptyList())
        val random = runCatching { s.albums(AlbumSort.RANDOM, 0, 20) }.getOrDefault(emptyList())
        HomeData(recent, newest, frequent, random)
    }

    fun refreshArtists() = load(_artists) { s -> s.artists().sortedBy { it.name.fold() } }
    fun refreshGenres() = load(_genres) { s -> s.genres().sortedByDescending { it.songCount } }
    fun refreshPlaylists() = load(_playlists) { s -> s.playlists().sortedBy { it.name.fold() } }
    fun refreshFavorites() {
        load(_favorites) { s -> s.starred().also { r -> c.starred.value = r.tracks.map { it.id }.toSet() } }
    }

    // ---- Detail screens (fetched on demand by the screens themselves) -------------------------
    suspend fun <T> api(block: suspend (Session) -> T): T {
        val s = session.value ?: throw java.io.IOException("Not logged in")
        return withContext(Dispatchers.IO) { block(s) }
    }

    // ---- Playback ---------------------------------------------------------------------------
    fun play(list: List<Track>, index: Int) = player.playQueue(list, index)
    fun playNext(list: List<Track>) = player.playNext(list)
    fun addToQueue(list: List<Track>) = player.addToQueue(list)

    fun playAlbum(id: String, shuffle: Boolean = false) {
        viewModelScope.launch {
            runCatching { api { it.album(id).second } }.onSuccess { if (it.isNotEmpty()) play(if (shuffle) it.shuffled() else it, 0) }
        }
    }

    fun playRandom() {
        viewModelScope.launch { runCatching { api { it.randomSongs(60) } }.onSuccess { if (it.isNotEmpty()) play(it, 0) } }
    }

    fun playGenre(genre: String) {
        viewModelScope.launch { runCatching { api { it.randomSongs(80, genre) } }.onSuccess { if (it.isNotEmpty()) play(it, 0) } }
    }

    // ---- Favorites -------------------------------------------------------------------------
    fun toggleFavorite(id: String) {
        c.toggleStar(id)
        viewModelScope.launch { kotlinx.coroutines.delay(1200); refreshFavorites() }
    }

    fun setFavorite(id: String, on: Boolean) { if ((id in starred.value) != on) toggleFavorite(id) }

    fun toggleStarContainer(id: String, kind: StarKind, on: Boolean) {
        viewModelScope.launch {
            runCatching { api { if (on) it.star(id, kind) else it.unstar(id, kind) } }
            refreshFavorites()
        }
    }

    // ---- Playlists -------------------------------------------------------------------------
    fun createPlaylist(name: String, firstTracks: List<String> = emptyList()) {
        viewModelScope.launch {
            runCatching { api { it.createPlaylist(name.trim().ifEmpty { "Playlist" }, firstTracks) } }
            refreshPlaylists()
        }
    }

    fun addAllToPlaylists(playlistIds: Collection<String>, trackIds: List<String>) {
        viewModelScope.launch {
            for (pid in playlistIds) runCatching { api { it.addToPlaylist(pid, trackIds) } }
            refreshPlaylists()
        }
    }

    fun renamePlaylist(id: String, name: String) {
        viewModelScope.launch { runCatching { api { it.renamePlaylist(id, name.trim().ifEmpty { "Playlist" }) } }; refreshPlaylists() }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch { runCatching { api { it.deletePlaylist(id) } }; refreshPlaylists() }
    }

    fun removeFromPlaylist(playlistId: String, index: Int, done: () -> Unit = {}) {
        viewModelScope.launch { runCatching { api { it.removeFromPlaylist(playlistId, listOf(index)) } }; refreshPlaylists(); done() }
    }

    // ---- Offline ---------------------------------------------------------------------------
    fun download(list: List<Track>) {
        list.filter { !localPaths.value.containsKey(it.id) && !downloadProgress.value.containsKey(it.id) }.forEach {
            c.downloads.set(it.id, 0f) // shows up as "downloading" straight away, even while queued
            DownloadWorker.enqueue(getApplication(), it)
        }
    }

    fun downloadAlbum(id: String) {
        viewModelScope.launch { runCatching { api { it.album(id).second } }.onSuccess(::download) }
    }

    fun deleteDownload(t: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            c.db.downloads().get(t.id)?.let { File(it.path).delete() }
            c.db.downloads().remove(t.id)
        }
    }

    fun deleteAllDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            c.db.downloads().all().forEach { File(it.path).delete(); c.db.downloads().remove(it.trackId) }
        }
    }

    val downloadBytes: StateFlow<Long> = c.downloadRows.map { r -> r.sumOf { it.bytes } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    // ---- Waveform --------------------------------------------------------------------------
    private val _wave = MutableStateFlow(WaveUi("", null, 0L))
    val wave: StateFlow<WaveUi> = _wave.asStateFlow()

    init {
        viewModelScope.launch {
            player.state.map { it.track }.distinctUntilChangedBy { it?.id }.filterNotNull().collectLatest { t ->
                val bins = c.repo.waveform(t, localPaths.value[t.id])
                _wave.value = WaveUi(t.id, bins, t.durationSec * 1000L)
            }
        }
    }

    init {
        // Warm the waveform of the next song so it is ready the moment the user skips.
        viewModelScope.launch {
            player.state.map { it.queue.getOrNull(it.index + 1) }.distinctUntilChangedBy { it?.id }.filterNotNull().collectLatest { n ->
                kotlinx.coroutines.delay(1500)
                c.repo.waveform(n, localPaths.value[n.id])
            }
        }
    }

    // ---- Queue persistence -----------------------------------------------------------------
    private val queueFile get() = File(getApplication<Application>().filesDir, "queue.json")

    init {
        // Bring back the last queue on launch (paused, track starts from 0:00), then keep remembering it.
        viewModelScope.launch {
            val st = player.state.first { it.connected }
            session.first { it != null }
            if (st.queue.isEmpty()) {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val o = org.json.JSONObject(queueFile.readText())
                        o.getString("server") to (TrackJson.decodeFromString(ListSerializer(Track.serializer()), o.getString("tracks")) to o.getInt("index"))
                    }.getOrNull()
                }
                if (saved != null && saved.first == session.value?.server?.id && saved.second.first.isNotEmpty()) {
                    player.restoreQueue(saved.second.first, saved.second.second)
                }
            }
            player.state.map { Triple(it.queue.map { t -> t.id }, it.index, it.queue) }
                .distinctUntilChanged { a, b -> a.first == b.first && a.second == b.second }
                .debounce(800)
                .collect { (ids, idx, queue) ->
                    val sid = session.value?.server?.id ?: return@collect
                    if (ids.isEmpty()) return@collect
                    withContext(Dispatchers.IO) {
                        runCatching {
                            queueFile.writeText(
                                org.json.JSONObject().put("server", sid).put("index", idx)
                                    .put("tracks", TrackJson.encodeToString(ListSerializer(Track.serializer()), queue)).toString()
                            )
                        }
                    }
                }
        }
    }

    // ---- Servers ---------------------------------------------------------------------------
    val http get() = c.http

    /** Validates and saves a server, makes it the active one, and returns its description. */
    suspend fun addServer(existing: ServerConfig?, name: String, url: String, user: String, password: String?): String {
        val cfg = (existing ?: ServerConfig(name = name, url = url, username = user)).copy(
            name = name.ifBlank { url.removePrefix("https://").removePrefix("http://") }, url = url, username = user
        )
        val pw = password ?: existing?.let { c.servers.password(it) }.orEmpty()
        val info = withContext(Dispatchers.IO) { testConnection(c.http, cfg, pw) }
        c.servers.save(cfg, password)
        c.reloadSession()
        loadedFor = null
        return info
    }

    fun switchServer(id: String) {
        c.servers.setActive(id); c.reloadSession()
    }

    fun removeServer(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            c.db.downloads().all().filter { it.serverId == id }.forEach { File(it.path).delete() }
            c.db.downloads().removeServer(id)
        }
        c.servers.remove(id); c.reloadSession()
        if (c.servers.servers.value.isEmpty()) player.stop()
    }

    fun serverPassword(s: ServerConfig) = c.servers.password(s)

    fun setQuality(q: StreamQuality) { prefs.setQuality(q) }
    fun settingsChanged() = player.settingsChanged()

    fun exportBackup(uri: android.net.Uri, done: (Result<Int>) -> Unit) {
        viewModelScope.launch { done(runCatching { c.backup.export(uri) }) }
    }

    fun importBackup(uri: android.net.Uri, done: (Result<Unit>) -> Unit) {
        viewModelScope.launch { done(runCatching { c.backup.import(uri) }) }
    }

    // ---- Save to folder / share ------------------------------------------------------------
    val exportAsk = prefs.exportAsk
    val exportDir = prefs.exportDir.map { it?.let(android.net.Uri::parse) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.exportDir.value?.let(android.net.Uri::parse))

    fun setExportAsk(v: Boolean) = prefs.setExportAsk(v)

    fun setExportDir(uri: android.net.Uri) {
        prefs.exportDir.value?.takeIf { it != uri.toString() }?.let { c.exporter.release(it) }
        c.exporter.persist(uri)
        prefs.setExportDir(uri.toString())
    }

    fun savedExportDir(): android.net.Uri? =
        if (prefs.exportAsk.value) null
        else prefs.exportDir.value?.takeIf { c.exporter.isUsable(it) }?.let(android.net.Uri::parse)

    suspend fun folderName(uri: android.net.Uri): String? = c.exporter.folderName(uri)

    fun saveToFolder(tracks: List<Track>, tree: android.net.Uri, done: (Exporter.Result) -> Unit) {
        viewModelScope.launch { done(c.exporter.export(tracks, tree)) }
    }

    fun prepareShare(tracks: List<Track>, done: (List<android.net.Uri>) -> Unit) {
        viewModelScope.launch { done(c.exporter.prepareShare(tracks)) }
    }

    fun cacheSizeMb(): Long =
        File(getApplication<Application>().cacheDir, "audio").walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)

    fun clearAudioCache(): Boolean = false // the cache is owned by the playback service; see Settings (applies at restart)
}

/** Waveform of the track that is (or just was) loaded in the player. */
class WaveUi(val trackId: String, val bins: FloatArray?, val durationMs: Long)
