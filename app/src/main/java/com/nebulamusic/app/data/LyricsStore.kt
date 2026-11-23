package com.nebulamusic.app.data

import java.io.File

/** Lyrics <-> text helpers, and a tiny on-disk cache so lyrics keep working offline and are fetched once per song. */
object LyricsCodec {
    private val stamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    /** Plain text, or LRC text with `[mm:ss.xx]` stamps. */
    fun fromText(raw: String): Lyrics? {
        val rows = raw.replace("\r", "").lines()
        val synced = rows.count { stamp.containsMatchIn(it) } >= 2
        val out = ArrayList<LyricLine>()
        for (row in rows) {
            if (!synced) { out += LyricLine(null, row.trim()); continue }
            val marks = stamp.findAll(row).toList()
            val text = row.replace(stamp, "").trim()
            if (marks.isEmpty()) continue // tags such as [ar:...]
            for (m in marks) {
                val frac = m.groupValues[3]
                val ms = (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000 +
                    if (frac.isEmpty()) 0 else (frac.padEnd(3, '0').take(3)).toLong()
                out += LyricLine(ms, text)
            }
        }
        val lines = (if (synced) out.sortedBy { it.startMs } else out).dropWhile { it.text.isBlank() && !synced }
        return if (lines.none { it.text.isNotBlank() }) null else Lyrics(synced, lines)
    }

    fun encode(l: Lyrics?): String =
        if (l == null) "N" else (if (l.synced) "S" else "P") + "\n" + l.lines.joinToString("\n") { "${it.startMs ?: ""}\t${it.text.replace('\n', ' ')}" }

    /** `Result.success(null)` = cached "no lyrics". */
    fun decode(raw: String): Result<Lyrics?> = runCatching {
        val rows = raw.lines()
        when (rows.firstOrNull()) {
            "N" -> null
            "S", "P" -> Lyrics(rows[0] == "S", rows.drop(1).map { r ->
                val tab = r.indexOf('\t')
                LyricLine(r.substring(0, tab).toLongOrNull(), r.substring(tab + 1))
            })
            else -> error("bad")
        }
    }
}

class LyricsStore(private val dir: File) {
    private fun file(id: String) = File(dir.apply { mkdirs() }, id.filter { it.isLetterOrDigit() } + ".lrc")

    /** `null` = not cached; `Result.success(null)` = known to have no lyrics. */
    fun read(id: String, maxAgeMs: Long = Long.MAX_VALUE): Result<Lyrics?>? {
        val f = file(id)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > maxAgeMs) return null
        return LyricsCodec.decode(runCatching { f.readText() }.getOrDefault("")).takeIf { it.isSuccess }
    }

    fun write(id: String, l: Lyrics?) { runCatching { file(id).writeText(LyricsCodec.encode(l)) } }
}
