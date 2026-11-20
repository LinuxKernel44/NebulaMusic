package com.nebulamusic.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.nebulamusic.app.data.AppContainer

class NebulaApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /** Covers are cached on disk and served from there even when the server's cache headers say otherwise (offline use). */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(container.http)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(300L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .crossfade(false)
        .build()
}
