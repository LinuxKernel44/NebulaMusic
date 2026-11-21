package com.nebulamusic.app.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.nebulamusic.app.data.Session
import com.nebulamusic.app.data.Track
import com.nebulamusic.app.data.TrackJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

enum class PlayMode { CONTINUE, REPEAT_ALL, REPEAT_ONE, STOP_AFTER }

private const val EXTRA_TRACK = "nebula.track"

/** The [Track] carried by a media item (null for items that were not created by this app). */
fun MediaItem.track(): Track? =
    mediaMetadata.extras?.getString(EXTRA_TRACK)?.let { runCatching { TrackJson.decodeFromString<Track>(it) }.getOrNull() }

data class PlayerUi(
    val connected: Boolean = false,
    val track: Track? = null,
    val isPlaying: Boolean = false,
    /** User-facing "playing" intent: stays true while buffering between tracks, so the UI does not flicker. */
    val active: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val mode: PlayMode = PlayMode.CONTINUE,
    val sampleRate: Int = 0,
    val bitrate: Int = 0,
    val queue: List<Track> = emptyList(),
    val index: Int = 0,
) {
    val trackId: String? get() = track?.id
}

class PlayerController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val uriFor: (Track) -> Pair<Uri, String>?,
    private val session: StateFlow<Session?>,
) {
    private var controller: MediaController? = null
    private var stopAfter = false

    private val _state = MutableStateFlow(PlayerUi())
    val state: StateFlow<PlayerUi> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val pending = ArrayList<(MediaController) -> Unit>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
        override fun onTracksChanged(tracks: Tracks) = refresh()
    }

    init {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.volume = 1f // never stay muted if the app died mid-scrub
            c.addListener(listener)
            refresh()
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(ctx))

        scope.launch {
            while (true) {
                delay(if (controller?.isPlaying == true) 250 else 1000)
                val c = controller
                if (c != null) _position.value = c.currentPosition.coerceAtLeast(0)
            }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending += block
    }

    /** Current position, read directly for smooth per-frame animation (main thread only). */
    fun positionNow(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: 0L

    private fun refresh() {
        val c = controller ?: return
        var sr = 0
        var br = 0
        for (g in c.currentTracks.groups) {
            if (g.type == C.TRACK_TYPE_AUDIO && g.isSelected) {
                for (i in 0 until g.length) if (g.isTrackSelected(i)) {
                    val f = g.getTrackFormat(i)
                    sr = f.sampleRate.takeIf { it > 0 } ?: 0
                    br = (f.averageBitrate.takeIf { it > 0 } ?: f.bitrate.takeIf { it > 0 } ?: 0)
                }
            }
        }
        val items = List(c.mediaItemCount) { c.getMediaItemAt(it) }
        val prev = _state.value
        // Decoding the queue JSON is not free: only redo it when the item list actually changed.
        val queue = if (prev.queue.size == items.size && prev.queue.indices.all { prev.queue[it].id == items[it].mediaId }) prev.queue
        else items.mapNotNull { it.track() }
        val mode = when {
            c.repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.REPEAT_ONE
            c.repeatMode == Player.REPEAT_MODE_ALL -> PlayMode.REPEAT_ALL
            stopAfter -> PlayMode.STOP_AFTER
            else -> PlayMode.CONTINUE
        }
        _state.value = PlayerUi(
            connected = true,
            track = c.currentMediaItem?.let { cur -> queue.firstOrNull { it.id == cur.mediaId } ?: cur.track() },
            isPlaying = c.isPlaying,
            active = c.playWhenReady && c.playbackState != Player.STATE_IDLE && c.playbackState != Player.STATE_ENDED,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
            shuffle = c.shuffleModeEnabled,
            mode = mode,
            sampleRate = sr,
            bitrate = br,
            queue = queue,
            index = c.currentMediaItemIndex,
        )
        _position.value = c.currentPosition.coerceAtLeast(0)
    }

    private fun item(t: Track): MediaItem? {
        val (uri, cacheKey) = uriFor(t) ?: return null
        val extras = Bundle().apply { putString(EXTRA_TRACK, TrackJson.encodeToString(t)) }
        return MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(uri)
            .setCustomCacheKey(cacheKey)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album)
                    .setArtworkUri(session.value?.coverUrl(t.coverArt, 600)?.let(Uri::parse))
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    fun playQueue(tracks: List<Track>, start: Int) = withController { c ->
        val items = tracks.mapNotNull(::item)
        if (items.isEmpty()) return@withController
        c.setMediaItems(items, start.coerceIn(0, items.lastIndex), 0L)
        c.prepare()
        c.play()
    }

    /** Loads a queue without playing it (cold start): paused, at the beginning of the track. No-op if something is loaded. */
    fun restoreQueue(tracks: List<Track>, start: Int) = withController { c ->
        val items = tracks.mapNotNull(::item)
        if (items.isEmpty() || c.mediaItemCount > 0) return@withController
        c.setMediaItems(items, start.coerceIn(0, items.lastIndex), 0L)
        c.playWhenReady = false
        c.prepare()
    }

    fun playNext(tracks: List<Track>) = withController { c ->
        val items = tracks.mapNotNull(::item)
        if (items.isEmpty()) return@withController
        if (c.mediaItemCount == 0) { c.setMediaItems(items); c.prepare(); c.play() }
        else c.addMediaItems(c.currentMediaItemIndex + 1, items)
    }

    fun addToQueue(tracks: List<Track>) = withController { c ->
        val items = tracks.mapNotNull(::item)
        if (items.isEmpty()) return@withController
        if (c.mediaItemCount == 0) { c.setMediaItems(items); c.prepare(); c.play() }
        else c.addMediaItems(items)
    }

    fun removeFromQueue(index: Int) = withController { it.removeMediaItem(index) }
    fun moveInQueue(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }
    fun skipTo(index: Int) = withController { it.seekToDefaultPosition(index); it.play() }

    fun togglePlay() = withController { if (it.playWhenReady && it.playbackState != Player.STATE_IDLE && it.playbackState != Player.STATE_ENDED) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    /** Called when the user skips a track in its first moments (a negative signal for the automatic queue). */
    var onSkip: ((Track) -> Unit)? = null

    fun next() = withController {
        val t = _state.value.track
        val dur = it.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 }
        if (t != null && dur != null && it.currentPosition < dur * 0.3 && it.currentPosition < 45_000) onSkip?.invoke(t)
        it.seekToNextMediaItem()
    }
    fun previous() = withController { if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPreviousMediaItem() }
    private var rampJob: Job? = null
    private fun ramp(to: Float, ms: Long, after: Long = 0L) {
        rampJob?.cancel()
        rampJob = scope.launch {
            if (after > 0) delay(after)
            val c = controller ?: return@launch
            val from = c.volume
            val steps = (ms / 10).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                c.volume = from + (to - from) * i / steps
                delay(10)
            }
            c.volume = to
        }
    }
    /** Finger down on the seek cursor: fade out quickly so no click is heard. */
    fun scrubStart() = ramp(0f, 60)
    /** Finger up: jump to the cursor position (while silent) then fade back in. */
    fun scrubEnd(ms: Long) {
        rampJob?.cancel()
        withController { it.volume = 0f }
        seekTo(ms)
        ramp(1f, 120, after = 90)
    }
    fun seekTo(ms: Long) = withController { it.seekTo(ms.coerceAtLeast(0)) }
    fun seekBy(deltaMs: Long) = withController {
        val dur = it.duration.takeIf { d -> d != C.TIME_UNSET } ?: Long.MAX_VALUE
        it.seekTo((it.currentPosition + deltaMs).coerceIn(0, dur))
    }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleMode(): PlayMode {
        val next = when (_state.value.mode) {
            PlayMode.CONTINUE -> PlayMode.REPEAT_ALL
            PlayMode.REPEAT_ALL -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.STOP_AFTER
            PlayMode.STOP_AFTER -> PlayMode.CONTINUE
        }
        withController { c ->
            stopAfter = next == PlayMode.STOP_AFTER
            c.repeatMode = when (next) {
                PlayMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
                PlayMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            c.sendCustomCommand(SessionCommand(CMD_STOP_AFTER, Bundle.EMPTY), Bundle().apply { putBoolean("on", stopAfter) })
            refresh()
        }
        return next
    }

    /** Tells the service that a playback setting (ReplayGain, bit-perfect...) changed. */
    fun settingsChanged() = withController { it.sendCustomCommand(SessionCommand(CMD_SETTINGS, Bundle.EMPTY), Bundle.EMPTY) }

    fun stop() = withController { it.stop(); it.clearMediaItems() }
}
