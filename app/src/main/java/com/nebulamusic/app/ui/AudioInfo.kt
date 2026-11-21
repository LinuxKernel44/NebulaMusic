package com.nebulamusic.app.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebulamusic.app.R
import com.nebulamusic.app.player.AudioInfo
import com.nebulamusic.app.player.BitPerfect
import com.nebulamusic.app.player.BpState

private fun khz(hz: Int) = if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000f)

private fun encodingName(e: Int) = when (e) {
    AudioFormat.ENCODING_PCM_16BIT -> "PCM 16 bit"
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24 bit"
    AudioFormat.ENCODING_PCM_32BIT -> "PCM 32 bit"
    AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
    else -> if (e == 0) "" else "encoding $e"
}

private fun codecName(mime: String?) = when (mime) {
    "audio/mpeg" -> "MP3"
    "audio/mp4a-latm" -> "AAC"
    "audio/flac" -> "FLAC"
    "audio/vorbis" -> "Vorbis"
    "audio/opus" -> "Opus"
    null -> ""
    else -> mime.removePrefix("audio/").uppercase()
}

private fun isHardware(decoder: String): Boolean {
    val info = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.name == decoder }
    return info != null && android.os.Build.VERSION.SDK_INT >= 29 && info.isHardwareAccelerated
}

/** Best guess of the device audio is routed to (the app has no direct access to the routing). */
private fun outputDevice(ctx: Context): Pair<String, String> {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    fun rank(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> 0
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 1
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> 2
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 3
        else -> 9
    }
    val d = outs.filter { rank(it.type) < 9 }.minByOrNull { rank(it.type) } ?: return "" to ""
    val kind = when (d.type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired"
        else -> "Speaker"
    }
    return (d.productName?.toString()?.takeIf { it.isNotBlank() } ?: kind) to kind
}

@Composable
private fun Section(title: String, vararg lines: String) {
    val shown = lines.filter { it.isNotBlank() }
    if (shown.isEmpty()) return
    Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        shown.forEach { Text(it, fontSize = 14.sp, color = Color.White.copy(alpha = 0.78f)) }
    }
}

@Composable
fun AudioInfoContent() {
    val ctx = LocalContext.current
    val a by AudioInfo.state.collectAsState()
    Text(
        stringResource(R.string.audio_info), fontWeight = FontWeight.Bold, fontSize = 18.sp,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp)
    )
    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
        val ch = when (a.channels) { 1 -> stringResource(R.string.ai_mono); 2 -> stringResource(R.string.ai_stereo); 0 -> ""; else -> "${a.channels} ch" }
        Section(
            stringResource(R.string.ai_track),
            listOf(codecName(a.mime), ch, if (a.bitrate > 0) "${a.bitrate / 1000} kbps" else "").filter { it.isNotBlank() }.joinToString(" · "),
            if (a.sampleRate > 0) khz(a.sampleRate) else ""
        )
        val dec = a.decoder
        Section(
            stringResource(R.string.ai_decoder),
            dec ?: "",
            if (dec == null) "" else stringResource(if (isHardware(dec)) R.string.ai_hardware else R.string.ai_software)
        )
        val resampled = a.sampleRate > 0 && a.outSampleRate > 0 && a.sampleRate != a.outSampleRate
        Section(
            stringResource(R.string.ai_dsp),
            if (a.outSampleRate > 0) stringResource(if (resampled) R.string.ai_resampled else R.string.ai_no_resampling, khz(a.sampleRate), khz(a.outSampleRate)) else "",
            stringResource(R.string.ai_fades)
        )
        Section(
            stringResource(R.string.ai_output),
            listOf(encodingName(a.outEncoding), if (a.outSampleRate > 0) khz(a.outSampleRate) else "").filter { it.isNotBlank() }.joinToString(" · "),
            if (a.outBufferBytes > 0 && a.outSampleRate > 0 && a.outChannelCount > 0) {
                val bytesPerSample = if (a.outEncoding == AudioFormat.ENCODING_PCM_FLOAT || a.outEncoding == AudioFormat.ENCODING_PCM_32BIT) 4 else 2
                stringResource(R.string.ai_buffer, a.outBufferBytes * 1000L / (a.outSampleRate * a.outChannelCount * bytesPerSample))
            } else "",
            listOfNotNull(if (a.offload) "offload" else null, if (a.tunneling) "tunneling" else null).joinToString(" · ")
        )
        val (dev, kind) = outputDevice(ctx)
        Section(stringResource(R.string.ai_device), dev, if (dev != kind) kind else "")
        val bp by BitPerfect.info.collectAsState()
        Section(
            stringResource(R.string.ai_bitperfect),
            stringResource(
                when (bp.state) {
                    BpState.ACTIVE -> R.string.ai_bp_active
                    BpState.OLD_ANDROID -> R.string.ai_bp_old
                    BpState.DISABLED -> R.string.ai_bp_available
                    BpState.NO_BP_MODE -> R.string.ai_bp_unsupported
                    BpState.REFUSED -> R.string.ai_bp_refused
                    BpState.NO_MATCH -> R.string.ai_bp_nomatch
                    BpState.NO_DEVICE -> R.string.ai_bp_nousb
                }
            ),
            bp.applied?.let { stringResource(R.string.ai_bp_format, it) } ?: "",
            if (bp.state == BpState.REFUSED && bp.system != null) stringResource(R.string.ai_bp_system, bp.system!!) else "",
            if ((bp.state == BpState.REFUSED || bp.state == BpState.NO_MATCH) && bp.attempts.isNotEmpty()) stringResource(R.string.ai_bp_attempts, bp.attempts.joinToString("\n")) else "",
            if (bp.formats.isNotEmpty()) stringResource(R.string.ai_bp_formats, bp.formats.joinToString(" / ")) else "",
            if (bp.state == BpState.REFUSED || bp.state == BpState.NO_MATCH) "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" else ""
        )
        Spacer(Modifier.height(10.dp))
    }
}
