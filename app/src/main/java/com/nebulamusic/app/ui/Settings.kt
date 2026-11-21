package com.nebulamusic.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebulamusic.app.R
import com.nebulamusic.app.data.*
import com.nebulamusic.app.player.BitPerfect
import com.nebulamusic.app.player.BpState
import com.nebulamusic.app.glass.GlassIconButton

@Composable
fun SettingsScreen(
    vm: MainViewModel, onBack: () -> Unit, onPickFolder: () -> Unit, onAddServer: () -> Unit,
    onEditServer: (ServerConfig) -> Unit, onExport: () -> Unit, onImport: () -> Unit,
) {
    val backdrop = glassBackdrop()
    val bottomInset = LocalBottomInset.current
    val prefs = vm.prefs
    val servers by vm.servers.collectAsState()
    val activeId by vm.activeServerId.collectAsState()
    val serverInfo by vm.serverInfo.collectAsState()
    val quality by prefs.quality.collectAsState()
    val rg by prefs.replayGain.collectAsState()
    val crossfade by prefs.crossfade.collectAsState()
    val cacheMb by prefs.cacheMb.collectAsState()
    val bitPerfect by prefs.bitPerfect.collectAsState()
    val hiRes by prefs.hiResOutput.collectAsState()
    val scrobble by prefs.scrobble.collectAsState()
    val lightGlass by prefs.lightGlass.collectAsState()
    val ask by vm.exportAsk.collectAsState()
    val dir by vm.exportDir.collectAsState()
    val out by BitPerfect.info.collectAsState()
    val dlBytes by vm.downloadBytes.collectAsState()
    var folderName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(dir) { folderName = dir?.let { vm.folderName(it) } }
    var headerH by remember { mutableStateOf(80.dp) }
    var confirmDownloads by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize()) {
        ScreenBackdrop {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberKeptScrollState("settings")).padding(top = headerH, bottom = bottomInset + 16.dp)
            ) {
                SectionTitle(stringResource(R.string.set_servers))
                for (s in servers) {
                    val active = s.id == activeId
                    Row(
                        Modifier.fillMaxWidth().clickable { if (!active) vm.switchServer(s.id) }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (active) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked, null, tint = if (active) Accent else Muted)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                            Text(
                                listOfNotNull("${s.username}@${s.url.removePrefix("https://").removePrefix("http://")}", if (active) serverInfo.takeIf { it.isNotBlank() } else null).joinToString(" · "),
                                color = Muted, fontSize = 13.sp, maxLines = 1
                            )
                        }
                        Icon(Icons.Rounded.Edit, stringResource(R.string.rename), tint = Muted, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onEditServer(s) }.padding(8.dp))
                        Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), tint = Muted, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { vm.removeServer(s.id) }.padding(8.dp))
                    }
                }
                ActionRow(Icons.Rounded.Add, stringResource(R.string.set_add_server), onAddServer)

                SectionTitle(stringResource(R.string.set_streaming))
                for (q in StreamQuality.entries) OptionRow(quality == q, stringResource(qualityTitle(q)), stringResource(qualitySub(q))) {
                    vm.setQuality(q); vm.settingsChanged()
                }
                Text(stringResource(R.string.set_quality_note), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))

                SectionTitle(stringResource(R.string.set_display))
                SwitchRow(stringResource(R.string.set_light_glass), stringResource(R.string.set_light_glass_sub), lightGlass) { prefs.setLightGlass(it) }

                SectionTitle(stringResource(R.string.set_audio))
                SwitchRow(stringResource(R.string.set_hires), stringResource(R.string.set_hires_sub), hiRes) { prefs.setHiResOutput(it) }
                SwitchRow(
                    stringResource(R.string.set_bitperfect),
                    stringResource(
                        when (out.state) {
                            BpState.NO_DEVICE -> R.string.set_bitperfect_nousb
                            BpState.OLD_ANDROID -> R.string.set_bitperfect_old
                            BpState.NO_BP_MODE -> R.string.set_bitperfect_unsupported
                            BpState.ACTIVE -> R.string.set_bitperfect_active
                            BpState.REFUSED -> R.string.set_bitperfect_refused
                            BpState.NO_MATCH -> R.string.set_bitperfect_nomatch
                            BpState.DISABLED -> R.string.set_bitperfect_sub
                        }, out.device ?: ""
                    ),
                    bitPerfect
                ) { prefs.setBitPerfect(it); vm.settingsChanged() }
                Text(stringResource(R.string.set_bitperfect_note), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
                Text(stringResource(R.string.set_replaygain), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 2.dp))
                for (m in ReplayGainMode.entries) OptionRow(rg == m, stringResource(when (m) { ReplayGainMode.OFF -> R.string.rg_off; ReplayGainMode.TRACK -> R.string.rg_track; ReplayGainMode.ALBUM -> R.string.rg_album }), null) {
                    prefs.setReplayGain(m); vm.settingsChanged()
                }
                Text(stringResource(R.string.set_replaygain_note), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
                Text(stringResource(R.string.set_fade), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 2.dp))
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in listOf(0, 2, 4, 6, 8)) Chip(if (s == 0) stringResource(R.string.off) else "${s}s", crossfade == s) { prefs.setCrossfade(s); vm.settingsChanged() }
                }
                Text(stringResource(R.string.set_fade_note), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                SwitchRow(stringResource(R.string.set_scrobble), stringResource(R.string.set_scrobble_sub), scrobble) { prefs.setScrobble(it) }

                SectionTitle(stringResource(R.string.set_cache))
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mb in listOf(256, 512, 1024, 2048, 4096)) Chip(if (mb >= 1024) "${mb / 1024} GB" else "$mb MB", cacheMb == mb) { prefs.setCacheMb(mb) }
                }
                Text(stringResource(R.string.set_cache_note), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                ActionRow(Icons.Rounded.DeleteSweep, stringResource(R.string.set_delete_downloads, fmtBytes(dlBytes)), { confirmDownloads = true })

                SectionTitle(stringResource(R.string.set_library))
                val scan by vm.scan.collectAsState()
                ActionRow(Icons.Rounded.Refresh, stringResource(R.string.set_scan_quick)) { vm.scanLibrary(false) }
                ActionRow(Icons.Rounded.Sync, stringResource(R.string.set_scan_full)) { vm.scanLibrary(true) }
                Text(
                    when (val sc = scan) {
                        is MainViewModel.ScanUi.Running -> stringResource(R.string.set_scan_running, sc.count)
                        is MainViewModel.ScanUi.Done -> stringResource(R.string.set_scan_done)
                        is MainViewModel.ScanUi.Failed -> stringResource(R.string.set_scan_failed, sc.message)
                        else -> stringResource(R.string.set_scan_note)
                    },
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )

                val coverCtx = androidx.compose.ui.platform.LocalContext.current
                ActionRow(Icons.Rounded.Image, stringResource(R.string.set_clear_covers)) {
                    vm.clearCoverCache()
                    android.widget.Toast.makeText(coverCtx, R.string.set_covers_cleared, android.widget.Toast.LENGTH_SHORT).show()
                }

                SectionTitle(stringResource(R.string.set_section_save))
                OptionRow(ask, stringResource(R.string.set_dest_ask), stringResource(R.string.set_dest_ask_sub)) { vm.setExportAsk(true) }
                OptionRow(!ask, stringResource(R.string.set_dest_fixed), stringResource(R.string.set_dest_fixed_sub)) { vm.setExportAsk(false) }
                if (!ask) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.set_folder), color = Muted, fontSize = 13.sp)
                            Text(folderName ?: stringResource(R.string.set_folder_none), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                        }
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(Accent.copy(alpha = 0.22f))
                                .clickable(onClick = onPickFolder).padding(horizontal = 18.dp, vertical = 11.dp)
                        ) {
                            Text(stringResource(if (dir == null) R.string.set_choose else R.string.set_change), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }

                SectionTitle(stringResource(R.string.set_icon))
                AppIconPicker()

                SectionTitle(stringResource(R.string.set_backup))
                ActionRow(Icons.Rounded.FileUpload, stringResource(R.string.export_title), onExport)
                ActionRow(Icons.Rounded.FileDownload, stringResource(R.string.import_title), onImport)
                Text(stringResource(R.string.set_backup_note), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))

                SectionTitle(stringResource(R.string.set_about))
                Text("NebulaMusic ${com.nebulamusic.app.BuildConfig.VERSION_NAME}", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
        }
        TopScrim(headerH, Modifier.align(Alignment.TopCenter))
        Row(
            Modifier.align(Alignment.TopCenter).measureHeight { headerH = it + 8.dp }.statusBarsPadding()
                .fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlassIconButton(onBack, backdrop, size = 46.dp) { Icon(Icons.Rounded.ArrowBack, null, tint = Color.White) }
            Text(stringResource(R.string.settings), fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
        }
        if (confirmDownloads) androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDownloads = false },
            confirmButton = { androidx.compose.material3.TextButton({ vm.deleteAllDownloads(); confirmDownloads = false }) { Text(stringResource(R.string.delete), color = Accent) } },
            dismissButton = { androidx.compose.material3.TextButton({ confirmDownloads = false }) { Text(stringResource(R.string.cancel)) } },
            title = { Text(stringResource(R.string.set_delete_downloads_title)) },
            text = { Text(stringResource(R.string.set_delete_downloads_msg)) },
            containerColor = SurfaceHi
        )
    }
}

