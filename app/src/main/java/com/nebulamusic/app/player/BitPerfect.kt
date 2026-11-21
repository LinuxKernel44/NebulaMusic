package com.nebulamusic.app.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BpState {
    /** No USB audio device is connected. */
    NO_DEVICE,
    /** Android older than 14: the system has no bit-perfect mixer. */
    OLD_ANDROID,
    /** The USB device is there but offers no bit-perfect mixer mode. */
    NO_BP_MODE,
    /** The option is switched off in Settings. */
    DISABLED,
    /** Android accepted a bit-perfect mixer configuration for the device. */
    ACTIVE,
    /** The device offers bit-perfect, but not for this song's sample rate / channel layout (e.g. 88.2 kHz or surround). */
    NO_MATCH,
    /** The option is on and the device supports it, but Android refused the configuration. */
    REFUSED,
}

/** What the user can be told about the output path: which device, and what the bit-perfect mixer is doing. */
data class OutputInfo(
    val device: String? = null,
    val usb: Boolean = false,
    val state: BpState = BpState.NO_DEVICE,
    /** The mixer format that was accepted, e.g. "PCM 24-bit packed · 96 kHz · 2 ch". */
    val applied: String? = null,
    /** Every bit-perfect format the device lists. */
    val formats: List<String> = emptyList(),
    /** What Android reported for each configuration we tried ("... -> OK" / "... -> refused"). */
    val attempts: List<String> = emptyList(),
    /** What the system currently has as our preferred mixer format (may be older than the track). */
    val system: String? = null,
) {
    val bitPerfect: Boolean get() = state == BpState.ACTIVE
    val supported: Boolean get() = state == BpState.ACTIVE || state == BpState.REFUSED || state == BpState.DISABLED || state == BpState.NO_MATCH
}

/**
 * Android 14+ can route a USB DAC through a "bit-perfect" mixer: the app's PCM reaches the DAC untouched, at the
 * track's native sample rate. This asks for that mode when a USB audio device is connected, tries the device's
 * supported formats from the best match to the worst, and checks with the system that the choice was really kept.
 */
object BitPerfect {
    private val _info = MutableStateFlow(OutputInfo())
    val info: StateFlow<OutputInfo> = _info.asStateFlow()

    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    private fun outputs(am: AudioManager) = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

