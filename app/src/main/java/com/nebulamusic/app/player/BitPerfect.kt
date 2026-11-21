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
    /** The option is on and the device supports it, but Android refused every configuration we tried. */
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
    val supported: Boolean get() = state == BpState.ACTIVE || state == BpState.REFUSED || state == BpState.DISABLED
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

    /**
     * Applies (or clears) the preference for a track played at [sampleRate] Hz with [bitDepth] bits (0 = unknown).
     * [preferFloat] says the player outputs 32-bit float. Safe to call often and on any API level.
     */
    fun apply(ctx: Context, enabled: Boolean, sampleRate: Int, bitDepth: Int, preferFloat: Boolean) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val dev = usb(am)
        val name = (dev ?: outputs(am).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP })
            ?.productName?.toString()?.takeIf { it.isNotBlank() }
        if (dev == null) { _info.value = OutputInfo(name, usb = false, state = BpState.NO_DEVICE); return }
        if (Build.VERSION.SDK_INT < 34) { _info.value = OutputInfo(name, usb = true, state = BpState.OLD_ANDROID); return }
        try {
            val bp = am.getSupportedMixerAttributes(dev).filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            val formats = bp.map(::describe).distinct()
            if (bp.isEmpty()) { _info.value = OutputInfo(name, true, BpState.NO_BP_MODE); return }
            if (!enabled) {
                am.clearPreferredMixerAttributes(attrs, dev)
                _info.value = OutputInfo(name, true, BpState.DISABLED, formats = formats)
                return
            }
            // Best match first: same sample rate, then the bit depth the player will really send.
            val wantBits = if (preferFloat) 32 else if (bitDepth > 16) 24 else 16
            val candidates = bp.sortedWith(
                compareBy<AudioMixerAttributes>(
                    { kotlin.math.abs(it.format.sampleRate - sampleRate) },
                    { if (preferFloat && it.format.encoding == AudioFormat.ENCODING_PCM_FLOAT) 0 else 1 },
                    { kotlin.math.abs(bitsOf(it.format.encoding) - wantBits) },
                    { -Integer.bitCount(it.format.channelMask) },
                )
            )
            val desired = candidates.first()
            val current = am.getPreferredMixerAttributes(attrs, dev)
            // Idempotent: when the system already holds the right format there is nothing to ask again. Asking again while a
            // track is playing can be refused, which used to overwrite a perfectly good state.
            if (current != null && current.format.sampleRate == desired.format.sampleRate &&
                current.format.encoding == desired.format.encoding && current.format.channelMask == desired.format.channelMask
            ) {
                _info.value = OutputInfo(name, true, BpState.ACTIVE, describe(current), formats, system = describe(current))
                return
            }
            val attempts = ArrayList<String>()
            var chosen: AudioMixerAttributes? = null
            for (c in candidates.take(8)) {
                val ok = am.setPreferredMixerAttributes(attrs, dev, c)
                attempts += "${describe(c)} → ${if (ok) "OK" else "refused"}"
                if (ok) { chosen = c; break }
            }
            // Ask the system what it kept: the call can report success and still not stick.
            val kept = am.getPreferredMixerAttributes(attrs, dev)
            _info.value = if (chosen != null && kept != null) OutputInfo(name, true, BpState.ACTIVE, describe(kept), formats, attempts, describe(kept))
            else OutputInfo(name, true, BpState.REFUSED, formats = formats, attempts = attempts, system = kept?.let(::describe))
            android.util.Log.i("NebulaBitPerfect", "device=$name sdk=${Build.VERSION.SDK_INT} track=${sampleRate}Hz/${bitDepth}bit state=${_info.value.state} attempts=$attempts system=${_info.value.system}")
        } catch (e: Exception) {
            android.util.Log.w("NebulaBitPerfect", "mixer attributes failed", e)
            _info.value = OutputInfo(name, true, BpState.REFUSED)
        }
    }
}
