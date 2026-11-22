package com.nebulamusic.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReplayGainMode { OFF, TRACK, ALBUM }
enum class PlayerStyle { WAVEFORM, CLASSIC }

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("nebula_prefs", Context.MODE_PRIVATE)

    private fun <T> flow(v: T) = MutableStateFlow(v)

    private val _grid = flow(sp.getBoolean("grid", true))
    val grid: StateFlow<Boolean> = _grid.asStateFlow()
    fun setGrid(v: Boolean) { sp.edit().putBoolean("grid", v).apply(); _grid.value = v }

    // Streaming. Audiophile default: the original file, untouched.
    private val _quality = flow(
        runCatching { StreamQuality.valueOf(sp.getString("quality", null)!!) }.getOrDefault(StreamQuality.ORIGINAL)
    )
    val quality: StateFlow<StreamQuality> = _quality.asStateFlow()
    fun setQuality(v: StreamQuality) { sp.edit().putString("quality", v.name).apply(); _quality.value = v }

    private val _replayGain = flow(
        runCatching { ReplayGainMode.valueOf(sp.getString("rg", null)!!) }.getOrDefault(ReplayGainMode.OFF)
    )
    val replayGain: StateFlow<ReplayGainMode> = _replayGain.asStateFlow()
    fun setReplayGain(v: ReplayGainMode) { sp.edit().putString("rg", v.name).apply(); _replayGain.value = v }

    private val _crossfade = flow(sp.getInt("crossfade", 0))
    /** Seconds of fade-out / fade-in between two tracks (0 = off, gapless). */
    val crossfade: StateFlow<Int> = _crossfade.asStateFlow()
    fun setCrossfade(v: Int) { sp.edit().putInt("crossfade", v).apply(); _crossfade.value = v }

    private val _cacheMb = flow(sp.getInt("cache_mb", 1024))
    val cacheMb: StateFlow<Int> = _cacheMb.asStateFlow()
    fun setCacheMb(v: Int) { sp.edit().putInt("cache_mb", v).apply(); _cacheMb.value = v }

    private val _bitPerfect = flow(sp.getBoolean("bit_perfect", false))
    /** Ask Android for a bit-perfect USB DAC mixer when one is connected (Android 14+). */
    val bitPerfect: StateFlow<Boolean> = _bitPerfect.asStateFlow()
    fun setBitPerfect(v: Boolean) { sp.edit().putBoolean("bit_perfect", v).apply(); _bitPerfect.value = v }

    private val _hiRes = flow(sp.getBoolean("hires_float", true))
    /** Float PCM output so 24-bit audio is not truncated to 16 bits. */
    val hiResOutput: StateFlow<Boolean> = _hiRes.asStateFlow()
    fun setHiResOutput(v: Boolean) { sp.edit().putBoolean("hires_float", v).apply(); _hiRes.value = v }

    private val _scrobble = flow(sp.getBoolean("scrobble", true))
    val scrobble: StateFlow<Boolean> = _scrobble.asStateFlow()
    fun setScrobble(v: Boolean) { sp.edit().putBoolean("scrobble", v).apply(); _scrobble.value = v }

    private val _autoplay = flow(sp.getBoolean("autoplay", true))
    /** Keep adding coherent songs when the queue runs out. */
    val autoplay: StateFlow<Boolean> = _autoplay.asStateFlow()
    fun setAutoplay(v: Boolean) { sp.edit().putBoolean("autoplay", v).apply(); _autoplay.value = v }

    private val _playerStyle = flow(
        runCatching { PlayerStyle.valueOf(sp.getString("player_style", null)!!) }.getOrDefault(PlayerStyle.WAVEFORM)
    )
    /** Full player layout: waveform seekbar (default) or a classic thin seekbar. */
    val playerStyle: StateFlow<PlayerStyle> = _playerStyle.asStateFlow()
    fun setPlayerStyle(v: PlayerStyle) { sp.edit().putString("player_style", v.name).apply(); _playerStyle.value = v }

    private val _aiUrl = flow(sp.getString("ai_url", "") ?: "")
    /** Base URL of an OpenAI-compatible chat server used for AI playlists (for example http://host:8180/v1). */
    val aiUrl: StateFlow<String> = _aiUrl.asStateFlow()
    fun setAiUrl(v: String) { val t = v.trim(); sp.edit().putString("ai_url", t).apply(); _aiUrl.value = t }

    private val _aiModel = flow(sp.getString("ai_model", "") ?: "")
    /** Optional model name sent to the AI server (blank = the server default). */
    val aiModel: StateFlow<String> = _aiModel.asStateFlow()
    fun setAiModel(v: String) { val t = v.trim(); sp.edit().putString("ai_model", t).apply(); _aiModel.value = t }

    private val _autoUpdate = flow(sp.getBoolean("auto_update", true))
    /** Look for a new version when the app starts (at most every 12 hours). */
    val autoUpdate: StateFlow<Boolean> = _autoUpdate.asStateFlow()
    fun setAutoUpdate(v: Boolean) { sp.edit().putBoolean("auto_update", v).apply(); _autoUpdate.value = v }
    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) { sp.edit().putLong("last_update_check", v).apply() }

    private val _highAccuracy = flow(sp.getBoolean("high_accuracy", false))
    /** Use the user's Last.fm account to choose the songs that are added automatically. */
    val highAccuracy: StateFlow<Boolean> = _highAccuracy.asStateFlow()
    fun setHighAccuracy(v: Boolean) { sp.edit().putBoolean("high_accuracy", v).apply(); _highAccuracy.value = v }

    private val _lbListens = flow(sp.getBoolean("lb_listens", true))
    /** Submit finished listens (and "now playing") to ListenBrainz while an account is connected. */
    val listenBrainzListens: StateFlow<Boolean> = _lbListens.asStateFlow()
    fun setListenBrainzListens(v: Boolean) { sp.edit().putBoolean("lb_listens", v).apply(); _lbListens.value = v }

    /** Ids of the pinned playlists, in pinning order (comma separated). */
    var pinnedPlaylists: String
        get() = sp.getString("pinned_playlists", "").orEmpty()
        set(v) { sp.edit().putString("pinned_playlists", v).apply() }

    /** Where the user was: bottom tab and Library category, restored after a restart. */
    var lastTab: String
        get() = sp.getString("last_tab", "").orEmpty()
        set(v) { sp.edit().putString("last_tab", v).apply() }
    var lastSection: String
        get() = sp.getString("last_section", "").orEmpty()
        set(v) { sp.edit().putString("last_section", v).apply() }

    /** The Library sort of each category, e.g. "ALBUMS=YEAR:1,SONGS=NAME:0" (parsed by the UI). */
    var librarySorts: String
        get() = sp.getString("library_sorts", "").orEmpty()
        set(v) { sp.edit().putString("library_sorts", v).apply() }

    /** The display level of each Library category, e.g. "ALBUMS=3,SONGS=1" (1 = list, 2..4 = grid columns). */
    var libraryLevels: String
        get() = sp.getString("library_levels", "").orEmpty()
        set(v) { sp.edit().putString("library_levels", v).apply() }

    /** Display level of the album grid inside a genre (shared by every genre): 1 = list, 2..4 = grid columns. */
    var genreLevel: Int
        get() = sp.getInt("genre_level", 2)
        set(v) { sp.edit().putInt("genre_level", v).apply() }

    private val _lightGlass = flow(sp.getBoolean("light_glass", false))
    /** Cheaper glass (no refraction / shadows): cooler phone, longer battery. */
    val lightGlass: StateFlow<Boolean> = _lightGlass.asStateFlow()
    fun setLightGlass(v: Boolean) { sp.edit().putBoolean("light_glass", v).apply(); _lightGlass.value = v }

    // Save-to-folder
    private val _exportAsk = flow(sp.getBoolean("export_ask", true))
    val exportAsk: StateFlow<Boolean> = _exportAsk.asStateFlow()
    fun setExportAsk(v: Boolean) { sp.edit().putBoolean("export_ask", v).apply(); _exportAsk.value = v }
    private val _exportDir = flow(sp.getString("export_dir", null))
    val exportDir: StateFlow<String?> = _exportDir.asStateFlow()
    fun setExportDir(v: String?) { sp.edit().putString("export_dir", v).apply(); _exportDir.value = v }

    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("grid", _grid.value).put("quality", _quality.value.name).put("rg", _replayGain.value.name)
        .put("crossfade", _crossfade.value).put("cache_mb", _cacheMb.value).put("bit_perfect", _bitPerfect.value)
        .put("hires_float", _hiRes.value).put("scrobble", _scrobble.value).put("light_glass", _lightGlass.value)
        .put("autoplay", _autoplay.value).put("high_accuracy", _highAccuracy.value).put("lb_listens", _lbListens.value).put("player_style", _playerStyle.value.name).put("pinned_playlists", pinnedPlaylists).put("auto_update", _autoUpdate.value).put("ai_url", _aiUrl.value).put("ai_model", _aiModel.value)

    fun restore(o: org.json.JSONObject) {
        if (o.has("grid")) setGrid(o.optBoolean("grid", true))
        runCatching { StreamQuality.valueOf(o.getString("quality")) }.getOrNull()?.let(::setQuality)
        runCatching { ReplayGainMode.valueOf(o.getString("rg")) }.getOrNull()?.let(::setReplayGain)
        if (o.has("crossfade")) setCrossfade(o.optInt("crossfade", 0))
        if (o.has("cache_mb")) setCacheMb(o.optInt("cache_mb", 1024))
        if (o.has("bit_perfect")) setBitPerfect(o.optBoolean("bit_perfect", false))
        if (o.has("hires_float")) setHiResOutput(o.optBoolean("hires_float", true))
        if (o.has("scrobble")) setScrobble(o.optBoolean("scrobble", true))
        if (o.has("autoplay")) setAutoplay(o.optBoolean("autoplay", true))
        if (o.has("high_accuracy")) setHighAccuracy(o.optBoolean("high_accuracy", false))
        runCatching { PlayerStyle.valueOf(o.getString("player_style")) }.getOrNull()?.let(::setPlayerStyle)
        if (o.has("pinned_playlists")) pinnedPlaylists = o.optString("pinned_playlists")
        if (o.has("auto_update")) setAutoUpdate(o.optBoolean("auto_update", true))
        if (o.has("ai_url")) setAiUrl(o.optString("ai_url"))
        if (o.has("ai_model")) setAiModel(o.optString("ai_model"))
        if (o.has("lb_listens")) setListenBrainzListens(o.optBoolean("lb_listens", true))
        if (o.has("light_glass")) setLightGlass(o.optBoolean("light_glass", false))
    }
}
