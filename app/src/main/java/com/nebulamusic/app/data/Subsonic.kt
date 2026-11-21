package com.nebulamusic.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest

// ---- Wire format ---------------------------------------------------------------------------------

@Serializable
private class Envelope(@SerialName("subsonic-response") val r: Resp)

@Serializable
private class ApiError(val code: Int = 0, val message: String = "")

@Serializable
private class Resp(
    val status: String = "failed",
    val version: String? = null,
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean = false,
    val error: ApiError? = null,
    val artists: ApiArtists? = null,
    val artist: ApiArtist? = null,
    val album: ApiAlbum? = null,
    val song: ApiSong? = null,
    val albumList2: ApiAlbumList? = null,
    val randomSongs: ApiSongs? = null,
    val songsByGenre: ApiSongs? = null,
    val starred2: ApiSearch? = null,
    val searchResult3: ApiSearch? = null,
    val playlists: ApiPlaylists? = null,
    val playlist: ApiPlaylist? = null,
    val genres: ApiGenres? = null,
    val artistInfo2: ApiArtistInfo? = null,
    val topSongs: ApiSongs? = null,
    val similarSongs2: ApiSongs? = null,
    val lyricsList: ApiLyricsList? = null,
    val scanStatus: ApiScanStatus? = null,
)

@Serializable private class ApiArtists(val index: List<ApiIndex> = emptyList())
@Serializable private class ApiIndex(val artist: List<ApiArtist> = emptyList())
@Serializable private class ApiAlbumList(val album: List<ApiAlbum> = emptyList())
@Serializable private class ApiSongs(val song: List<ApiSong> = emptyList())
@Serializable private class ApiPlaylists(val playlist: List<ApiPlaylist> = emptyList())
@Serializable private class ApiGenres(val genre: List<ApiGenre> = emptyList())
@Serializable private class ApiGenre(val value: String = "", val songCount: Int = 0, val albumCount: Int = 0)

@Serializable
private class ApiSearch(
    val artist: List<ApiArtist> = emptyList(),
    val album: List<ApiAlbum> = emptyList(),
    val song: List<ApiSong> = emptyList(),
)

@Serializable
private class ApiArtist(
    val id: String = "",
    val name: String = "",
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val starred: String? = null,
    val album: List<ApiAlbum> = emptyList(),
)

@Serializable
private class ApiAlbum(
    val id: String = "",
    val name: String = "",
    val title: String? = null,
    val artist: String = "",
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int = 0,
    val genre: String? = null,
    val starred: String? = null,
    val playCount: Int = 0,
    val song: List<ApiSong> = emptyList(),
)

@Serializable
private class ApiSong(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val artistId: String? = null,
    val album: String = "",
    val albumId: String? = null,
    val duration: Int = 0,
    val track: Int = 0,
    val discNumber: Int = 0,
    val year: Int = 0,
    val genre: String? = null,
    val coverArt: String? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val bitRate: Int = 0,
    val bitDepth: Int = 0,
    val samplingRate: Int = 0,
    val channelCount: Int = 0,
    val size: Long = 0,
    val starred: String? = null,
    val playCount: Int = 0,
    val bpm: Int = 0,
    val replayGain: ReplayGain? = null,
    val musicBrainzId: String? = null,
)

@Serializable
private class ApiPlaylist(
    val id: String = "",
    val name: String = "",
    val owner: String = "",
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val public: Boolean = false,
    val comment: String = "",
    val entry: List<ApiSong> = emptyList(),
)

@Serializable
private class ApiArtistInfo(
    val biography: String = "",
    val largeImageUrl: String? = null,
    val similarArtist: List<ApiArtist> = emptyList(),
)

@Serializable private class ApiScanStatus(val scanning: Boolean = false, val count: Int = 0)
@Serializable private class ApiLyricsList(val structuredLyrics: List<ApiStructuredLyrics> = emptyList())
@Serializable private class ApiStructuredLyrics(val synced: Boolean = false, val line: List<ApiLine> = emptyList())
@Serializable private class ApiLine(val start: Long? = null, val value: String = "")

