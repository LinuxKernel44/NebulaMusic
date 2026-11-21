package com.nebulamusic.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class ListenBrainzState(val user: String? = null) {
    val connected get() = user != null
}

class ListenBrainzException(message: String) : IOException(message)

/** A song suggested by ListenBrainz, not yet matched against the Navidrome library. [score] is 0..1 within its list. */
data class Suggestion(val artist: String, val title: String, val score: Double, val mbid: String? = null)

/** A recording identified on MusicBrainz, with the artist ids needed for "similar artists". */
data class Recording(val mbid: String, val artistMbids: List<String>)

/**
 * ListenBrainz client (free, open, no API key). The user pastes the personal token of their account
 * (https://listenbrainz.org/settings/): it is stored encrypted with the Android Keystore. Used to read the
 * account's taste, to fetch similar songs for the endless queue, and to submit listens.
 */
class ListenBrainz(private val http: OkHttpClient, ctx: Context) {
    private val sp = ctx.getSharedPreferences("nebula_listenbrainz", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<ListenBrainzState> = _state.asStateFlow()

    private fun read() = ListenBrainzState(sp.getString("user", null)?.takeIf { sp.getString("token", null) != null })
    private fun token() = sp.getString("token", null)?.let(Vault::decrypt).orEmpty()

    /** Checks [token] with the server and, if valid, stores it. Throws with a readable message otherwise. */
    suspend fun connect(token: String) {
        val t = token.trim()
        if (t.isEmpty()) throw ListenBrainzException("Paste your user token first")
        val o = request("https://api.listenbrainz.org/1/validate-token", t)
        if (!o.optBoolean("valid")) throw ListenBrainzException("This token is not valid")
        sp.edit().putString("token", Vault.encrypt(t)).putString("user", o.optString("user_name")).apply()
        taste = null
        _state.value = read()
    }

    fun disconnect() {
        sp.edit().remove("token").remove("user").apply(); taste = null; _state.value = read()
    }

    private fun request(url: String, token: String? = token(), body: String? = null): JSONObject {
        val b = Request.Builder().url(url.toHttpUrl())
        if (!token.isNullOrEmpty()) b.header("Authorization", "Token $token")
        if (body != null) b.post(body.toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { if (text.trimStart().startsWith("[")) JSONObject().put("_list", JSONArray(text)) else JSONObject(text) }.getOrNull()
                ?: throw ListenBrainzException("HTTP ${r.code}")
            if (!r.isSuccessful) throw ListenBrainzException(o.optString("error", o.optString("message", "HTTP ${r.code}")))
            return o
        }
    }

    // ---- Recommendations ---------------------------------------------------------------------------------

    private val cache = HashMap<String, Any>()

    /** MusicBrainz id of a song (needed by the similarity service) from its tags, when the library has no id. */
    suspend fun lookup(artist: String, title: String): Recording? = withContext(Dispatchers.IO) {
        val key = "l|${artist.fold()}|${title.fold()}"
        (synchronized(cache) { cache[key] } as? Recording)?.let { return@withContext it }
        val u = "https://api.listenbrainz.org/1/metadata/lookup/".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist).addQueryParameter("recording_name", title).build().toString()
        val o = runCatching { request(u) }.getOrNull() ?: return@withContext null
        val mbid = o.optString("recording_mbid").takeIf { it.isNotBlank() } ?: return@withContext null
        val ids = o.optJSONArray("artist_mbids")
        val rec = Recording(mbid, List(ids?.length() ?: 0) { ids!!.getString(it) })
        synchronized(cache) { cache[key] = rec }
        rec
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun labs(path: String, param: String, id: String): JSONArray = withContext(Dispatchers.IO) {
        val u = "https://labs.api.listenbrainz.org/$path/json".toHttpUrl().newBuilder()
            .addQueryParameter(param, id).addQueryParameter("algorithm", ALGORITHM).build().toString()
        request(u, token = null).optJSONArray("_list") ?: JSONArray()
    }

    /** Songs that people listen to in the same sessions as [recordingMbid], best first. */
    suspend fun similarRecordings(recordingMbid: String, limit: Int = 60): List<Suggestion> {
        val key = "r|$recordingMbid"
        @Suppress("UNCHECKED_CAST")
        (synchronized(cache) { cache[key] } as? List<Suggestion>)?.let { return it }
        val arr = labs("similar-recordings", "recording_mbids", recordingMbid)
        val raw = List(minOf(arr.length(), limit)) { arr.getJSONObject(it) }
        val max = raw.maxOfOrNull { it.optDouble("score", 0.0) }?.takeIf { it > 0 } ?: 1.0
        val out = raw.map {
            Suggestion(it.optString("artist_credit_name"), it.optString("recording_name"), it.optDouble("score", 0.0) / max, it.optString("recording_mbid"))
        }.filter { it.artist.isNotBlank() && it.title.isNotBlank() }
        synchronized(cache) { cache[key] = out }
        return out
    }

    /** Names of the artists closest to [artistMbid]. */
    suspend fun similarArtists(artistMbid: String, limit: Int = 15): List<String> {
        val arr = labs("similar-artists", "artist_mbids", artistMbid)
        return List(minOf(arr.length(), limit)) { arr.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }
    }

    class Taste(val artists: Map<String, Int>, val loved: Set<String>, val at: Long)
    private var taste: Taste? = null

    /** The account's most listened artists (rank) and loved songs; refreshed at most every 6 hours. */
    suspend fun taste(): Taste? = withContext(Dispatchers.IO) {
        val user = _state.value.user ?: return@withContext null
        taste?.takeIf { System.currentTimeMillis() - it.at < 6 * 3600_000L }?.let { return@withContext it }
        val top = runCatching {
            request("https://api.listenbrainz.org/1/stats/user/$user/artists?range=all_time&count=150")
                .optJSONObject("payload")?.optJSONArray("artists")
        }.getOrNull()
        val lov = runCatching {
            request("https://api.listenbrainz.org/1/feedback/user/$user/get-feedback?score=1&count=300&metadata=true")
                .optJSONArray("feedback")
        }.getOrNull()
        if (top == null && lov == null) return@withContext null
        val artists = HashMap<String, Int>()
        if (top != null) for (i in 0 until top.length()) artists[top.getJSONObject(i).optString("artist_name").fold()] = i
        val loved = HashSet<String>()
        if (lov != null) for (i in 0 until lov.length()) {
            val m = lov.getJSONObject(i).optJSONObject("track_metadata") ?: continue
            loved += m.optString("artist_name").fold() + "|" + m.optString("track_name").fold()
        }
        Taste(artists, loved, System.currentTimeMillis()).also { taste = it }
    }

    // ---- Listening history -------------------------------------------------------------------------------

    /** Tells ListenBrainz what is playing right now (shown on the profile; not counted as a listen). */
    suspend fun playingNow(t: Track) = submit("playing_now", t, null)

    /** Records a finished listen that started at [startMs]. */
    suspend fun listen(t: Track, startMs: Long) = submit("single", t, startMs / 1000)

    private suspend fun submit(type: String, t: Track, listenedAt: Long?) = withContext(Dispatchers.IO) {
        if (!_state.value.connected) return@withContext
        val info = JSONObject().put("media_player", "NebulaMusic").put("submission_client", "NebulaMusic")
            .put("duration", t.durationSec)
        t.musicBrainzId?.let { info.put("recording_mbid", it) }
        val meta = JSONObject().put("artist_name", t.artist).put("track_name", t.title).put("additional_info", info)
        if (t.album.isNotBlank()) meta.put("release_name", t.album)
        val item = JSONObject().put("track_metadata", meta)
        if (listenedAt != null) item.put("listened_at", listenedAt)
        val body = JSONObject().put("listen_type", type).put("payload", JSONArray().put(item)).toString()
        runCatching { request("https://api.listenbrainz.org/1/submit-listens", body = body) }
    }

    companion object {
        const val ALGORITHM = "session_based_days_9000_session_300_contribution_5_threshold_15_limit_50_skip_30"
    }
}
