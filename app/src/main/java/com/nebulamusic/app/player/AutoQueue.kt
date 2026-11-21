package com.nebulamusic.app.player

import com.nebulamusic.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the queue from ever running dry: when the last song (or the one before it) starts, a batch of songs that fit
 * what is playing is appended.
 *
 * Normal mode asks the server for similar songs, then falls back to the same artist / genre / era.
 * "High accuracy" additionally asks ListenBrainz (similar recordings), weighs the result with the user's own listening
 * taste, and keeps only what exists in the Navidrome library.
 */
class AutoQueue(private val c: AppContainer) {
    private val player get() = c.player
    private var busy = false
    private var retryAt = 0L
    private val history = ArrayDeque<String>()           // ids already played / queued this session
    private val skippedArtists = HashMap<String, Int>()  // artist -> times skipped early
    private var started = false

    fun start() {
        if (started) return
        started = true
        player.onSkip = { t -> skippedArtists.merge(t.artist.fold(), 1, Int::plus) }
        c.appScope.launch {
            player.state.map { s ->
                // A key that changes whenever the "queue is about to end" situation changes.
                if (s.connected && s.active && s.queue.isNotEmpty() && !s.shuffle && s.mode == PlayMode.CONTINUE &&
                    s.index >= s.queue.size - 2) s.queue.size to s.trackId else null
            }.distinctUntilChanged().collect { key -> if (key != null) maybeExtend() }
        }
    }

    private fun remember(ids: Collection<String>) {
        for (id in ids) if (id !in history) { history.addLast(id); if (history.size > 400) history.removeFirst() }
    }

    private fun maybeExtend() {
        if (!c.prefs.autoplay.value || busy || System.currentTimeMillis() < retryAt) return
        val s = player.state.value
        val session = c.session.value ?: return
        if (s.queue.isEmpty() || s.index < s.queue.size - 2) return
        busy = true
        remember(s.queue.map { it.id })
        c.appScope.launch {
            try {
                val batch = withContext(Dispatchers.IO) { pick(session, s.queue) }
                if (batch.isEmpty()) retryAt = System.currentTimeMillis() + 30_000
                else {
                    android.util.Log.i("AutoQueue", "seed=${s.queue.last().artist} - ${s.queue.last().title} hi=${c.prefs.highAccuracy.value} added: " + batch.joinToString { "${it.artist} - ${it.title}" })
                    remember(batch.map { it.id }); player.addToQueue(batch)
                }
            } catch (e: Exception) {
                retryAt = System.currentTimeMillis() + 30_000
            } finally { busy = false }
        }
    }

    private suspend fun pick(s: Session, queue: List<Track>): List<Track> {
        val seed = queue.last()
        val prev = queue.getOrNull(queue.size - 2)
        val exclude = HashSet<String>(history).apply { addAll(queue.map { it.id }) }
        val seenKeys = HashSet<String>().apply { queue.takeLast(30).forEach { add(dupKey(it)) } }
        val blocked = skippedArtists.filterValues { it >= 2 }.keys

        val ordered = ArrayList<Track>()

        // 1. High accuracy: ListenBrainz similarity filtered through the user's taste, resolved in the library.
        val lb = c.listenBrainz
        if (c.prefs.highAccuracy.value && lb.state.value.connected) {
            runCatching { ordered += viaListenBrainz(s, lb, seed, prev, exclude) }
        }
        // 2. Server-side similarity.
        if (ordered.size < 25) {
            ordered += s.similarSongs(seed.id, 40)
            if (prev != null && ordered.size < 25) ordered += s.similarSongs(prev.id, 25)
        }
        // 3. Same artist (other albums), same genre, same era: weaker but always available.
        if (ordered.size < 25) {
            runCatching {
                val albums = seed.artistId?.let { s.artist(it).second }.orEmpty().shuffled().take(3)
                for (al in albums) ordered += s.album(al.id).second.shuffled().take(4)
            }
            seed.genre?.takeIf { it.isNotBlank() }?.let { g -> ordered += runCatching { s.randomSongs(15, g) }.getOrDefault(emptyList()) }
            if (seed.year > 0) runCatching {
                val era = s.albums(AlbumSort.YEAR, 0, 30, fromYear = seed.year - 5, toYear = seed.year + 5).shuffled().take(5)
                for (al in era) ordered += s.album(al.id).second.shuffled().take(2)
            }
        }
        if (ordered.size < 12) ordered += runCatching { s.randomSongs(30) }.getOrDefault(emptyList())

        return diversify(ordered, exclude, seenKeys, blocked, seed, 10)
    }

