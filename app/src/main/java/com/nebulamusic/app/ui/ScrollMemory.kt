package com.nebulamusic.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

/**
 * Remembers where each screen was scrolled to (vertically and horizontally, including every scrolling row) and what was typed
 * in the search box, so coming back to a screen puts everything exactly where it was left.
 */
class ScrollMemory {
    val lists = HashMap<String, Pair<Int, Int>>()
    val scrolls = HashMap<String, Int>()
    val texts = HashMap<String, String>()
}

val LocalScrollMemory = compositionLocalOf { ScrollMemory() }

/** Identity of the screen being drawn (tab or stacked destination); scroll positions are stored under it. */
val LocalScreenKey = compositionLocalOf { "" }

@Composable
fun rememberKeptListState(name: String = "main"): LazyListState {
    val mem = LocalScrollMemory.current
    val key = LocalScreenKey.current + "/" + name
    val saved = remember(key) { mem.lists[key] }
    val state = rememberLazyListState(saved?.first ?: 0, saved?.second ?: 0)
    DisposableEffect(key) {
        onDispose {
            // An empty list (still loading) says nothing about where the user was: keep the previous position.
            if (state.layoutInfo.totalItemsCount > 0) mem.lists[key] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        }
    }
    return state
}

@Composable
fun rememberKeptGridState(name: String = "main"): LazyGridState {
    val mem = LocalScrollMemory.current
    val key = LocalScreenKey.current + "/" + name
    val saved = remember(key) { mem.lists[key] }
    val state = rememberLazyGridState(saved?.first ?: 0, saved?.second ?: 0)
    DisposableEffect(key) {
        onDispose {
            if (state.layoutInfo.totalItemsCount > 0) mem.lists[key] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        }
    }
    return state
}

@Composable
fun rememberKeptScrollState(name: String = "main"): ScrollState {
    val mem = LocalScrollMemory.current
    val key = LocalScreenKey.current + "/" + name
    val state = rememberScrollState(remember(key) { mem.scrolls[key] ?: 0 })
    DisposableEffect(key) { onDispose { mem.scrolls[key] = state.value } }
    return state
}
