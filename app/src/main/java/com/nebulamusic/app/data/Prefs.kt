package com.nebulamusic.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReplayGainMode { OFF, TRACK, ALBUM }

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

    private val _highAccuracy = flow(sp.getBoolean("high_accuracy", false))
    /** Use the user's Last.fm account to choose the songs that are added automatically. */
    val highAccuracy: StateFlow<Boolean> = _highAccuracy.asStateFlow()
    fun setHighAccuracy(v: Boolean) { sp.edit().putBoolean("high_accuracy", v).apply(); _highAccuracy.value = v }

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
        .put("autoplay", _autoplay.value).put("high_accuracy", _highAccuracy.value)

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
        if (o.has("light_glass")) setLightGlass(o.optBoolean("light_glass", false))
    }
}