    private fun dupKey(t: Track) = t.title.fold().replace(Regex("\\(.*?\\)|\\[.*?]"), "").trim() + "|" + t.artist.fold()

    /** Drops what was already played/queued, caps repeats of one artist and avoids two identical artists in a row. */
    private fun diversify(cands: List<Track>, exclude: Set<String>, seen: MutableSet<String>, blocked: Set<String>, seed: Track, n: Int): List<Track> {
        val out = ArrayList<Track>()
        val perArtist = HashMap<String, Int>()
        val pool = cands.filter { it.id !in exclude && it.artist.fold() !in blocked }
        for (t in pool) {
            if (out.size >= n) break
            val key = dupKey(t)
            val artist = t.artist.fold()
            if (!seen.add(key)) continue
            if ((perArtist[artist] ?: 0) >= (if (artist == seed.artist.fold()) 3 else 2)) continue
            if (artist == (out.lastOrNull()?.artist ?: seed.artist).fold() && (perArtist[artist] ?: 0) >= 1) continue
            perArtist.merge(artist, 1, Int::plus)
            out += t
        }
        return out
    }

    private suspend fun viaListenBrainz(s: Session, lb: ListenBrainz, seed: Track, prev: Track?, exclude: Set<String>): List<Track> {
        val scores = LinkedHashMap<String, Suggestion>()
        var artistMbid: String? = null
        suspend fun add(t: Track, weight: Double) {
            val rec = if (t.musicBrainzId != null) Recording(t.musicBrainzId, emptyList()) else lb.lookup(t.artist, t.title) ?: return
            if (t === seed) artistMbid = rec.artistMbids.firstOrNull()
            for (x in lb.similarRecordings(rec.mbid)) {
                val k = x.artist.fold() + "|" + x.title.fold()
                val old = scores[k]
                scores[k] = if (old == null) x.copy(score = x.score * weight) else old.copy(score = old.score + x.score * weight)
            }
        }
        add(seed, 1.0)
        if (prev != null) runCatching { add(prev, 0.5) }
        val taste = runCatching { lb.taste() }.getOrNull()
        val ranked = scores.values.map { x ->
            var sc = x.score
            if (taste != null) {
                val rank = taste.artists[x.artist.fold()]
                if (rank != null) sc += 0.25 * (1.0 - rank / 150.0)
                if ((x.artist.fold() + "|" + x.title.fold()) in taste.loved) sc += 0.3
            }
            x to sc
        }.sortedByDescending { it.second }.map { it.first }

        val out = ArrayList<Track>()
        // Resolve in small parallel chunks until enough matches exist (each lookup is one cheap search3 call).
        var i = 0
        while (i < ranked.size && i < 48 && out.size < 22) {
            val chunk = ranked.subList(i, minOf(i + 6, ranked.size)); i += 6
            out += coroutineScope { chunk.map { x -> async { findInLibrary(s, x) } }.awaitAll() }.filterNotNull().filter { it.id !in exclude }
        }
        val am = artistMbid
        if (out.size < 8 && am != null) {
            // Few exact hits: widen to artists ListenBrainz links to the current one.
            for (name in runCatching { lb.similarArtists(am) }.getOrDefault(emptyList())) {
                if (out.size >= 22) break
                val r = runCatching { s.search(name, 1, 0, 6).tracks }.getOrDefault(emptyList())
                out += r.filter { it.artist.fold() == name.fold() && it.id !in exclude }.take(2)
            }
        }
        return out
    }

    private suspend fun findInLibrary(s: Session, x: Suggestion): Track? {
        val r = runCatching { s.search("${x.artist} ${x.title}", 0, 0, 8).tracks }.getOrDefault(emptyList())
        val a = x.artist.fold(); val t = x.title.fold()
        return r.firstOrNull { it.title.fold() == t && (it.artist.fold() == a || a in it.artist.fold() || it.artist.fold() in a) }
    }
}
