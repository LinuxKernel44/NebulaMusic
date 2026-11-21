package com.nebulamusic.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/** What the user configured for Last.fm. The secret and the session key are encrypted with the Android Keystore. */
data class LastFmState(
    val apiKey: String = "",
    val hasSecret: Boolean = false,
    /** Token waiting for the user to approve the app on last.fm (between "Connect" and "Finish"). */
    val pendingToken: String? = null,
    val user: String? = null,
) {
    val connected get() = user != null
    val configured get() = apiKey.isNotBlank() && hasSecret
}

class LastFmException(message: String) : IOException(message)

/** A track suggested by Last.fm, not yet matched against the Navidrome library. */
data class LfmTrack(val artist: String, val title: String, val score: Double)

/**
 * Minimal Last.fm web-service client: the user supplies their own API key + shared secret and signs in with the
 * regular web authorisation flow. Nothing is ever scrobbled for now; the account is only read.
 */
class LastFm(private val http: OkHttpClient, ctx: Context) {
    private val sp = ctx.getSharedPreferences("nebula_lastfm", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<LastFmState> = _state.asStateFlow()

    private fun read() = LastFmState(
        apiKey = sp.getString("key", "").orEmpty(),
        hasSecret = !sp.getString("secret", null).isNullOrEmpty(),
        pendingToken = sp.getString("token", null),
        user = sp.getString("user", null)?.takeIf { sp.getString("sk", null) != null },
    )

    private fun secret() = sp.getString("secret", null)?.let(Vault::decrypt).orEmpty()

    fun saveCredentials(key: String, secret: String?) {
        val e = sp.edit().putString("key", key.trim())
        if (!secret.isNullOrBlank()) e.putString("secret", Vault.encrypt(secret.trim()))
        e.apply(); _state.value = read()
    }

    fun disconnect() {
        sp.edit().remove("sk").remove("user").remove("token").apply(); _state.value = read()
    }

    // ---- Auth (web flow) -------------------------------------------------------------------------------

    /** Step 1: returns the page the user has to open in a browser to approve the app. */
    suspend fun beginAuth(): String {
        val token = get("auth.getToken", signed = true).getString("token")
        sp.edit().putString("token", token).apply(); _state.value = read()
        return "https://www.last.fm/api/auth/?api_key=${_state.value.apiKey}&token=$token"
    }

    /** Step 2: once approved in the browser, turns the token into a session. */
    suspend fun finishAuth() {
        val token = _state.value.pendingToken ?: throw LastFmException("Start the connection first")
        val s = get("auth.getSession", "token" to token, signed = true).getJSONObject("session")
        sp.edit().putString("sk", Vault.encrypt(s.getString("key"))).putString("user", s.getString("name")).remove("token").apply()
        _state.value = read()
    }

    // ---- Reading ---------------------------------------------------------------------------------------

    private fun sign(p: List<Pair<String, String>>): String {
        val raw = p.filter { it.first != "format" && it.first != "callback" }.sortedBy { it.first }
            .joinToString("") { it.first + it.second } + secret()
        return MessageDigest.getInstance("MD5").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private suspend fun get(method: String, vararg params: Pair<String, String>, signed: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            val st = _state.value
            if (!st.configured) throw LastFmException("Last.fm API key / secret missing")
            val all = buildList {
                add("method" to method); add("api_key" to st.apiKey); addAll(params)
            }
            val b = "https://ws.audioscrobbler.com/2.0/".toHttpUrl().newBuilder()
            for ((k, v) in all) b.addQueryParameter(k, v)
            if (signed) b.addQueryParameter("api_sig", sign(all))
            b.addQueryParameter("format", "json")
            http.newCall(Request.Builder().url(b.build()).build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                val o = runCatching { JSONObject(body) }.getOrNull() ?: throw LastFmException("HTTP ${r.code}")
                if (o.has("error")) throw LastFmException(o.optString("message", "Last.fm error ${o.optInt("error")}"))
                o
            }
        }

    private val cache = HashMap<String, List<LfmTrack>>()

    /** Tracks Last.fm considers close to [title] by [artist], best first (score = Last.fm "match", 0..1). */
    suspend fun similarTracks(artist: String, title: String, limit: Int = 60): List<LfmTrack> {
        val key = "t|${artist.fold()}|${title.fold()}"
        synchronized(cache) { cache[key] }?.let { return it }
        val arr = get("track.getSimilar", "artist" to artist, "track" to title, "limit" to limit.toString(), "autocorrect" to "1")
            .optJSONObject("similartracks")?.optJSONArray("track") ?: return emptyList()
        val out = List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            LfmTrack(o.optJSONObject("artist")?.optString("name").orEmpty(), o.optString("name"), o.optString("match").toDoubleOrNull() ?: 0.0)
        }.filter { it.artist.isNotBlank() && it.title.isNotBlank() }
        synchronized(cache) { cache[key] = out }
        return out
    }

    /** Artists close to [artist] (the track field of the result is empty). */
    suspend fun similarArtists(artist: String, limit: Int = 20): List<LfmTrack> {
        val key = "a|${artist.fold()}"
        synchronized(cache) { cache[key] }?.let { return it }
        val arr = get("artist.getSimilar", "artist" to artist, "limit" to limit.toString(), "autocorrect" to "1")
            .optJSONObject("similarartists")?.optJSONArray("artist") ?: return emptyList()
        val out = List(arr.length()) { i ->
            val o = arr.getJSONObject(i); LfmTrack(o.optString("name"), "", o.optString("match").toDoubleOrNull() ?: 0.0)
        }.filter { it.artist.isNotBlank() }
        synchronized(cache) { cache[key] = out }
        return out
    }

    private var taste: Taste? = null

    class Taste(val artists: Map<String, Int>, val loved: Set<String>, val at: Long)

    /** The signed-in user's favourite artists (rank by play count) and loved tracks; refreshed at most every 6 hours. */
    suspend fun taste(): Taste? {
        val user = _state.value.user ?: return null
        taste?.takeIf { System.currentTimeMillis() - it.at < 6 * 3600_000L }?.let { return it }
        val top = runCatching {
            get("user.getTopArtists", "user" to user, "period" to "overall", "limit" to "150").getJSONObject("topartists").getJSONArray("artist")
        }.getOrNull()
        val lov = runCatching {
            get("user.getLovedTracks", "user" to user, "limit" to "300").getJSONObject("lovedtracks").getJSONArray("track")
        }.getOrNull()
        if (top == null && lov == null) return null
        val artists = HashMap<String, Int>()
        if (top != null) for (i in 0 until top.length()) artists[top.getJSONObject(i).optString("name").fold()] = i
        val loved = HashSet<String>()
        if (lov != null) for (i in 0 until lov.length()) {
            val o = lov.getJSONObject(i)
            loved += o.optJSONObject("artist")?.optString("name").orEmpty().fold() + "|" + o.optString("name").fold()
        }
        return Taste(artists, loved, System.currentTimeMillis()).also { taste = it }
    }
}
