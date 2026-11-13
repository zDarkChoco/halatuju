package com.mbk.halatuju.core.geocoding

import com.mbk.halatuju.core.model.AppInfo
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.ServiceEndpoints
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Search-as-you-type geocoder backed by Photon (`https://photon.komoot.io` default, configurable).
 * Supports viewport/GPS location bias, LRU TTL caching, and proper User-Agent identification.
 */
@Singleton
class PhotonGeocoder internal constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val clockMs: () -> Long,
    private val cacheTtlMs: Long,
    private val maxCacheEntries: Int
) {

    @Inject
    constructor(
        @Named("geocoding") okHttpClient: OkHttpClient
    ) : this(
        okHttpClient = okHttpClient,
        json = Json { ignoreUnknownKeys = true },
        clockMs = { System.nanoTime() / 1_000_000L },
        cacheTtlMs = DEFAULT_CACHE_TTL_MS,
        maxCacheEntries = DEFAULT_MAX_CACHE_ENTRIES
    )

    private data class CacheEntry(
        val expiresAtMs: Long,
        val results: List<Place>
    )

    private val cacheMutex = Mutex()
    private val cache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxCacheEntries
        }
    }

    suspend fun search(
        query: String,
        bias: LatLng? = null,
        limit: Int = 8,
        baseUrl: String = ServiceEndpoints().photonBaseUrl
    ): Result<List<Place>> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return Result.success(emptyList())

        val coercedLimit = limit.coerceIn(1, 20)
        val normalizedBase = baseUrl.trim().trimEnd('/')
        val cacheKey = buildCacheKey(normalizedBase, trimmed, bias, coercedLimit)
        val now = clockMs()

        cacheMutex.withLock {
            val existing = cache[cacheKey]
            if (existing != null && existing.expiresAtMs > now) {
                return Result.success(existing.results)
            }
        }

        val httpUrl = "$normalizedBase/api".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", trimmed)
            ?.addQueryParameter("limit", coercedLimit.toString())
            ?.apply {
                if (bias != null) {
                    addQueryParameter("lat", String.format(Locale.US, "%.5f", bias.latitude))
                    addQueryParameter("lon", String.format(Locale.US, "%.5f", bias.longitude))
                }
            }
            ?.build()
            ?: return Result.failure(IllegalArgumentException("Invalid Photon base URL: $baseUrl"))

        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", AppInfo.USER_AGENT)
            .header("Accept", "application/geo+json, application/json")
            .get()
            .build()

        return runCatching {
            okHttpClient.awaitExecute(request).use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Photon search failed with HTTP ${response.code}")
                }
                val body = response.body.string()
                val parsed = parseFeatureCollection(body)
                cacheMutex.withLock {
                    cache[cacheKey] = CacheEntry(
                        expiresAtMs = clockMs() + cacheTtlMs,
                        results = parsed
                    )
                }
                parsed
            }
        }
    }

    internal fun parseFeatureCollection(rawJson: String): List<Place> {
        val collection = json.decodeFromString(PhotonFeatureCollection.serializer(), rawJson)
        val seenIds = HashSet<String>()
        val results = ArrayList<Place>(collection.features.size)

        for (feature in collection.features) {
            val coords = feature.geometry?.coordinates ?: continue
            if (coords.size < 2) continue
            val lon = coords[0]
            val lat = coords[1]
            val location = runCatching { LatLng(lat, lon) }.getOrNull() ?: continue
            val props = feature.properties ?: continue

            val streetWithNumber = listOfNotNull(
                props.housenumber?.takeIf { it.isNotBlank() },
                props.street?.takeIf { it.isNotBlank() }
            ).joinToString(" ").takeIf { it.isNotBlank() }

            val title = props.name?.takeIf { it.isNotBlank() }
                ?: streetWithNumber
                ?: props.locality?.takeIf { it.isNotBlank() }
                ?: props.district?.takeIf { it.isNotBlank() }
                ?: props.city?.takeIf { it.isNotBlank() }
                ?: props.country?.takeIf { it.isNotBlank() }
                ?: continue

            val subtitleParts = listOfNotNull(
                props.street?.takeIf { it.isNotBlank() && !title.equals(it, ignoreCase = true) },
                props.district?.takeIf { it.isNotBlank() } ?: props.locality?.takeIf { it.isNotBlank() },
                props.city?.takeIf { it.isNotBlank() },
                props.state?.takeIf { it.isNotBlank() },
                props.country?.takeIf { it.isNotBlank() }
            ).distinct().filterNot { it.equals(title, ignoreCase = true) }

            val stableId = "photon:${props.osmType ?: "X"}:${props.osmId ?: "${lat}_${lon}"}"
            if (!seenIds.add(stableId)) continue

            results += Place(
                id = stableId,
                name = title,
                subtitle = subtitleParts.joinToString(", "),
                location = location
            )
        }
        return results
    }

    private fun buildCacheKey(baseUrl: String, query: String, bias: LatLng?, limit: Int): String {
        val biasKey = if (bias != null) {
            val rLat = (bias.latitude * 100.0).roundToInt()
            val rLon = (bias.longitude * 100.0).roundToInt()
            "$rLat,$rLon"
        } else {
            "none"
        }
        return "$baseUrl|${query.lowercase(Locale.US)}|$biasKey|$limit"
    }

    @Serializable
    private data class PhotonFeatureCollection(
        val features: List<PhotonFeature> = emptyList()
    )

    @Serializable
    private data class PhotonFeature(
        val properties: PhotonProperties? = null,
        val geometry: PhotonGeometry? = null
    )

    @Serializable
    private data class PhotonGeometry(
        val coordinates: List<Double> = emptyList()
    )

    @Serializable
    private data class PhotonProperties(
        @SerialName("osm_type") val osmType: String? = null,
        @SerialName("osm_id") val osmId: Long? = null,
        val name: String? = null,
        val housenumber: String? = null,
        val street: String? = null,
        val locality: String? = null,
        val district: String? = null,
        val city: String? = null,
        val state: String? = null,
        val country: String? = null,
        val postcode: String? = null
    )

    companion object {
        const val DEFAULT_CACHE_TTL_MS = 15L * 60L * 1000L
        const val DEFAULT_MAX_CACHE_ENTRIES = 128
    }
}
