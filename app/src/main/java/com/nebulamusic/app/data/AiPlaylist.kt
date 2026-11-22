package com.nebulamusic.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Result of an AI playlist run: the chosen tracks and a proposed name. */
class AiPlaylistResult(val name: String, val tracks: List<Track>)

/**
 * Builds a playlist from a free-text request with any OpenAI-compatible chat server (llama.cpp, Ollama, LM Studio…).
 * The model never sees the whole library: it first turns the request into a search plan, the app gathers matching
 * songs from the Navidrome library, then the model picks and orders the best ones among those real candidates.
 */
class AiPlaylist(http: OkHttpClient) {
    private val client = http.newBuilder().readTimeout(240, TimeUnit.SECONDS).callTimeout(300, TimeUnit.SECONDS).build()

    private suspend fun chat(base: String, model: String, system: String, user: String, maxTokens: Int): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("temperature", 0.4).put("max_tokens", maxTokens).put("stream", false)
                // Thinking models would spend the whole budget reasoning; the answer is a small JSON object.
                .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)))
            if (model.isNotBlank()) body.put("model", model)
            val req = Request.Builder().url(base.trimEnd('/') + "/chat/completions")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) error("HTTP ${r.code}")
                JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            }
        }

    /** The first JSON object found in a model answer (models like to wrap it in prose or code fences). */
    private fun jsonOf(text: String): JSONObject {
        val a = text.indexOf('{'); val b = text.lastIndexOf('}')
        require(a >= 0 && b > a) { "no JSON" }
        return JSONObject(text.substring(a, b + 1))
    }

    private fun strings(o: JSONObject, key: String, max: Int): List<String> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf(String::isNotEmpty) }.take(max)
    }

    suspend fun generate(
        baseUrl: String, model: String, request: String, count: Int, s: Session,
        stage: (Int) -> Unit = {},
    ): AiPlaylistResult {
        stage(1)
        val genres = runCatching { s.genres() }.getOrDefault(emptyList())
        val genreNames = genres.sortedByDescending { it.songCount }.take(80).map { it.name }
        val plan = jsonOf(
            chat(
                baseUrl, model,
                "You turn a request for a music playlist into a search plan for a personal music library. " +
                    "Reply with one JSON object only, no prose: " +
                    "{\"name\": short playlist title in the language of the request, \"artists\": up to 12 artist names that fit, " +
                    "\"genres\": up to 6 entries copied from the library genre list when possible, " +
                    "\"keywords\": up to 8 words that may appear in song titles, \"fromYear\": number or null, \"toYear\": number or null}.",
                "Request: $request\nLibrary genres: ${genreNames.joinToString(", ")}", 1500
            )
        )
        val name = plan.optString("name").trim().ifEmpty { request.take(40) }
        val fromYear = plan.optInt("fromYear", 0)
        val toYear = plan.optInt("toYear", 0)

        stage(2)
        val wantedArtists = strings(plan, "artists", 12)
        val wantedGenres = strings(plan, "genres", 6).mapNotNull { g ->
            genreNames.firstOrNull { it.fold() == g.fold() } ?: genreNames.firstOrNull { it.fold().contains(g.fold()) }
        }.distinct()
        val keywords = strings(plan, "keywords", 8)
        val found = coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<Track>>>()
            wantedArtists.forEach { a ->
                jobs += async {
                    runCatching { s.search(a, artists = 0, albums = 0, songs = 40).tracks }.getOrDefault(emptyList())
                        .filter { it.artist.fold().contains(a.fold()) }.shuffled().take(14)
                }
            }
            wantedGenres.forEach { g -> jobs += async { runCatching { s.songsByGenre(g, 0, 300).shuffled().take(45) }.getOrDefault(emptyList()) } }
            keywords.forEach { k -> jobs += async { runCatching { s.search(k, artists = 0, albums = 0, songs = 20).tracks }.getOrDefault(emptyList()) } }
            jobs.awaitAll().flatten()
        }
        var pool = found.distinctBy { it.id }
        if (fromYear > 0 || toYear > 0) {
            val inRange = pool.filter { t -> t.year == 0 || ((fromYear == 0 || t.year >= fromYear) && (toYear == 0 || t.year <= toYear)) }
            if (inRange.size >= count) pool = inRange
        }
        if (pool.size < count * 2) {
            pool = (pool + runCatching { s.randomSongs(120) }.getOrDefault(emptyList())).distinctBy { it.id }
        }
        pool = pool.shuffled().take(170)
        require(pool.isNotEmpty()) { "empty library" }

        stage(3)
        val listing = pool.mapIndexed { i, t ->
            "${i + 1}. ${t.title} - ${t.artist}" + (if (t.year > 0) " (${t.year})" else "") + (t.genre?.let { " [$it]" } ?: "")
        }.joinToString("\n")
        val picked = runCatching {
            val o = jsonOf(
                chat(
                    baseUrl, model,
                    "You are a music curator. From the numbered songs, pick the ones that best match the request and order them " +
                        "so the playlist flows well. Use at most 3 songs per artist. Reply with one JSON object only: {\"tracks\": [numbers]}.",
                    "Request: $request\nPick up to $count songs.\n$listing", 2500
                )
            )
            val arr = o.getJSONArray("tracks")
            (0 until arr.length()).mapNotNull { pool.getOrNull(arr.optInt(it) - 1) }.distinctBy { it.id }.take(count)
        }.getOrDefault(emptyList())
        return AiPlaylistResult(name, picked.ifEmpty { pool.take(count) })
    }
}