// ---- Mapping --------------------------------------------------------------------------------------

private fun ApiSong.toTrack() = Track(
    id = id, title = title, artist = artist, artistId = artistId, album = album, albumId = albumId,
    durationSec = duration, trackNo = track, discNo = discNumber, year = year, genre = genre,
    coverArt = coverArt ?: id, suffix = suffix, contentType = contentType, bitRate = bitRate,
    samplingRate = samplingRate, bitDepth = bitDepth, channels = channelCount, size = size,
    starred = starred != null, playCount = playCount, bpm = bpm, replayGain = replayGain,
    musicBrainzId = musicBrainzId?.takeIf { it.isNotBlank() },
)

private fun ApiAlbum.toAlbum() = Album(
    id, name.ifBlank { title.orEmpty() }, artist, artistId, coverArt ?: id, songCount, duration, year, genre,
    starred != null, playCount,
)

private fun ApiArtist.toArtist() = Artist(id, name, coverArt, albumCount, starred != null)

private fun ApiPlaylist.toPlaylist() = Playlist(id, name, owner, songCount, duration, coverArt, public, comment)

// ---- Server + session ------------------------------------------------------------------------------

class SubsonicException(val code: Int, message: String) : IOException(message) {
    val isAuth get() = code == 40 || code == 41 || code == 44
}

/** Quality used when streaming. [raw] means "send the original file untouched". */
enum class StreamQuality(val format: String?, val maxBitRate: Int) {
    ORIGINAL(null, 0), MP3_320("mp3", 320), MP3_192("mp3", 192), MP3_128("mp3", 128), OPUS_128("opus", 128)
}

/**
 * One authenticated connection to a Navidrome / OpenSubsonic server. The salt is fixed per session so that cover and
 * stream URLs stay identical between calls (which keeps the image and audio caches effective).
 */