private fun qualityTitle(q: StreamQuality) = when (q) {
    StreamQuality.ORIGINAL -> R.string.q_original
    StreamQuality.MP3_320 -> R.string.q_mp3_320
    StreamQuality.MP3_192 -> R.string.q_mp3_192
    StreamQuality.MP3_128 -> R.string.q_mp3_128
    StreamQuality.OPUS_128 -> R.string.q_opus_128
}

private fun qualitySub(q: StreamQuality) = if (q == StreamQuality.ORIGINAL) R.string.q_original_sub else R.string.q_transcoded_sub

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, color = Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.5.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp)
    )
}

@Composable
private fun OptionRow(selected: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked, null, tint = if (selected) Accent else Muted)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.Black, uncheckedTrackColor = SurfaceHi, uncheckedThumbColor = Muted, uncheckedBorderColor = Muted)
        )
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Accent)
        Spacer(Modifier.width(16.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (selected) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Accent else SurfaceHi).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}


private class IconChoice(val alias: String, val res: Int, val label: Int)

/** The alias that is enabled when the user never picked one (keep in sync with the manifest). */
private const val DEFAULT_ICON_ALIAS = "LauncherVinyl"

// Alias names are kept stable on purpose: a phone that already picked one keeps its choice after updates.
private val ICON_CHOICES = listOf(
    IconChoice("LauncherVinyl", R.mipmap.ic_icon_vinyl, R.string.icon_vinyl),
    IconChoice("LauncherAurora", R.mipmap.ic_icon_aurora, R.string.icon_aurora),
    IconChoice("LauncherGlass", R.mipmap.ic_icon_glass, R.string.icon_glass),
    IconChoice("LauncherDefault", R.mipmap.ic_icon_nebula, R.string.icon_nebula),
)

