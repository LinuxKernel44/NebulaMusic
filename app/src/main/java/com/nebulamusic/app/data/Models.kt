package com.nebulamusic.app.data

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

/** ReplayGain values as exposed by OpenSubsonic (dB / linear peak). */
@Serializable
data class ReplayGain(
    val trackGain: Float? = null,
    val albumGain: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
    val baseGain: Float? = null,
    val fallbackGain: Float? = null,
)

/** A playable song. Serializable so it can travel inside a MediaItem and be cached for offline use. */
@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String = "",
    val artistId: String? = null,
    val album: String = "",
    val albumId: String? = null,
    val durationSec: Int = 0,
    val trackNo: Int = 0,
    val discNo: Int = 0,
    val year: Int = 0,
    val genre: String? = null,
    val coverArt: String? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val bitRate: Int = 0,
    val samplingRate: Int = 0,
    val bitDepth: Int = 0,
    val channels: Int = 0,
    val size: Long = 0,
    val starred: Boolean = false,
    val playCount: Int = 0,
    val bpm: Int = 0,
    val replayGain: ReplayGain? = null,
    /** MusicBrainz recording id when the file is tagged with one. */
    val musicBrainzId: String? = null,
    /** When the song was added to the library (epoch ms, 0 = unknown). */
    val addedAt: Long = 0,
) {
    val subtitle: String get() = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ")

    /** "FLAC" / "MP3" ... in upper case, or "" when unknown. */
    val format: String get() = suffix?.uppercase(Locale.ROOT).orEmpty()

    val isLossless: Boolean get() = suffix?.lowercase(Locale.ROOT) in LOSSLESS
    val isHiRes: Boolean get() = isLossless && (bitDepth > 16 || samplingRate > 44100)

    companion object {
        private val LOSSLESS = setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff", "tta", "wma_lossless")
    }
}

data class Album(
    val id: String,
    val name: String,
    val artist: String = "",
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val durationSec: Int = 0,
    val year: Int = 0,
    val genre: String? = null,
    val starred: Boolean = false,
    val playCount: Int = 0,
    val addedAt: Long = 0,
)

data class Artist(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val starred: Boolean = false,
)

data class Playlist(
    val id: String,
    val name: String,
    val owner: String = "",
    val songCount: Int = 0,
    val durationSec: Int = 0,
    val coverArt: String? = null,
    val isPublic: Boolean = false,
    val comment: String = "",
    val addedAt: Long = 0,
)

data class Genre(val name: String, val songCount: Int, val albumCount: Int)

data class ArtistInfo(val biography: String = "", val image: String? = null, val similar: List<Artist> = emptyList())

data class SearchResults(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    val isEmpty get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
}

data class LyricLine(val startMs: Long?, val text: String)
data class Lyrics(val synced: Boolean, val lines: List<LyricLine>)

enum class AlbumSort(val type: String) {
    NEWEST("newest"), RECENT("recent"), FREQUENT("frequent"), RANDOM("random"),
    NAME("alphabeticalByName"), ARTIST("alphabeticalByArtist"), STARRED("starred"), YEAR("byYear"),
}

/** Lower-case, accent-less string used for local filtering / sorting. */
fun String.fold(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
