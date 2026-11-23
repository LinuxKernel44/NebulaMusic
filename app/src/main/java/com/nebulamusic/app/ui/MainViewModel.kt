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
import kotlinx.coroutines.sync.withLock
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

    /** The chosen order of each Library list (default: by name). */
    val sorts = MutableStateFlow(readSorts(c.prefs.librarySorts))
    fun sortOf(section: LibSection) = sorts.value[section] ?: SortSpec()
    fun setSort(section: LibSection, spec: SortSpec) {
        sorts.value = sorts.value + (section to spec)
        c.prefs.librarySorts = sorts.value.entries.joinToString(",") { "${it.key.name}=${it.value.key.name}:${if (it.value.reverse) 1 else 0}" }
    }

    /** Display level per Library category: 1 = list, 2..4 = grid columns. Albums default to 2 columns, the rest to a list. */
    val levels = MutableStateFlow(readLevels(c.prefs.libraryLevels))
    fun levelOf(section: LibSection) = levels.value[section] ?: if (section == LibSection.ALBUMS) 2 else 1
    fun setLevel(section: LibSection, level: Int) {
        levels.value = levels.value + (section to level.coerceIn(MIN_LEVEL, MAX_LEVEL))
        c.prefs.libraryLevels = levels.value.entries.joinToString(",") { "${it.key.name}=${it.value}" }
    }

    /** Column count of the album grid in every genre screen (one shared setting). */
    val genreLevel = MutableStateFlow(c.prefs.genreLevel.coerceIn(MIN_LEVEL, MAX_LEVEL))
    fun setGenreLevel(level: Int) {
        genreLevel.value = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        c.prefs.genreLevel = genreLevel.value
    }

    private fun readLevels(raw: String): Map<LibSection, Int> = raw.split(',').mapNotNull { part ->
        runCatching {
            val (sec, lv) = part.split('=')
            LibSection.valueOf(sec) to lv.toInt().coerceIn(MIN_LEVEL, MAX_LEVEL)
        }.getOrNull()
    }.toMap()

    private fun readSorts(raw: String): Map<LibSection, SortSpec> = raw.split(',').mapNotNull { part ->
        runCatching {
            val (sec, rest) = part.split('=')
            val (key, rev) = rest.split(':')
            LibSection.valueOf(sec) to SortSpec(SortKey.valueOf(key), rev == "1")
        }.getOrNull()
    }.filter { (sec, sp) -> sp.key in sortKeysFor(sec) }.toMap()

    // Orders the server can produce page by page are fetched that way; any other order (or a reversed one) needs the whole
    // list, loaded once in the background and sorted here.
    fun albumsNative(sp: SortSpec) = when (sp.key) {
        SortKey.RECENT, SortKey.FREQUENT, SortKey.RANDOM, SortKey.STARRED -> true
        SortKey.NAME, SortKey.ARTIST, SortKey.ADDED -> !sp.reverse
        else -> false
    }
    fun songsNative(sp: SortSpec) = sp.key == SortKey.NAME && !sp.reverse
    private fun albumType(sp: SortSpec) = when (sp.key) {
        SortKey.ARTIST -> AlbumSort.ARTIST; SortKey.ADDED -> AlbumSort.NEWEST; SortKey.RECENT -> AlbumSort.RECENT
        SortKey.FREQUENT -> AlbumSort.FREQUENT; SortKey.RANDOM -> AlbumSort.RANDOM; SortKey.STARRED -> AlbumSort.STARRED
        else -> AlbumSort.NAME
    }
    val albums = Paged(this, 60) { s, off -> s.albums(albumType(sortOf(LibSection.ALBUMS)), off, 60) }
    val songs = Paged(this, 100) { s, off -> s.allSongs(off, 100) }

    private val _albumsAll = MutableStateFlow<Load<List<Album>>>(Load.Loading)
    val albumsAll: StateFlow<Load<List<Album>>> = _albumsAll.asStateFlow()
    private val _songsAll = MutableStateFlow<Load<List<Track>>>(Load.Loading)
    val songsAll: StateFlow<Load<List<Track>>> = _songsAll.asStateFlow()

    fun loadAllAlbums(force: Boolean = false) {
        if (!force && _albumsAll.value is Load.Ok) return
        _albumsAll.value = Load.Loading
        viewModelScope.launch {
            _albumsAll.value = try {
                Load.Ok(withContext(Dispatchers.IO) {
                    val s = session.value ?: throw java.io.IOException("Not logged in")
                    buildList { var off = 0; while (true) { val p = s.albums(AlbumSort.NAME, off, 500); addAll(p); if (p.size < 500) break; off += 500 } }
                })
            } catch (e: Exception) { errorOf(e) }
        }
    }

    fun loadAllSongs(force: Boolean = false) {
        if (!force && _songsAll.value is Load.Ok) return
        _songsAll.value = Load.Loading
        viewModelScope.launch {
            _songsAll.value = try {
                Load.Ok(withContext(Dispatchers.IO) {
                    val s = session.value ?: throw java.io.IOException("Not logged in")
                    buildList { var off = 0; while (true) { val p = s.allSongs(off, 500); addAll(p); if (p.size < 500) break; off += 500 } }
                })
            } catch (e: Exception) { errorOf(e) }
        }
    }

    /** Songs saved for offline listening on the active server. */
    val downloadedTracks: StateFlow<List<Track>> = c.downloadRows
        .map { rows -> rows.mapNotNull { r -> runCatching { TrackJson.decodeFromString<Track>(r.json).copy(addedAt = r.completedAt) }.getOrNull() } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var loadedFor: String? = null

    init {
        c.autoQueue.start()
        viewModelScope.launch {
            session.collect { s ->
                if (s == null) { loadedFor = null; _conn.value = Conn.UNKNOWN; return@collect }
                if (loadedFor != s.server.id) { loadedFor = s.server.id; connect(s) }
            }
        }
        viewModelScope.launch { sorts.map { sortOf(LibSection.ALBUMS) }.distinctUntilChanged().drop(1).collect { sp -> if (albumsNative(sp)) albums.reset() else loadAllAlbums() } }
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
        albums.reset(); songs.reset(); _albumsAll.value = Load.Loading; _songsAll.value = Load.Loading
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

    // ---- Lyrics -----------------------------------------------------------------------------
    private val lyricsStore = LyricsStore(java.io.File(getApplication<Application>().cacheDir, "lyrics"))

    /**
     * Lyrics of [t]: disk cache first ("no lyrics" answers are re-checked after a week), then the server. When the
     * server cannot be reached, a stale cache entry is better than nothing; otherwise the failure is thrown.
     */
    suspend fun lyricsFor(t: Track): Lyrics? = withContext(Dispatchers.IO) {
        val week = 7L * 24 * 3600 * 1000
        lyricsStore.read(t.id, week)?.let { return@withContext it.getOrNull() }
        try {
            val l = api { it.lyrics(t.id, t.artist, t.title) }
            lyricsStore.write(t.id, l)
            l
        } catch (e: java.io.IOException) {
            lyricsStore.read(t.id)?.getOrNull() ?: throw e
        }
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

    val updater get() = c.updater

    // ---- AI playlists ----------------------------------------------------------------------
    sealed interface AiUi {
        data object Idle : AiUi
        data class Working(val stage: Int) : AiUi
        data class Done(val name: String, val count: Int) : AiUi
        data class Failed(val reason: String) : AiUi
    }
    private val _ai = MutableStateFlow<AiUi>(AiUi.Idle)
    val ai: StateFlow<AiUi> = _ai
    fun aiReset() { if (_ai.value !is AiUi.Working) _ai.value = AiUi.Idle }

    fun generateAiPlaylist(request: String, count: Int) {
        if (_ai.value is AiUi.Working || request.isBlank()) return
        _ai.value = AiUi.Working(1)
        viewModelScope.launch {
            try {
                val r = api { s ->
                    c.aiPlaylist.generate(prefs.aiUrl.value, prefs.aiModel.value, request.trim(), count, s) { _ai.value = AiUi.Working(it) }
                }
                api { it.createPlaylist(r.name, r.tracks.map { t -> t.id }) }
                refreshPlaylists()
                _ai.value = AiUi.Done(r.name, r.tracks.size)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ai.value = AiUi.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ---- Playlists -------------------------------------------------------------------------
    /** Pinned playlist ids, in the order they were pinned; they stay above the other playlists. */
    val pinned = MutableStateFlow(c.prefs.pinnedPlaylists.split(',').filter { it.isNotBlank() })
    fun togglePin(id: String) {
        val cur = pinned.value
        pinned.value = if (id in cur) cur - id else cur + id
        c.prefs.pinnedPlaylists = pinned.value.joinToString(",")
    }

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

    // Removals run one after the other: each index refers to the list as the previous removal left it.
    private val removeLock = kotlinx.coroutines.sync.Mutex()
    fun removeFromPlaylist(playlistId: String, index: Int, done: () -> Unit = {}) {
        viewModelScope.launch {
            removeLock.withLock { runCatching { api { it.removeFromPlaylist(playlistId, listOf(index)) } } }
            refreshPlaylists(); done()
        }
    }

    // ---- Server library scan ----------------------------------------------------------------
    sealed interface ScanUi {
        data object Idle : ScanUi
        data class Running(val count: Int, val full: Boolean) : ScanUi
        data object Done : ScanUi
        data class Failed(val message: String) : ScanUi
    }
    private val _scan = MutableStateFlow<ScanUi>(ScanUi.Idle)
    val scan: StateFlow<ScanUi> = _scan.asStateFlow()

    /** Tells the server to scan its music folders (new covers, new files...), waits for it, then reloads everything. */
    fun scanLibrary(full: Boolean) {
        if (_scan.value is ScanUi.Running) return
        viewModelScope.launch {
            _scan.value = ScanUi.Running(0, full)
            try {
                var p = api { it.startScan(full) }
                var guard = 0
                while (p.scanning && guard++ < 900) {
                    _scan.value = ScanUi.Running(p.count, full)
                    kotlinx.coroutines.delay(2000)
                    p = api { it.scanStatus() }
                }
                _scan.value = ScanUi.Done
                clearCoverCache() // covers may have changed on the server
                albums.reset(); songs.reset(); refreshAll()
            } catch (e: Exception) {
                _scan.value = ScanUi.Failed(errorOf(e).message)
            }
        }
    }

    /** Forgets every downloaded cover so changed artwork is fetched again. */
    fun clearCoverCache() {
        val loader = coil.Coil.imageLoader(getApplication())
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
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

    /** Removes the offline copies of these songs (the songs stay on the server). */
    fun deleteDownloads(list: List<Track>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (t in list) {
                c.db.downloads().get(t.id)?.let { File(it.path).delete() }
                c.db.downloads().remove(t.id)
            }
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

    val listenBrainz = c.listenBrainz
    val listenBrainzMessage = MutableStateFlow<String?>(null)
    fun listenBrainzConnect(token: String) = viewModelScope.launch {
        listenBrainzMessage.value = null
        runCatching { listenBrainz.connect(token) }.onFailure { listenBrainzMessage.value = it.message ?: "Connection failed (${it.javaClass.simpleName})" }
    }
    fun listenBrainzDisconnect() = listenBrainz.disconnect()

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
