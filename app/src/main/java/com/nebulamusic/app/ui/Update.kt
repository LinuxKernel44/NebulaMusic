package com.nebulamusic.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nebulamusic.app.R
import com.nebulamusic.app.data.UpdateState
import com.nebulamusic.app.glass.GlassButton
import com.nebulamusic.app.glass.GlassDefaults

/** Content of the update dialog: check, download, then install (asking for the "unknown apps" permission when needed). */
@Composable
fun UpdateContent(vm: MainViewModel, backdrop: com.kyant.backdrop.Backdrop, onDismiss: () -> Unit) {
    val up = vm.updater
    val state by up.state.collectAsState()
    val ctx = LocalContext.current
    var canInstall by remember { mutableStateOf(up.canInstall()) }
    var waitingForPermission by remember { mutableStateOf(false) }

    fun install(s: UpdateState.Ready) {
        runCatching { ctx.startActivity(up.installIntent(s.file)) }
    }
    // Back from the system permission page: re-check and carry on with the installation by itself.
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(state)
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                canInstall = up.canInstall()
                val s = current
                if (waitingForPermission && canInstall && s is UpdateState.Ready) { waitingForPermission = false; install(s) }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val white = TextStyle(color = Color.White, fontSize = 15.sp)
    val dim = TextStyle(color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp)
    BasicText(stringResource(R.string.upd_title), style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold))
    Spacer(Modifier.height(12.dp))
    when (val s = state) {
        UpdateState.Idle, UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            BasicText(stringResource(R.string.upd_checking), style = white)
        }
        UpdateState.UpToDate -> BasicText(stringResource(R.string.upd_uptodate, up.currentVersion), style = white)
        is UpdateState.Available -> {
            BasicText(stringResource(R.string.upd_available, s.info.version, up.currentVersion), style = white)
            if (s.info.sizeBytes > 0) {
                Spacer(Modifier.height(4.dp))
                BasicText(stringResource(R.string.upd_size, "%.1f MB".format(s.info.sizeBytes / 1_048_576.0)), style = dim)
            }
            if (s.info.notes.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) { BasicText(s.info.notes, style = dim) }
            }
        }
        is UpdateState.Downloading -> {
            BasicText(stringResource(R.string.upd_downloading, (s.progress * 100).toInt()), style = white)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = GlassDefaults.Accent, trackColor = Color.White.copy(alpha = 0.2f))
        }
        is UpdateState.Ready -> {
            BasicText(stringResource(R.string.upd_ready, s.info.version), style = white)
            if (!canInstall) {
                Spacer(Modifier.height(10.dp))
                BasicText(stringResource(R.string.upd_perm), style = dim)
            }
        }
        is UpdateState.Failed -> BasicText(stringResource(R.string.upd_failed, s.reason), style = white)
    }
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        GlassButton(onDismiss, backdrop, height = 46.dp) {
            BasicText(
                stringResource(if (state is UpdateState.Available || state is UpdateState.Ready) R.string.upd_later else R.string.close),
                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            )
        }
        val label: Int? = when (state) {
            is UpdateState.Available -> R.string.upd_download
            is UpdateState.Ready -> if (canInstall) R.string.upd_install else R.string.upd_perm_open
            is UpdateState.Failed -> R.string.upd_retry
            UpdateState.UpToDate -> R.string.upd_check
            else -> null
        }
        if (label != null) GlassButton({
            when (val s = state) {
                is UpdateState.Available -> up.download()
                is UpdateState.Ready -> if (canInstall) install(s) else {
                    waitingForPermission = true
                    runCatching { ctx.startActivity(up.permissionIntent()) }
                }
                is UpdateState.Failed -> if (s.info != null) up.download() else up.check()
                UpdateState.UpToDate -> up.check()
                else -> {}
            }
        }, backdrop, height = 46.dp, tint = GlassDefaults.Accent) {
            BasicText(stringResource(label), style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        }
    }
}
