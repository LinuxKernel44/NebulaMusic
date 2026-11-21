package com.nebulamusic.app.data

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

val TrackJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

class AppContainer(val ctx: Context) {
    val db: AppDb = AppDb.create(ctx)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "NebulaMusic-Android/${com.nebulamusic.app.BuildConfig.VERSION_NAME}").build())
        }
        .build()
    val prefs = Prefs(ctx)
    val servers = ServerStore(ctx)
    val listenBrainz by lazy { ListenBrainz(http, ctx) }
    val autoQueue by lazy { com.nebulamusic.app.player.AutoQueue(this) }
    val backup by lazy { Backup(this) }
    val exporter by lazy { Exporter(this) }
    val downloads = DownloadTracker()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Offline copies of the active server: id -> file path, and the full rows (for the Downloads list). */
    val downloadRows = MutableStateFlow<List<DownloadEntity>>(emptyList())
    val localPaths = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Ids of the starred (favorite) songs of the active server, kept in sync by the UI and the media notification. */
    val starred = MutableStateFlow<Set<String>>(emptySet())

    private val _session = MutableStateFlow<Session?>(null)
    /** The connection to the active server; null until the user has logged in. */
    val session: StateFlow<Session?> = _session.asStateFlow()

    init {
        reloadSession()
        appScope.launch {
            session.flatMapLatest { s -> if (s == null) flowOf(emptyList()) else db.downloads().observe(s.server.id) }
                .collect { rows ->
                    downloadRows.value = rows
                    localPaths.value = rows.filter { File(it.path).exists() }.associate { it.trackId to it.path }
                }
        }
    }

    /** Toggles the favorite state of a song on the server (optimistic: the local set flips first, and is rolled back on failure). */
    fun toggleStar(id: String) {
        val s = session.value ?: return
        val on = id !in starred.value
        starred.value = if (on) starred.value + id else starred.value - id
        appScope.launch {
            runCatching { withContext(Dispatchers.IO) { if (on) s.star(id) else s.unstar(id) } }.onFailure {
                starred.value = if (on) starred.value - id else starred.value + id
            }
        }
    }

    /** What to play for [t]: the offline file when we have it, else the stream URL for the chosen quality. Second = cache key. */
    fun resolveUri(t: Track): Pair<android.net.Uri, String>? {
        localPaths.value[t.id]?.let { return android.net.Uri.fromFile(File(it)) to "local:${t.id}" }
        val s = session.value ?: return null
        val q = prefs.quality.value
        return android.net.Uri.parse(s.streamUrl(t.id, q)) to "${s.server.id}:${t.id}:${q.name}"
    }

    /** Rebuilds [session] from the active server (call after adding / switching / removing servers). */
    fun reloadSession() {
        val s = servers.active
        _session.value = s?.let { Session(it, servers.password(it), http) }
    }

    val player: com.nebulamusic.app.player.PlayerController by lazy {
        com.nebulamusic.app.player.PlayerController(ctx, appScope, ::resolveUri, session)
    }

    val repo = Repo(this)
}

class Repo(private val c: AppContainer) {
    fun downloadsDir() = File(c.ctx.filesDir, "downloads").apply { mkdirs() }

    // ---- Waveform -------------------------------------------------------------------------------

