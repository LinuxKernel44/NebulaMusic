package com.nebulamusic.app.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.nebulamusic.app.MainActivity
import com.nebulamusic.app.NebulaApp
import com.nebulamusic.app.R
import com.nebulamusic.app.data.ReplayGainMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

const val CMD_STOP_AFTER = "nebula.stop_after"
const val CMD_TOGGLE_FAV = "nebula.toggle_fav"
const val CMD_SETTINGS = "nebula.settings"

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var cache: SimpleCache? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var starredIds: Set<String> = emptySet()
    private val gain = GainAudioProcessor()

    // Scrobbling: a play counts after half the track or 4 minutes, whichever comes first.
    private var scrobbleId: String? = null
    private var scrobbleStart = 0L
    private var scrobbleDurationMs = 0L
    private var playedMs = 0L

    private fun likeLayout(player: Player): ImmutableList<CommandButton> {
        val id = player.currentMediaItem?.mediaId ?: return ImmutableList.of()
        val fav = id in starredIds
        return ImmutableList.of(
            CommandButton.Builder()
                .setDisplayName(getString(if (fav) R.string.rm_fav else R.string.add_fav))
                .setIconResId(if (fav) R.drawable.ic_heart else R.drawable.ic_heart_border)
                .setSessionCommand(SessionCommand(CMD_TOGGLE_FAV, Bundle.EMPTY))
                .build()
        )
    }

    private fun applyReplayGain(item: MediaItem?) {
        val c = (application as NebulaApp).container
        val mode = c.prefs.replayGain.value
        gain.enabled = mode != ReplayGainMode.OFF
        val rg = item?.track()?.replayGain
        val db = when (mode) {
            ReplayGainMode.OFF -> 0f
            ReplayGainMode.TRACK -> rg?.trackGain ?: rg?.albumGain
            ReplayGainMode.ALBUM -> rg?.albumGain ?: rg?.trackGain
        } ?: rg?.fallbackGain ?: 0f
        // Never push a track into clipping: cap the gain by its peak when known.
        val peak = when (mode) { ReplayGainMode.ALBUM -> rg?.albumPeak ?: rg?.trackPeak; else -> rg?.trackPeak ?: rg?.albumPeak }
        val maxDb = if (peak != null && peak > 0f) (-20f * Math.log10(peak.toDouble()).toFloat()) else 0f
        gain.setGainDb(if (db > maxDb) maxDb else db)
    }

    private fun applyOutput(item: MediaItem?) {
        val c = (application as NebulaApp).container
        BitPerfect.apply(this, c.prefs.bitPerfect.value, item?.track()?.samplingRate ?: 0)
    }

    private fun flushScrobble(c: com.nebulamusic.app.data.AppContainer) {
        val id = scrobbleId ?: return
        val threshold = minOf(scrobbleDurationMs / 2, 240_000L).coerceAtLeast(30_000L)
        val start = scrobbleStart
        val counted = playedMs >= threshold
        scrobbleId = null
        if (!counted || !c.prefs.scrobble.value) return
        val s = c.session.value ?: return
        scope.launch(Dispatchers.IO) { runCatching { s.scrobble(id, true, start) } }
    }

    private fun startScrobble(c: com.nebulamusic.app.data.AppContainer, item: MediaItem?, durationMs: Long) {
        flushScrobble(c)
        val id = item?.mediaId ?: return
        scrobbleId = id
        scrobbleStart = System.currentTimeMillis()
        scrobbleDurationMs = item.track()?.durationSec?.times(1000L)?.takeIf { it > 0 } ?: durationMs
        playedMs = 0
        if (c.prefs.scrobble.value) c.session.value?.let { s -> scope.launch(Dispatchers.IO) { runCatching { s.scrobble(id, false) } } }
    }

    override fun onCreate() {
        super.onCreate()
        val container = (application as NebulaApp).container
        val simpleCache = SimpleCache(
            File(cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(container.prefs.cacheMb.value * 1024L * 1024L),
            StandaloneDatabaseProvider(this)
        ).also { cache = it }

        val upstream = OkHttpDataSource.Factory(container.http)
        val cached = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // Audio sink: gain (ReplayGain) then a sample-accurate fade-in after every flush (anti-click).
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(container.prefs.hiResOutput.value)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(gain, FadeInAudioProcessor()))
                .build()
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val exo = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, cached)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exo.addAnalyticsListener(AudioInfo.listener)

        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider(this).also {
                it.setSmallIcon(R.drawable.ic_notification)
            }
        )

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, FadingPlayer(exo))
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_STOP_AFTER, Bundle.EMPTY))
                        .add(SessionCommand(CMD_SETTINGS, Bundle.EMPTY))
                        .add(SessionCommand(CMD_TOGGLE_FAV, Bundle.EMPTY)).build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(cmds)
                        .setCustomLayout(likeLayout(exo)).build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        CMD_STOP_AFTER -> exo.pauseAtEndOfMediaItems = args.getBoolean("on")
                        CMD_SETTINGS -> { applyReplayGain(exo.currentMediaItem); applyOutput(exo.currentMediaItem) }
                        CMD_TOGGLE_FAV -> exo.currentMediaItem?.mediaId?.let { container.toggleStar(it) }
                        else -> return super.onCustomCommand(session, controller, customCommand, args)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()

        // Keep the heart, ReplayGain, output routing and scrobbling in sync with the current track.
        val refreshLike = { session?.setCustomLayout(likeLayout(exo)) ?: Unit }
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                refreshLike()
                applyReplayGain(mediaItem)
                applyOutput(mediaItem)
                startScrobble(container, mediaItem, exo.duration.coerceAtLeast(0))
            }

        })
        // Accumulates real listening time (pauses and seeks do not count).
        scope.launch {
            var last = android.os.SystemClock.elapsedRealtime()
            while (true) {
                kotlinx.coroutines.delay(1000)
                val now = android.os.SystemClock.elapsedRealtime()
                if (exo.isPlaying) playedMs += now - last
                last = now
            }
        }
        // Fade between songs: the end of a song fades out and the next one fades in (playback stays gapless otherwise).
        scope.launch {
            var shaping = false
            while (true) {
                kotlinx.coroutines.delay(80)
                val fadeMs = container.prefs.crossfade.value * 1000L
                if (fadeMs <= 0L || !exo.isPlaying) {
                    if (shaping && fadeMs <= 0L) { exo.volume = 1f; shaping = false }
                    continue
                }
                val dur = exo.duration
                val pos = exo.currentPosition
                if (dur <= 0L || dur < fadeMs * 3) continue
                val remaining = dur - pos
                val v = when {
                    remaining < fadeMs -> remaining.toFloat() / fadeMs
                    pos < fadeMs && exo.currentMediaItemIndex > 0 -> pos.toFloat() / fadeMs
                    else -> 1f
                }.coerceIn(0f, 1f)
                if (v < 1f) { exo.volume = v; shaping = true }
                else if (shaping) { exo.volume = 1f; shaping = false }
            }
        }
        scope.launch {
            container.starred.collect { ids ->
                starredIds = ids
                refreshLike()
            }
        }
        scope.launch { container.prefs.replayGain.collect { applyReplayGain(exo.currentMediaItem) } }
        scope.launch { container.prefs.bitPerfect.collect { applyOutput(exo.currentMediaItem) } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        flushScrobble((application as NebulaApp).container)
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        cache?.release()
        cache = null
        super.onDestroy()
    }
}