    private fun usb(am: AudioManager): AudioDeviceInfo? = outputs(am).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    private fun encodingName(e: Int) = when (e) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit packed"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
        AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
        else -> "encoding $e"
    }

    private fun bitsOf(e: Int) = when (e) {
        AudioFormat.ENCODING_PCM_16BIT -> 16
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
        else -> 8
    }

    private fun describe(a: AudioMixerAttributes): String {
        val f = a.format
        val ch = Integer.bitCount(f.channelMask)
        return "${encodingName(f.encoding)} · ${f.sampleRate / 1000f} kHz".replace(".0 kHz", " kHz") + if (ch > 0) " · $ch ch" else ""
    }

    /** Removes any preferred mixer format we may have left in the system (it persists, and a stale one breaks playback). */
    fun clear(ctx: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val dev = usb(am) ?: return
        runCatching { am.clearPreferredMixerAttributes(attrs, dev) }
    }

    /** Refreshes the "which device is connected" part of the status without touching the system configuration. */
    fun refreshDevice(ctx: Context, enabled: Boolean) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val dev = usb(am)
        val name = dev?.productName?.toString()?.takeIf { it.isNotBlank() }
        val cur = _info.value
        _info.value = when {
            dev == null -> OutputInfo(null, false, BpState.NO_DEVICE)
            Build.VERSION.SDK_INT < 34 -> OutputInfo(name, true, BpState.OLD_ANDROID)
            !enabled -> cur.copy(device = name, usb = true, state = BpState.DISABLED)
            else -> cur.copy(device = name, usb = true)
        }
    }

    /**
     * Called by the audio pipeline right when a new PCM format reaches the sink, BEFORE the AudioTrack exists.
     * Bit-perfect only works if the AudioTrack has exactly the format of the mixer configuration (encoding, channels, rate): a
     * mismatch makes the track die immediately (EPIPE, silence). So we pick a format the DAC lists for this sample rate, ask
     * Android for it, and return the integer PCM encoding the stream must be converted to. Null = leave the stream alone (and make
     * sure no preferred format stays set).
     */
    fun configure(ctx: Context, enabled: Boolean, sampleRate: Int, channelCount: Int, encoding: Int): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return false
        val dev = usb(am)
        val name = (dev ?: outputs(am).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP })
            ?.productName?.toString()?.takeIf { it.isNotBlank() }
        if (dev == null) { _info.value = OutputInfo(name, usb = false, state = BpState.NO_DEVICE); return false }
        if (Build.VERSION.SDK_INT < 34) { _info.value = OutputInfo(name, usb = true, state = BpState.OLD_ANDROID); return false }
        try {
            val bp = am.getSupportedMixerAttributes(dev).filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            val formats = bp.map(::describe).distinct()
            if (bp.isEmpty()) { am.clearPreferredMixerAttributes(attrs, dev); _info.value = OutputInfo(name, true, BpState.NO_BP_MODE); return false }
            if (!enabled) { am.clearPreferredMixerAttributes(attrs, dev); _info.value = OutputInfo(name, true, BpState.DISABLED, formats = formats); return false }
            if (channelCount != 2) {
                am.clearPreferredMixerAttributes(attrs, dev)
                _info.value = OutputInfo(name, true, BpState.NO_MATCH, formats = formats, attempts = listOf("$channelCount channels: stereo only"))
                return false
            }
            // Media3's DefaultAudioSink can only output 16-bit integer or float PCM. Float is on no DAC's list, so only a 16-bit
            // integer stream can match a bit-perfect mixer.
            if (encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
                am.clearPreferredMixerAttributes(attrs, dev)
                _info.value = OutputInfo(name, true, BpState.NO_MATCH, formats = formats, attempts = listOf("the stream is not 16-bit integer PCM"))
                return false
            }
            val usable = bp.filter {
                it.format.sampleRate == sampleRate && it.format.channelMask == AudioFormat.CHANNEL_OUT_STEREO &&
                    it.format.encoding == AudioFormat.ENCODING_PCM_16BIT
            }
            if (usable.isEmpty()) {
                am.clearPreferredMixerAttributes(attrs, dev)
                _info.value = OutputInfo(name, true, BpState.NO_MATCH, formats = formats, attempts = listOf("${sampleRate / 1000f} kHz stereo: not offered"))
                return false
            }
            val want = usable.first()
            val current = am.getPreferredMixerAttributes(attrs, dev)
            val same = current != null && current.format.sampleRate == want.format.sampleRate &&
                current.format.encoding == want.format.encoding && current.format.channelMask == want.format.channelMask
            var ok = same
            val attempts = ArrayList<String>()
            if (!same) {
                ok = am.setPreferredMixerAttributes(attrs, dev, want)
                attempts += "${describe(want)} → ${if (ok) "OK" else "refused"}"
            }
            val kept = am.getPreferredMixerAttributes(attrs, dev)
            if (!ok || kept == null) {
                am.clearPreferredMixerAttributes(attrs, dev)
                _info.value = OutputInfo(name, true, BpState.REFUSED, formats = formats, attempts = attempts, system = kept?.let(::describe))
                return false
            }
            _info.value = OutputInfo(name, true, BpState.ACTIVE, describe(kept), formats, attempts, describe(kept))
            android.util.Log.i("NebulaBitPerfect", "ACTIVE device=$name format=${describe(kept)}")
            return true
        } catch (e: Exception) {
            android.util.Log.w("NebulaBitPerfect", "bit-perfect setup failed", e)
            runCatching { am.clearPreferredMixerAttributes(attrs, dev) }
            _info.value = OutputInfo(name, true, BpState.REFUSED, attempts = listOf(e.javaClass.simpleName + ": " + (e.message ?: "")))
            return false
        }
    }
}
