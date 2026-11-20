package com.nebulamusic.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import com.nebulamusic.app.R
import com.nebulamusic.app.data.ServerConfig
import com.nebulamusic.app.data.normalizeServerUrl
import com.nebulamusic.app.glass.GlassButton
import com.nebulamusic.app.glass.GlassDefaults
import com.nebulamusic.app.glass.GlassIconButton
import com.nebulamusic.app.glass.GlassTextField
import kotlinx.coroutines.launch

/**
 * Add a server, or edit one ([existing]) — for instance to type a new password after it changed.
 * The password field stays empty when editing: leaving it empty keeps the saved one.
 */
@Composable
fun LoginScreen(vm: MainViewModel, existing: ServerConfig?, canCancel: Boolean, onDone: () -> Unit, onCancel: () -> Unit) {
    val backdrop = glassBackdrop()
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var user by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    fun connect() {
        if (busy) return
        val u = normalizeServerUrl(url)
        if (u.isBlank() || user.isBlank() || (password.isBlank() && existing == null)) { error = ctx.getString(R.string.login_fill_all); return }
        busy = true; error = null
        scope.launch {
            try {
                vm.addServer(existing, name.trim(), u, user.trim(), password.ifEmpty { null })
                onDone()
            } catch (e: Exception) {
                error = vm.errorOf(e).message
            } finally { busy = false }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Glass fields must be siblings of the backdrop layer they sample, never its children.
        ScreenBackdrop {}
        run {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(if (canCancel) 56.dp else 40.dp))
                Icon(Icons.Rounded.GraphicEq, null, tint = Accent, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(10.dp))
                Text("NEBULA", fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, letterSpacing = 3.sp)
                Text(stringResource(R.string.login_subtitle), color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(28.dp))

                GlassTextField(
                    url, { url = it; error = null }, stringResource(R.string.login_url), backdrop,
                    Modifier.fillMaxWidth(), imeAction = ImeAction.Next,
                    leading = { Icon(Icons.Rounded.Language, null, tint = Color.White.copy(alpha = 0.7f)) },
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                )
                Spacer(Modifier.height(12.dp))
                GlassTextField(
                    user, { user = it; error = null }, stringResource(R.string.login_user), backdrop,
                    Modifier.fillMaxWidth(), imeAction = ImeAction.Next,
                    leading = { Icon(Icons.Rounded.Person, null, tint = Color.White.copy(alpha = 0.7f)) },
                )
                Spacer(Modifier.height(12.dp))
                GlassTextField(
                    password, { password = it; error = null },
                    stringResource(if (existing != null) R.string.login_password_keep else R.string.login_password), backdrop,
                    Modifier.fillMaxWidth(), imeAction = ImeAction.Done, onImeAction = ::connect, password = true,
                    leading = { Icon(Icons.Rounded.Lock, null, tint = Color.White.copy(alpha = 0.7f)) },
                )
                Spacer(Modifier.height(12.dp))
                GlassTextField(
                    name, { name = it }, stringResource(R.string.login_name), backdrop, Modifier.fillMaxWidth(), imeAction = ImeAction.Done,
                    onImeAction = ::connect,
                )

                if (error != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(error!!, color = Color(0xFFFF8A80), fontSize = 14.sp, textAlign = TextAlign.Center)
                }
                if (url.trim().startsWith("http://", true)) {
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.login_http_warning), color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(22.dp))
                GlassButton(::connect, backdrop, Modifier.fillMaxWidth(), height = 54.dp, tint = GlassDefaults.Accent) {
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    else BasicText(
                        stringResource(R.string.login_connect),
                        style = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
        if (canCancel) Row(Modifier.statusBarsPadding().padding(12.dp)) {
            GlassIconButton(onCancel, backdrop, size = 46.dp) {
                Icon(androidx.compose.material.icons.Icons.Rounded.ArrowBack, null, tint = Color.White)
            }
        }
    }
}
