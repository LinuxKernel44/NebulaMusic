package com.nebulamusic.app.player

import android.os.Handler
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.nio.ByteBuffer

private const val FADE_OUT_MS = 35L
private const val FADE_IN_MS = 60L

/**
 * Sample-accurate fade-in applied to the first few milliseconds of audio after every flush
 * (seek, track change, start). Removes the "click" caused by audio starting at a non-zero sample.
 */
@OptIn(UnstableApi::class)
class FadeInAudioProcessor(private val fadeMs: Int = 25) : BaseAudioProcessor() {
    private var fadeFrames = 0
    private var frame = 0
    private var channels = 1

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        fadeFrames = inputAudioFormat.sampleRate * fadeMs / 1000
        frame = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // The pipeline may hand us an empty buffer; copying it onto the shared empty output would throw.
        if (!inputBuffer.hasRemaining()) return
        val out = replaceOutputBuffer(inputBuffer.remaining())
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 4 * channels && frame < fadeFrames) {
                val gain = frame.toFloat() / fadeFrames
                repeat(channels) { out.putFloat(inputBuffer.float * gain) }
                frame++
            }
            out.put(inputBuffer)
        } else if (frame >= fadeFrames) {
            out.put(inputBuffer)
        } else {
            while (inputBuffer.remaining() >= 2 * channels && frame < fadeFrames) {
                val gain = frame.toFloat() / fadeFrames
                repeat(channels) { out.putShort((inputBuffer.short * gain).toInt().toShort()) }
                frame++
            }
            out.put(inputBuffer)
        }
        out.flip()
    }
}

/**
 * Last stage of the chain (only used by the 16-bit integer output path: Media3 does not run custom processors on the float path).
 * It never changes a sample; it only tells [configure] the exact format reaching the sink, before the AudioTrack is created, so the
 * bit-perfect mixer can be configured to match it (or released when it cannot).
 */
@OptIn(UnstableApi::class)
class BitPerfectGate(private val configure: (sampleRate: Int, channelCount: Int, encoding: Int) -> Unit) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, inputAudioFormat.encoding)
        return AudioProcessor.AudioFormat.NOT_SET // inactive: samples pass through untouched
    }

    override fun queueInput(inputBuffer: ByteBuffer) = Unit // never called while inactive
}

/**
 * Wraps the real player so every control path (app, notification, lock screen, headset buttons)
 * gets short volume ramps: fade-out before a seek / pause / track change while audio is playing,
 * and fade-in when playback starts or resumes.
 */
@OptIn(UnstableApi::class)
class FadingPlayer(private val exo: ExoPlayer) : ForwardingPlayer(exo) {
    private val handler = Handler(exo.applicationLooper)
    private val queue = ArrayDeque<() -> Unit>()
    private var fadingOut = false
    private var rampGen = 0
    private var rampActive = false
    private var pendingRestore = false

    /** ReplayGain attenuation (0..1): the level every ramp and every volume request is scaled by. */
    @Volatile var baseGain = 1f
        set(v) { field = v.coerceIn(0f, 1f); if (!rampActive && !fadingOut) exo.volume = field }

    override fun setVolume(volume: Float) { exo.volume = volume.coerceIn(0f, 1f) * baseGain }
    override fun getVolume(): Float = if (baseGain > 0f) (exo.volume / baseGain).coerceIn(0f, 1f) else exo.volume

    private val restore = Runnable { restoreVolume() }

