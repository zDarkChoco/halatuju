package com.mbk.halatuju.core.map

import android.content.Context
import com.mbk.halatuju.core.model.AppInfo
import com.mbk.halatuju.core.model.ServiceEndpoints
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface MapStyleSpec {
    data class StyleUrl(val url: String) : MapStyleSpec
    data class StyleJson(val json: String, val cacheKey: String) : MapStyleSpec
}

/**
 * Abstraction for map tile / style providers (OpenFreeMap default, Protomaps PMTiles, self-hosted).
 */
interface TileSource {
    fun resolveStyle(isDarkTheme: Boolean, endpoints: ServiceEndpoints): MapStyleSpec
}

@Singleton
class OpenFreeMapTileSource @Inject constructor(
    @ApplicationContext private val context: Context
) : TileSource {

    private val darkTemplateJson: String by lazy {
        context.assets.open(DARK_STYLE_ASSET).bufferedReader().use { it.readText() }
    }

    override fun resolveStyle(isDarkTheme: Boolean, endpoints: ServiceEndpoints): MapStyleSpec {
        return if (!isDarkTheme) {
            MapStyleSpec.StyleUrl(endpoints.lightStyleUrl.trim())
        } else {
            val tileUrl = endpoints.darkTileSourceUrl.trim()
            val resolvedJson = darkTemplateJson.replace(TILE_URL_PLACEHOLDER, tileUrl)
            MapStyleSpec.StyleJson(
                json = resolvedJson,
                cacheKey = "halatuju-dark:$tileUrl"
            )
        }
    }

    companion object {
        const val DARK_STYLE_ASSET = "halatuju_dark.json"
        const val TILE_URL_PLACEHOLDER = "__TILE_SOURCE_URL__"
    }
}

object MapHttpClientFactory {
    fun createTileHttpClient(cacheDir: File): OkHttpClient {
        val tileCacheDir = File(cacheDir, "maplibre_http_cache")
        return OkHttpClient.Builder()
            .cache(Cache(tileCacheDir, 64L * 1024L * 1024L))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", AppInfo.USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .build()
    }
}
