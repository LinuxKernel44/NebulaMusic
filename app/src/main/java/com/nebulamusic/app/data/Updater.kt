package com.nebulamusic.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class UpdateInfo(val version: String, val notes: String, val url: String, val sizeBytes: Long, val sha256: String?)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val reason: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * In-app updates from the GitHub releases of the project: check the latest release, download its APK into the cache
 * (checking its SHA-256 when GitHub provides one) and hand it to the system installer. Android needs the
 * "install unknown apps" permission for this app; [canInstall] / [permissionIntent] cover that case.
 */
class Updater(private val http: OkHttpClient, private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private val dir get() = File(ctx.cacheDir, "updates")

    val currentVersion: String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()

    private fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
    private fun isNewer(latest: String, current: String): Boolean {
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Removes downloaded APKs that are no longer useful (already installed or older). */
    fun cleanup() {
        scope.launch {
            val s = _state.value
            if (s is UpdateState.Downloading || s is UpdateState.Ready) return@launch
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    fun check() {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        job = scope.launch {
            _state.value = try {
                val req = Request.Builder().url("https://api.github.com/repos/LinuxKernel44/NebulaMusic/releases/latest")
                    .header("Accept", "application/vnd.github+json").build()
                val body = http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    JSONObject(r.body?.string().orEmpty())
                }
                val tag = body.getString("tag_name")
                if (!isNewer(tag, currentVersion)) UpdateState.UpToDate
                else {
                    val assets = body.getJSONArray("assets")
                    var apk: JSONObject? = null
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.getString("name").endsWith(".apk")) { apk = a; break }
                    }
                    if (apk == null) error("no APK in the release")
                    UpdateState.Available(
                        UpdateInfo(
                            tag.removePrefix("v"), body.optString("body").trim(), apk.getString("browser_download_url"),
                            apk.optLong("size"), apk.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 }
                        )
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun download() {
        val info = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.Failed -> s.info ?: return
            else -> return
        }
        _state.value = UpdateState.Downloading(info, 0f)
        job = scope.launch {
            try {
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "NebulaMusic-${info.version}.apk")
                val part = File(dir, out.name + ".part")
                val sha = MessageDigest.getInstance("SHA-256")
                http.newCall(Request.Builder().url(info.url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val b = r.body ?: error("empty response")
                    val total = b.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                    var done = 0L
                    var lastPct = -1
                    b.byteStream().use { input ->
                        part.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n); sha.update(buf, 0, n); done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                if (pct != lastPct) { lastPct = pct; _state.value = UpdateState.Downloading(info, pct / 100f) }
                            }
                        }
                    }
                }
                val hex = sha.digest().joinToString("") { "%02x".format(it) }
                if (info.sha256 != null && !info.sha256.equals(hex, ignoreCase = true)) { part.delete(); error("checksum mismatch") }
                if (!part.renameTo(out)) error("could not save the file")
                _state.value = UpdateState.Ready(info, out)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                dir.listFiles()?.forEach { it.delete() }
                _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName, info)
            }
        }
    }

    fun canInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Opens the system page where the user allows this app to install APKs. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.share", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun reset() { if (_state.value !is UpdateState.Downloading && _state.value !is UpdateState.Checking) _state.value = UpdateState.Idle }
}