class Session(
    val server: ServerConfig,
    private val password: String,
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val base = server.url.trimEnd('/')
    private val salt = server.id.replace("-", "").take(12)
    private val token = md5(password + salt)

    private fun md5(s: String) =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun authParams(b: okhttp3.HttpUrl.Builder) = b
        .addQueryParameter("u", server.username).addQueryParameter("t", token).addQueryParameter("s", salt)
        .addQueryParameter("v", "1.16.1").addQueryParameter("c", "NebulaMusic")

    fun url(method: String, vararg params: Pair<String, String?>): String {
        val b = "$base/rest/$method".toHttpUrl().newBuilder()
        authParams(b)
        for ((k, v) in params) if (v != null) b.addQueryParameter(k, v)
        return b.build().toString()
    }

    fun coverUrl(coverArt: String?, size: Int = 300): String? =
        coverArt?.let { url("getCoverArt", "id" to it, "size" to size.toString()) }

    fun streamUrl(id: String, q: StreamQuality): String = url(
        "stream", "id" to id,
        "format" to (q.format ?: "raw"),
        "maxBitRate" to q.maxBitRate.takeIf { it > 0 }?.toString(),
        "estimateContentLength" to if (q.format != null) "true" else null,
    )

    fun downloadUrl(id: String) = url("download", "id" to id)

    /** Low-bitrate transcode used only to compute waveforms cheaply. */
    fun waveUrl(id: String) = url("stream", "id" to id, "format" to "mp3", "maxBitRate" to "48")

    private suspend fun call(method: String, vararg params: Pair<String, String?>): Resp = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url(method, *params, "f" to "json")).cacheControl(CacheControl.FORCE_NETWORK).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("Empty response")
            val r = try { json.decodeFromString<Envelope>(body).r } catch (e: Exception) {
                throw IOException("Not a Subsonic server", e)
            }
            if (r.status != "ok") throw SubsonicException(r.error?.code ?: 0, r.error?.message ?: "Request failed")
            r
        }
    }

    // ---- Endpoints ----

    /** Returns a short server description such as "Navidrome 0.64.1". */
    suspend fun ping(): String {
        val r = call("ping")
        return listOfNotNull(r.type?.replaceFirstChar { it.uppercase() }, r.serverVersion?.substringBefore(' ')).joinToString(" ")
            .ifBlank { "Subsonic ${r.version}" }
    }

    suspend fun artists(): List<Artist> = call("getArtists").artists?.index.orEmpty().flatMap { it.artist }.map { it.toArtist() }

    suspend fun artist(id: String): Pair<Artist, List<Album>> {
        val a = call("getArtist", "id" to id).artist ?: throw IOException("Artist not found")
        return a.toArtist() to a.album.map { it.toAlbum() }
    }

    suspend fun artistInfo(id: String): ArtistInfo {
        val i = runCatching { call("getArtistInfo2", "id" to id, "count" to "12").artistInfo2 }.getOrNull() ?: return ArtistInfo()
        return ArtistInfo(
            i.biography.replace(Regex("<a [^>]*>.*?</a>", RegexOption.IGNORE_CASE), "").replace(Regex("<[^>]+>"), "").trim(),
            i.largeImageUrl, i.similarArtist.map { it.toArtist() },
        )
    }

    suspend fun topSongs(artistName: String): List<Track> =
        runCatching { call("getTopSongs", "artist" to artistName, "count" to "20").topSongs?.song.orEmpty().map { it.toTrack() } }
            .getOrDefault(emptyList())

    /** Songs of the library close to [id] (Navidrome relies on its Last.fm / agents; may be empty). */
    suspend fun similarSongs(id: String, count: Int = 40): List<Track> =
        runCatching { call("getSimilarSongs2", "id" to id, "count" to count.toString()).similarSongs2?.song.orEmpty().map { it.toTrack() } }
            .getOrDefault(emptyList())

    suspend fun album(id: String): Pair<Album, List<Track>> {
        val a = call("getAlbum", "id" to id).album ?: throw IOException("Album not found")
        return a.toAlbum() to a.song.map { it.toTrack() }
    }

    suspend fun song(id: String): Track? = call("getSong", "id" to id).song?.toTrack()

    suspend fun albums(sort: AlbumSort, offset: Int, size: Int, genre: String? = null, fromYear: Int? = null, toYear: Int? = null): List<Album> =
        call(
            "getAlbumList2", "type" to if (genre != null) "byGenre" else sort.type, "size" to size.toString(),
            "offset" to offset.toString(), "genre" to genre, "fromYear" to fromYear?.toString(), "toYear" to toYear?.toString(),
        ).albumList2?.album.orEmpty().map { it.toAlbum() }

    suspend fun randomSongs(size: Int = 50, genre: String? = null): List<Track> =
        call("getRandomSongs", "size" to size.toString(), "genre" to genre).randomSongs?.song.orEmpty().map { it.toTrack() }

    suspend fun songsByGenre(genre: String, offset: Int, size: Int): List<Track> =
        call("getSongsByGenre", "genre" to genre, "count" to size.toString(), "offset" to offset.toString())
            .songsByGenre?.song.orEmpty().map { it.toTrack() }

    suspend fun genres(): List<Genre> =
        call("getGenres").genres?.genre.orEmpty().filter { it.value.isNotBlank() }.map { Genre(it.value, it.songCount, it.albumCount) }

    suspend fun starred(): SearchResults {
        val s = call("getStarred2").starred2
        return SearchResults(s?.artist.orEmpty().map { it.toArtist() }, s?.album.orEmpty().map { it.toAlbum() }, s?.song.orEmpty().map { it.toTrack() })
    }

    suspend fun search(query: String, artists: Int = 10, albums: Int = 20, songs: Int = 40, songOffset: Int = 0): SearchResults {
        val s = call(
            "search3", "query" to query, "artistCount" to artists.toString(), "albumCount" to albums.toString(),
            "songCount" to songs.toString(), "songOffset" to songOffset.toString(),
        ).searchResult3
        return SearchResults(s?.artist.orEmpty().map { it.toArtist() }, s?.album.orEmpty().map { it.toAlbum() }, s?.song.orEmpty().map { it.toTrack() })
    }

    /** Every song of the library, paged (Navidrome answers an empty query with the whole catalogue). */
    suspend fun allSongs(offset: Int, size: Int): List<Track> =
        call("search3", "query" to "", "artistCount" to "0", "albumCount" to "0", "songCount" to size.toString(), "songOffset" to offset.toString())
            .searchResult3?.song.orEmpty().map { it.toTrack() }

    suspend fun playlists(): List<Playlist> = call("getPlaylists").playlists?.playlist.orEmpty().map { it.toPlaylist() }

    suspend fun playlist(id: String): Pair<Playlist, List<Track>> {
        val p = call("getPlaylist", "id" to id).playlist ?: throw IOException("Playlist not found")
        return p.toPlaylist() to p.entry.map { it.toTrack() }
    }

    suspend fun createPlaylist(name: String, songIds: List<String>) {
        withContext(Dispatchers.IO) {
            val b = StringBuilder(url("createPlaylist", "name" to name, "f" to "json"))
            songIds.forEach { b.append("&songId=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
            exec(b.toString())
        }
    }

    suspend fun addToPlaylist(id: String, songIds: List<String>) = updatePlaylist(id, add = songIds)
    suspend fun removeFromPlaylist(id: String, indexes: List<Int>) = updatePlaylist(id, removeIdx = indexes)
    suspend fun renamePlaylist(id: String, name: String) = updatePlaylist(id, name = name)

    private suspend fun updatePlaylist(id: String, name: String? = null, add: List<String> = emptyList(), removeIdx: List<Int> = emptyList()) =
        withContext(Dispatchers.IO) {
            val b = StringBuilder(url("updatePlaylist", "playlistId" to id, "name" to name, "f" to "json"))
            add.forEach { b.append("&songIdToAdd=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
            removeIdx.forEach { b.append("&songIndexToRemove=").append(it) }
            exec(b.toString())
        }

    suspend fun deletePlaylist(id: String) { call("deletePlaylist", "id" to id) }

    suspend fun star(id: String, kind: StarKind = StarKind.SONG) {
        call("star", kind.param to id)
    }

    suspend fun unstar(id: String, kind: StarKind = StarKind.SONG) {
        call("unstar", kind.param to id)
    }

    /** [submission] false = "now playing" ping, true = counted as a play. */
    suspend fun scrobble(id: String, submission: Boolean, timeMs: Long = System.currentTimeMillis()) {
        call("scrobble", "id" to id, "submission" to submission.toString(), "time" to timeMs.toString())
    }

    /** Asks the server to look for new / changed files. [full] re-reads every file (slow); needs an admin account. */
    suspend fun startScan(full: Boolean): ScanProgress {
        val r = call("startScan", "fullScan" to if (full) "true" else null).scanStatus
        return ScanProgress(r?.scanning ?: true, r?.count ?: 0)
    }

    suspend fun scanStatus(): ScanProgress {
        val r = call("getScanStatus").scanStatus
        return ScanProgress(r?.scanning ?: false, r?.count ?: 0)
    }

    suspend fun lyrics(id: String): Lyrics? {
        val l = runCatching { call("getLyricsBySongId", "id" to id).lyricsList?.structuredLyrics }.getOrNull()
            ?.maxByOrNull { if (it.synced) 1 else 0 } ?: return null
        val lines = l.line.map { LyricLine(if (l.synced) it.start else null, it.value) }
        return if (lines.isEmpty()) null else Lyrics(l.synced, lines)
    }

    private fun exec(fullUrl: String) {
        val req = Request.Builder().url(fullUrl).cacheControl(CacheControl.FORCE_NETWORK).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val r = json.decodeFromString<Envelope>(body).r
            if (r.status != "ok") throw SubsonicException(r.error?.code ?: 0, r.error?.message ?: "Request failed")
        }
    }
}

data class ScanProgress(val scanning: Boolean, val count: Int)

enum class StarKind(val param: String) { SONG("id"), ALBUM("albumId"), ARTIST("artistId") }

/** Checks the credentials of a not-yet-saved server. */
suspend fun testConnection(http: OkHttpClient, server: ServerConfig, password: String): String =
    Session(server, password, http).ping()