private fun aliasComponent(ctx: android.content.Context, alias: String) =
    android.content.ComponentName(ctx.packageName, "com.nebulamusic.app.$alias")

/** The launcher alias that is currently enabled (the default one while nothing was ever switched). */
private fun currentIcon(ctx: android.content.Context): String {
    val pm = ctx.packageManager
    return ICON_CHOICES.firstOrNull {
        val st = pm.getComponentEnabledSetting(aliasComponent(ctx, it.alias))
        st == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (st == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && it.alias == DEFAULT_ICON_ALIAS)
    }?.alias ?: DEFAULT_ICON_ALIAS
}

private fun setIcon(ctx: android.content.Context, alias: String) {
    val pm = ctx.packageManager
    // Enable the new one first so the app never has zero launcher entries.
    pm.setComponentEnabledSetting(aliasComponent(ctx, alias), android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
    for (c in ICON_CHOICES) if (c.alias != alias) {
        pm.setComponentEnabledSetting(aliasComponent(ctx, c.alias), android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
    }
}

@Composable
private fun AppIconPicker() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var current by remember { mutableStateOf(currentIcon(ctx)) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (c in ICON_CHOICES) {
            val on = c.alias == current
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).clickable { setIcon(ctx, c.alias); current = c.alias }.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { context -> android.widget.ImageView(context).apply { setImageResource(c.res) } },
                    modifier = Modifier.size(68.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (on) Accent else Color.Transparent).padding(if (on) 3.dp else 0.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                )
                Text(stringResource(c.label), color = if (on) Accent else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    Text(stringResource(R.string.set_icon_note), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
}