    init {
        exo.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) = restoreVolume()
        })
    }

    private fun restoreVolume() {
        if (pendingRestore && !rampActive) {
            pendingRestore = false
            exo.volume = baseGain
        }
    }

    private fun ramp(to: Float, ms: Long, onDone: () -> Unit = {}) {
        val gen = ++rampGen
        val from = exo.volume
        val t0 = SystemClock.uptimeMillis()
        rampActive = true
        val step = object : Runnable {
            override fun run() {
                if (gen != rampGen) return
                val p = if (ms <= 0) 1f else ((SystemClock.uptimeMillis() - t0).toFloat() / ms).coerceIn(0f, 1f)
                exo.volume = from + (to - from) * p
                if (p >= 1f) {
                    rampActive = false
                    onDone()
                } else handler.postDelayed(this, 4)
            }
        }
        step.run()
    }

    /** Runs [action] after a short fade-out if audio is currently audible, otherwise immediately. */
    private fun guarded(action: () -> Unit) {
        if (fadingOut) { queue.addLast(action); return }
        if (!exo.isPlaying) { action(); return }
        fadingOut = true
        queue.addLast(action)
        ramp(0f, FADE_OUT_MS) {
            fadingOut = false
            val pending = queue.toList()
            queue.clear()
            pending.forEach { it() }
            pendingRestore = true
            handler.removeCallbacks(restore)
            handler.postDelayed(restore, 300)
        }
    }

    /**
     * Commands that change the playlist itself must reach the player synchronously: media3's MediaSession reports a
     * command as processed as soon as it returns, so deferring it by the fade-out let the controller receive the old
     * position together with the new (shorter) timeline -> IllegalStateException in PlayerInfo.copyWithTimeline.
     * Audio is muted instantly instead of faded; the new item fades in (FadeInAudioProcessor + startPlayback ramp).
     */
    private fun applyNow(action: () -> Unit) {
        if (fadingOut) {
            rampGen++; rampActive = false; fadingOut = false
            val pending = queue.toList()
            queue.clear()
            pending.forEach { it() }
        }
        if (exo.isPlaying) {
            rampGen++; rampActive = false
            exo.volume = 0f
            pendingRestore = true
            handler.removeCallbacks(restore)
            handler.postDelayed(restore, 300)
        }
        action()
    }

    private fun startPlayback() {
        handler.removeCallbacks(restore)
        if (exo.playWhenReady) {
            exo.play()
            if (exo.volume < baseGain && !rampActive) ramp(baseGain, FADE_IN_MS)
        } else {
            exo.volume = 0f
            exo.play()
            ramp(baseGain, FADE_IN_MS)
        }
    }

    // ---- Transport -----------------------------------------------------------------------------
    override fun play() {
        if (fadingOut) queue.addLast { startPlayback() } else startPlayback()
    }

    override fun pause() = guarded { exo.pause() }
    override fun stop() = guarded { exo.stop() }
    override fun prepare() { if (fadingOut) queue.addLast { exo.prepare() } else exo.prepare() }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    // ---- Seeking ---------------------------------------------------------------------------------
    override fun seekTo(positionMs: Long) = guarded { exo.seekTo(positionMs) }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = guarded { exo.seekTo(mediaItemIndex, positionMs) }
    override fun seekToDefaultPosition() = guarded { exo.seekToDefaultPosition() }
    override fun seekToDefaultPosition(mediaItemIndex: Int) = guarded { exo.seekToDefaultPosition(mediaItemIndex) }
    override fun seekBack() = guarded { exo.seekBack() }
    override fun seekForward() = guarded { exo.seekForward() }
    override fun seekToNext() = guarded { exo.seekToNext() }
    override fun seekToPrevious() = guarded { exo.seekToPrevious() }
    override fun seekToNextMediaItem() = guarded { exo.seekToNextMediaItem() }
    override fun seekToPreviousMediaItem() = guarded { exo.seekToPreviousMediaItem() }

    // ---- Changing what is playing ------------------------------------------------------------------
    override fun setMediaItem(mediaItem: MediaItem) = applyNow { exo.setMediaItem(mediaItem) }
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) =
        applyNow { exo.setMediaItem(mediaItem, startPositionMs) }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) =
        applyNow { exo.setMediaItem(mediaItem, resetPosition) }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) = applyNow { exo.setMediaItems(mediaItems) }
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) =
        applyNow { exo.setMediaItems(mediaItems, resetPosition) }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) =
        applyNow { exo.setMediaItems(mediaItems, startIndex, startPositionMs) }

    override fun clearMediaItems() = applyNow { exo.clearMediaItems() }

    override fun removeMediaItem(index: Int) {
        if (index == exo.currentMediaItemIndex) applyNow { exo.removeMediaItem(index) } else exo.removeMediaItem(index)
    }
}
