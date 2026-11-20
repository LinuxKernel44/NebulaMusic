package com.nebulamusic.app.data

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Copies the original audio files (their tags and cover are already embedded by the server library) to a SAF folder or to a share intent. */
class Exporter(private val c: AppContainer) {
    private val ctx = c.ctx
    private val resolver get() = ctx.contentResolver

    class Result(val ok: Int, val failed: Int)

    private fun dirDoc(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    fun isUsable(uri: String?): Boolean =
        uri != null && resolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isWritePermission }

    fun persist(uri: Uri) {
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }

    fun release(uri: String) {
        runCatching {
            resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    suspend fun folderName(tree: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(dirDoc(tree), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }

    suspend fun export(tracks: List<Track>, tree: Uri, onProgress: (done: Int) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val dir = dirDoc(tree)
        var ok = 0
        var failed = 0
        for ((i, t) in tracks.withIndex()) {
            if (runCatching { exportOne(t, dir) }.isSuccess) ok++ else failed++
            onProgress(i + 1)
        }
        Result(ok, failed)
    }

    private fun fileName(t: Track) =
        safeName(listOf(t.artist, t.title).filter { it.isNotBlank() }.joinToString(" - ")).ifBlank { "track-${t.id}" } +
            "." + (t.suffix ?: "audio")

    private fun exportOne(t: Track, dir: Uri) {
        val name = fileName(t)
        val mime = t.contentType?.takeIf { it.startsWith("audio/") } ?: "audio/*"
        val created = mutableListOf<Uri>()
        try {
            val target = findChild(dir, name)
                ?: DocumentsContract.createDocument(resolver, dir, mime, name)?.also { created += it }
                ?: throw IOException("create failed")
            resolver.openOutputStream(target, "wt")!!.buffered().use { out -> open(t).use { it.copyTo(out) } }
        } catch (e: Exception) {
            created.forEach { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            throw e
        }
    }

    private fun findChild(dir: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getDocumentId(dir))
        return runCatching {
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getString(1) == name) return@runCatching DocumentsContract.buildDocumentUriUsingTree(dir, cur.getString(0))
                }
                null
            }
        }.getOrNull()
    }

    /** Copies the files to the cache and returns shareable content URIs. Tracks that fail are left out. */
    suspend fun prepareShare(tracks: List<Track>): List<Uri> = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val used = HashSet<String>()
        tracks.mapNotNull { t ->
            runCatching {
                var name = fileName(t)
                if (!used.add(name.lowercase())) name = "${t.id}-$name"
                val f = File(dir, name)
                try {
                    f.outputStream().buffered().use { out -> open(t).use { it.copyTo(out) } }
                } catch (e: Exception) { f.delete(); throw e }
                androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.share", f)
            }.getOrNull()
        }
    }

    /** The offline copy when there is one, otherwise the original file from the server. */
    private fun open(t: Track): InputStream {
        val local = kotlinx.coroutines.runBlocking { c.db.downloads().get(t.id) }?.path?.let(::File)
        if (local != null && local.isFile) return local.inputStream()
        val s = c.session.value ?: throw IOException("Not logged in")
        val r = c.http.newCall(Request.Builder().url(s.downloadUrl(t.id)).build()).execute()
        if (!r.isSuccessful) { r.close(); throw IOException("HTTP ${r.code}") }
        return r.body!!.byteStream()
    }

    private fun safeName(s: String) =
        s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(100)
}
