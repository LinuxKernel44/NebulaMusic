package com.nebulamusic.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.statusBarsPadding
import com.kyant.backdrop.Backdrop
import com.nebulamusic.app.R
import com.nebulamusic.app.data.*
import com.nebulamusic.app.glass.GlassDefaults
import com.nebulamusic.app.glass.GlassMenu

/** Ways to order a Library list. Not every list offers every key (see [sortKeysFor]). */
enum class SortKey { NAME, ARTIST, YEAR, ADDED, COUNT, RECENT, FREQUENT, RANDOM, STARRED }

/** The chosen order. [reverse] flips the natural direction of [key] (A-Z, or newest / largest first). */
data class SortSpec(val key: SortKey = SortKey.NAME, val reverse: Boolean = false)

/** Orders that only the server can compute (play history, favorites, random): no local sort, no reverse. */
val SortKey.serverOnly get() = this == SortKey.RECENT || this == SortKey.FREQUENT || this == SortKey.RANDOM || this == SortKey.STARRED

fun sortKeysFor(section: LibSection): List<SortKey> = when (section) {
    LibSection.ARTISTS -> listOf(SortKey.NAME, SortKey.COUNT)
    LibSection.ALBUMS -> listOf(SortKey.NAME, SortKey.ARTIST, SortKey.YEAR, SortKey.ADDED, SortKey.COUNT, SortKey.RECENT, SortKey.FREQUENT, SortKey.RANDOM, SortKey.STARRED)
    LibSection.GENRES -> listOf(SortKey.NAME, SortKey.COUNT)
    LibSection.PLAYLISTS -> listOf(SortKey.NAME, SortKey.ADDED, SortKey.COUNT)
    LibSection.SONGS, LibSection.FAVORITES, LibSection.DOWNLOADS -> listOf(SortKey.NAME, SortKey.ARTIST, SortKey.YEAR, SortKey.ADDED)
}

private fun label(section: LibSection, k: SortKey) = when (k) {
    SortKey.NAME -> R.string.by_name
    SortKey.ARTIST -> R.string.by_artist
    SortKey.YEAR -> R.string.by_year
    SortKey.ADDED -> R.string.by_added
    SortKey.COUNT -> if (section == LibSection.ARTISTS) R.string.by_albums else R.string.by_tracks
    SortKey.RECENT -> R.string.sort_recent
    SortKey.FREQUENT -> R.string.sort_frequent
    SortKey.RANDOM -> R.string.sort_random
    SortKey.STARRED -> R.string.sort_starred
}

private fun <T> List<T>.dir(s: SortSpec) = if (s.reverse) asReversed() else this

fun sortTracks(l: List<Track>, s: SortSpec): List<Track> = when (s.key) {
    SortKey.ARTIST -> l.sortedWith(compareBy<Track>({ it.artist.fold() }, { it.album.fold() }, { it.discNo }, { it.trackNo }))
    SortKey.YEAR -> l.sortedWith(compareByDescending<Track> { it.year }.thenBy { it.title.fold() })
    SortKey.ADDED -> l.sortedByDescending { it.addedAt }
    else -> l.sortedBy { it.title.fold() }
}.dir(s)

fun sortAlbums(l: List<Album>, s: SortSpec): List<Album> = when (s.key) {
    SortKey.ARTIST -> l.sortedWith(compareBy<Album>({ it.artist.fold() }, { it.year }, { it.name.fold() }))
    SortKey.YEAR -> l.sortedWith(compareByDescending<Album> { it.year }.thenBy { it.name.fold() })
    SortKey.ADDED -> l.sortedByDescending { it.addedAt }
    SortKey.COUNT -> l.sortedWith(compareByDescending<Album> { it.songCount }.thenBy { it.name.fold() })
    else -> l.sortedBy { it.name.fold() }
}.dir(s)

fun sortArtists(l: List<Artist>, s: SortSpec): List<Artist> = when (s.key) {
    SortKey.COUNT -> l.sortedWith(compareByDescending<Artist> { it.albumCount }.thenBy { it.name.fold() })
    else -> l.sortedBy { it.name.fold() }
}.dir(s)

fun sortGenres(l: List<Genre>, s: SortSpec): List<Genre> = when (s.key) {
    SortKey.COUNT -> l.sortedWith(compareByDescending<Genre> { it.songCount }.thenBy { it.name.fold() })
    else -> l.sortedBy { it.name.fold() }
}.dir(s)

fun sortPlaylists(l: List<Playlist>, s: SortSpec): List<Playlist> = when (s.key) {
    SortKey.ADDED -> l.sortedByDescending { it.addedAt }
    SortKey.COUNT -> l.sortedWith(compareByDescending<Playlist> { it.songCount }.thenBy { it.name.fold() })
    else -> l.sortedBy { it.name.fold() }
}.dir(s)

/** The sort sheet: radio list of orders plus a "Reverse" checkbox (hidden for server-only orders). */
@Composable
fun SortMenu(visible: Boolean, onDismiss: () -> Unit, backdrop: Backdrop, section: LibSection, spec: SortSpec, onChange: (SortSpec) -> Unit) {
    GlassMenu(
        visible = visible, onDismiss = onDismiss, backdrop = backdrop,
        alignment = Alignment.TopEnd, modifier = Modifier.statusBarsPadding().padding(top = 64.dp, end = 12.dp)
    ) {
        // Never taller than the space above the mini player / tab bar: scrolls when the screen is small.
        val maxH = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - LocalBottomInset.current - 130.dp).coerceAtLeast(200.dp)
        androidx.compose.foundation.layout.Column(
            Modifier.heightIn(max = maxH).verticalScroll(androidx.compose.foundation.rememberScrollState())
        ) {
        for (k in sortKeysFor(section)) {
            val on = k == spec.key
            GlassMenuRow(
                if (on) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked, stringResource(label(section, k)),
                { onChange(SortSpec(k, spec.reverse && !k.serverOnly)) },
                color = if (on) GlassDefaults.Accent else Color.White, fill = false, minWidth = 240.dp, vPad = 10.dp,
            )
        }
        if (!spec.key.serverOnly) GlassMenuRow(
            if (spec.reverse) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, stringResource(R.string.sort_reverse),
            { onChange(spec.copy(reverse = !spec.reverse)) },
            color = if (spec.reverse) GlassDefaults.Accent else Color.White, fill = false, minWidth = 240.dp, vPad = 10.dp,
        )
        }
    }
}
