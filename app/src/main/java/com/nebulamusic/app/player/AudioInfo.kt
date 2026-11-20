package com.nebulamusic.app.player

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Snapshot of the audio pipeline of the track being played (source, decoder, output). */
data class AudioPipeline(
    val mime: String? = null,
    val channels: Int = 0,
    val sampleRate: Int = 0,
    val bitrate: Int = 0,
    val decoder: String? = null,
    val outEncoding: Int = 0,
    val outSampleRate: Int = 0,
    val outChannelCount: Int = 0,
    val outBufferBytes: Int = 0,
    val offload: Boolean = false,
    val tunneling: Boolean = false,
)

/** The service and the UI live in the same process, so a process-wide flow is enough. */
object AudioInfo {
    private val _state = MutableStateFlow(AudioPipeline())
    val state: StateFlow<AudioPipeline> = _state.asStateFlow()

    @OptIn(UnstableApi::class)
    val listener = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime, format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
        ) {
            _state.value = _state.value.copy(
                mime = format.sampleMimeType, channels = format.channelCount,
                sampleRate = format.sampleRate,
                bitrate = format.averageBitrate.takeIf { it > 0 } ?: format.bitrate.takeIf { it > 0 } ?: 0,
            )
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime, decoderName: String,
            initializedTimestampMs: Long, initializationDurationMs: Long
        ) {
            _state.value = _state.value.copy(decoder = decoderName)
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, c: AudioSink.AudioTrackConfig) {
            _state.value = _state.value.copy(
                outEncoding = c.encoding, outSampleRate = c.sampleRate,
                outChannelCount = Integer.bitCount(c.channelConfig),
                outBufferBytes = c.bufferSize, offload = c.offload, tunneling = c.tunneling,
            )
        }
    }
}
