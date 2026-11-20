package com.nebulamusic.app.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export / import of the app settings and the server list (URL + username only: passwords never leave the device,
 * so they have to be typed again after an import).
 */
class Backup(private val c: AppContainer) {
    private val ctx = c.ctx
    private val format = "nebulamusic-backup"

    /** Returns the number of servers written. */
    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val servers = JSONArray()
        c.servers.servers.value.forEach {
            servers.put(JSONObject().put("name", it.name).put("url", it.url).put("username", it.username))
        }
        val root = JSONObject().put("format", format).put("version", 2)
            .put("app", com.nebulamusic.app.BuildConfig.VERSION_NAME)
            .put("prefs", c.prefs.toJson()).put("servers", servers)
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: error("no output stream")
        out.use { it.write(root.toString(2).toByteArray()) }
        servers.length()
    }

    /** Restores settings and adds the servers that are not already known (they stay logged out until a password is entered). */
    suspend fun import(uri: Uri): Unit = withContext(Dispatchers.IO) {
        val text = ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: error("no input stream")
        val r = JSONObject(text)
        require(r.optString("format") == format) { "invalid backup" }
        r.optJSONObject("prefs")?.let { c.prefs.restore(it) }
        val known = c.servers.servers.value.map { it.url to it.username }.toSet()
        val arr = r.optJSONArray("servers") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val url = o.optString("url"); val user = o.optString("username")
            if (url.isBlank() || (url to user) in known) continue
            c.servers.save(ServerConfig(name = o.optString("name", url), url = url, username = user), null)
        }
    }
}
