package com.nebulamusic.app.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the user can be told about the output path: which device, and whether Android's bit-perfect mixer is active. */
data class OutputInfo(val device: String? = null, val usb: Boolean = false, val bitPerfect: Boolean = false, val supported: Boolean = false)

/**
 * Android 14+ can route a USB DAC through a "bit-perfect" mixer: the app's PCM reaches the DAC untouched, at the
 * track's native sample rate. This asks for that mode when a USB audio device is connected and picks the supported
 * mixer attributes closest to the track.
 */
object BitPerfect {
    private val _info = MutableStateFlow(OutputInfo())
    val info: StateFlow<OutputInfo> = _info.asStateFlow()

    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    private fun outputs(am: AudioManager) = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

    private fun usb(am: AudioManager): AudioDeviceInfo? = outputs(am).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    /** Applies (or clears) the preference for a track played at [sampleRate] Hz. Safe to call often and on any API level. */
    fun apply(ctx: Context, enabled: Boolean, sampleRate: Int) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val dev = usb(am)
        val name = (dev ?: outputs(am).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP })
            ?.productName?.toString()?.takeIf { it.isNotBlank() }
        if (dev == null || Build.VERSION.SDK_INT < 34) {
            _info.value = OutputInfo(name, usb = dev != null, bitPerfect = false, supported = false)
            return
        }
        try {
            val bp = am.getSupportedMixerAttributes(dev)
                .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            if (bp.isEmpty()) { _info.value = OutputInfo(name, true, false, false); return }
            if (!enabled) {
                am.clearPreferredMixerAttributes(attrs, dev)
                _info.value = OutputInfo(name, true, false, true)
                return
            }
            val pick = bp.firstOrNull { it.format.sampleRate == sampleRate } ?: bp.maxByOrNull { it.format.sampleRate } ?: return
            val ok = am.setPreferredMixerAttributes(attrs, dev, pick)
            _info.value = OutputInfo(name, true, ok, true)
        } catch (e: Exception) {
            android.util.Log.w("NebulaBitPerfect", "mixer attributes failed", e)
            _info.value = OutputInfo(name, true, false, true)
        }
    }
}