    private val waveCache = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 10
    }

    private fun waveFile(t: Track) = File(File(c.ctx.cacheDir, "wave").apply { mkdirs() }, "${t.id.filter { it.isLetterOrDigit() }}.bin")

    /** Peak amplitude (0..1) for every [WAVE_BIN_MS] ms. Local file when downloaded, else a tiny transcode of the stream. */
    suspend fun waveform(t: Track, localPath: String?): FloatArray? {
        synchronized(waveCache) { waveCache[t.id] }?.let { return it }
        val bins = withContext(Dispatchers.IO) {
            readWave(waveFile(t)) ?: run {
                val local = localPath?.takeIf { File(it).exists() }
                val bins = if (local != null) decodeWaveform(local, true) else fetchAndDecode(t)
                bins?.also { writeWave(waveFile(t), it) }
            }
        }
        if (bins != null) synchronized(waveCache) { waveCache[t.id] = bins }
        return bins
    }

    /** Downloads the tiny transcode first (about a second), then decodes it locally: far faster than decoding over HTTP. */
    private fun fetchAndDecode(t: Track): FloatArray? {
        val url = c.session.value?.waveUrl(t.id) ?: return null
        val tmp = File(File(c.ctx.cacheDir, "wave").apply { mkdirs() }, "tmp-${t.id.filter { it.isLetterOrDigit() }}.mp3")
        return try {
            c.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                tmp.outputStream().use { out -> r.body?.byteStream()?.copyTo(out) ?: return null }
            }
            decodeWaveform(tmp.absolutePath, true)
        } catch (e: Exception) { null } finally { tmp.delete() }
    }

    private fun readWave(f: File): FloatArray? = runCatching {
        if (!f.exists()) return null
        val b = java.nio.ByteBuffer.wrap(f.readBytes())
        FloatArray(b.remaining() / 4) { b.getFloat() }
    }.getOrNull()

    private fun writeWave(f: File, bins: FloatArray) = runCatching {
        val b = java.nio.ByteBuffer.allocate(bins.size * 4)
        bins.forEach { b.putFloat(it) }
        f.writeBytes(b.array())
    }

    private fun normalize(bins: FloatArray): FloatArray {
        if (bins.isEmpty()) return bins
        val sorted = bins.sortedArray()
        val hi = sorted[(sorted.size * 0.97f).toInt().coerceAtMost(sorted.size - 1)]
        val lo = sorted[(sorted.size * 0.15f).toInt().coerceAtMost(sorted.size - 1)]
        val range = (hi - lo).coerceAtLeast(0.02f)
        for (i in bins.indices) bins[i] = ((bins[i] - lo) / range).coerceIn(0f, 1f)
        return bins
    }

    private fun decodeWaveform(source: String, isFile: Boolean): FloatArray? {
        val ex = android.media.MediaExtractor()
        var codec: android.media.MediaCodec? = null
        try {
            if (isFile) ex.setDataSource(source) else ex.setDataSource(source, emptyMap())
            val ti = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            codec = android.media.MediaCodec.createDecoderByType(fmt.getString(android.media.MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var rate = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            var bins = FloatArray(512)
            var maxBin = -1
            var frames = 0L
            val info = android.media.MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val out = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                    val total = info.size / 2
                    var k = 0
                    while (k + ch <= total) {
                        var peak = 0
                        for (j in 0 until ch) peak = maxOf(peak, kotlin.math.abs(out.get(info.offset / 2 + k + j).toInt()))
                        val bi = (frames * 1000L / WAVE_BIN_MS / rate).toInt()
                        if (bi >= bins.size) bins = bins.copyOf(bi * 2)
                        val v = peak / 32768f
                        if (v > bins[bi]) bins[bi] = v
                        if (bi > maxBin) maxBin = bi
                        frames++
                        k += ch
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    rate = nf.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                    ch = nf.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            if (maxBin < 1) return null
            return normalize(bins.copyOf(maxBin + 1))
        } catch (e: Exception) {
            android.util.Log.w("NebulaRepo", "waveform failed", e)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }

    companion object {
        const val WAVE_BIN_MS = 1000
    }
}

// ---- Downloads ---------------------------------------------------------------------------------

class DownloadTracker {
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    fun set(id: String, p: Float?) {
        _progress.value = if (p == null) _progress.value - id else _progress.value + (id to p)
    }
}

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val trackJson = inputData.getString("track") ?: return Result.failure()
        val track = runCatching { TrackJson.decodeFromString<Track>(trackJson) }.getOrNull() ?: return Result.failure()
        val c = (applicationContext as com.nebulamusic.app.NebulaApp).container
        val session = c.session.value ?: return Result.failure()
        val ext = track.suffix?.takeIf { it.isNotBlank() } ?: "audio"
        val dir = c.repo.downloadsDir()
        val safeId = track.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val target = File(dir, "$safeId.$ext")
        val part = File(dir, "$safeId.part")
        return withContext(Dispatchers.IO) {
            try {
                c.downloads.set(track.id, 0f)
                val req = Request.Builder().url(session.downloadUrl(track.id)).build()
                c.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("empty")
                    val total = body.contentLength()
                    var done = 0L
                    var lastPct = -1
                    part.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) { lastPct = pct; c.downloads.set(track.id, pct / 100f) }
                                }
                            }
                        }
                    }
                }
                if (!part.renameTo(target)) throw IOException("rename failed")
                c.db.downloads().put(
                    DownloadEntity(
                        track.id, session.server.id, target.absolutePath, target.length(), System.currentTimeMillis(),
                        track.title, track.artist, track.album, TrackJson.encodeToString(track),
                    )
                )
                c.downloads.set(track.id, null)
                Result.success()
            } catch (e: Exception) {
                android.util.Log.w("NebulaDownload", "download ${track.id} failed (attempt $runAttemptCount)", e)
                part.delete()
                c.downloads.set(track.id, null)
                if (runAttemptCount < 2) Result.retry() else Result.failure()
            }
        }
    }

    companion object {
        fun enqueue(ctx: Context, track: Track) {
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("track" to TrackJson.encodeToString(track)))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("dl-${track.id}", ExistingWorkPolicy.KEEP, req)
        }
    }
}
