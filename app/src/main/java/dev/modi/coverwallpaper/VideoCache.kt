package dev.modi.coverwallpaper

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/** Keeps motion videos on the phone so each loop (and each replay of an album) doesn't use data again. */
object VideoCache {
    private const val MAX_BYTES = 300L * 1024 * 1024

    @Volatile
    private var cache: SimpleCache? = null

    private fun cache(ctx: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(ctx.cacheDir, "motion-art"),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(ctx),
            ).also { cache = it }
        }

    fun dataSourceFactory(ctx: Context): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache(ctx.applicationContext))
            .setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setUserAgent(AppleCatalog.USER_AGENT)
                    .setAllowCrossProtocolRedirects(true)
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}
