package com.mbk.halatuju.core.geocoding

import com.mbk.halatuju.core.model.AppInfo
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.ServiceEndpoints
import kotlinx.coroutines.delay
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
import kotlin.math.roundToLong

/**
 * Reverse-only geocoder backed by Nominatim (`https://nominatim.openstreetmap.org` default, configurable).
 * Enforces Nominatim's 1 request/second fair-use policy via a mutex gate and caches responses spatially.
 */
@Singleton
class NominatimGeocoder internal constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val minIntervalMs: Long,
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
        minIntervalMs = DEFAULT_MIN_INTERVAL_MS,
        clockMs = { System.nanoTime() / 1_000_000L },
        cacheTtlMs = DEFAULT_CACHE_TTL_MS,
        maxCacheEntries = DEFAULT_MAX_CACHE_ENTRIES
    )

    private data class CacheEntry(
        val expiresAtMs: Long,
        val place: Place?
    )

    private val cacheMutex = Mutex()
    private val cache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxCacheEntries
        }
    }

    private val rateLimitMutex = Mutex()
    private var lastRequestAtMs: Long = Long.MIN_VALUE

    suspend fun reverse(
        location: LatLng,
        baseUrl: String = ServiceEndpoints().nominatimBaseUrl
    ): Result<Place?> {
        val normalizedBase = baseUrl.trim().trimEnd('/')
        val spatialKey = buildSpatialKey(normalizedBase, location)
        val now = clockMs()

        cacheMutex.withLock {
            val existing = cache[spatialKey]
            if (existing != null && existing.expiresAtMs > now) {
                return Result.success(existing.place?.copy(location = location))
            }
        }

        val httpUrl = "$normalizedBase/reverse".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("format", "jsonv2")
            ?.addQueryParameter("lat", String.format(Locale.US, "%.6f", location.latitude))
            ?.addQueryParameter("lon", String.format(Locale.US, "%.6f", location.longitude))
            ?.addQueryParameter("zoom", "18")
            ?.addQueryParameter("addressdetails", "1")
            ?.build()
            ?: return Result.failure(IllegalArgumentException("Invalid Nominatim base URL: $baseUrl"))

        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", AppInfo.USER_AGENT)
            .header("Accept", "application/json")
            .header("Accept-Language", "en")
            .get()
            .build()

        // Acquire 1 req/s rate limit gate only for cache misses before hitting Nominatim
        awaitRateLimitSlot()

        return runCatching {
            okHttpClient.awaitExecute(request).use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Nominatim reverse failed with HTTP ${response.code}")
                }
                val body = response.body.string()
                val place = parseReverseResponse(body, requestedLocation = location)
                cacheMutex.withLock {
                    cache[spatialKey] = CacheEntry(
                        expiresAtMs = clockMs() + cacheTtlMs,
                        place = place
                    )
                }
                place
            }
        }
    }

    private suspend fun awaitRateLimitSlot() {
        rateLimitMutex.withLock {
            if (lastRequestAtMs != Long.MIN_VALUE) {
                val elapsed = clockMs() - lastRequestAtMs
                val waitMs = minIntervalMs - elapsed
                if (waitMs > 0L) {
                    delay(waitMs)
                }
            }
            lastRequestAtMs = clockMs()
        }
    }

    internal fun parseReverseResponse(rawJson: String, requestedLocation: LatLng): Place? {
        val dto = json.decodeFromString(NominatimReverseDto.serializer(), rawJson)
        if (!dto.error.isNullOrBlank()) return null

        val addr = dto.address
        val streetWithNumber = listOfNotNull(
            addr?.houseNumber?.takeIf { it.isNotBlank() },
            addr?.road?.takeIf { it.isNotBlank() }
        ).joinToString(" ").takeIf { it.isNotBlank() }

        val title = dto.name?.takeIf { it.isNotBlank() }
            ?: streetWithNumber
            ?: addr?.suburb?.takeIf { it.isNotBlank() }
            ?: addr?.quarter?.takeIf { it.isNotBlank() }
            ?: addr?.city?.takeIf { it.isNotBlank() }
            ?: dto.displayName?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() }
            ?: String.format(Locale.US, "%.5f, %.5f", requestedLocation.latitude, requestedLocation.longitude)

        val subtitleParts = listOfNotNull(
            addr?.road?.takeIf { it.isNotBlank() && !title.equals(it, ignoreCase = true) },
            addr?.quarter?.takeIf { it.isNotBlank() } ?: addr?.suburb?.takeIf { it.isNotBlank() },
            addr?.city?.takeIf { it.isNotBlank() }
                ?: addr?.town?.takeIf { it.isNotBlank() }
                ?: addr?.village?.takeIf { it.isNotBlank() },
            addr?.state?.takeIf { it.isNotBlank() },
            addr?.country?.takeIf { it.isNotBlank() }
        ).distinct().filterNot { it.equals(title, ignoreCase = true) }

        val subtitle = subtitleParts.joinToString(", ").ifBlank {
            dto.displayName?.substringAfter(",", "")?.trim().orEmpty()
        }

        val idSuffix = dto.placeId?.toString()
            ?: "${dto.osmType ?: "X"}:${dto.osmId ?: "${requestedLocation.latitude},${requestedLocation.longitude}"}"

        return Place(
            id = "nominatim:$idSuffix",
            name = title,
            subtitle = subtitle,
            location = requestedLocation
        )
    }

    private fun buildSpatialKey(baseUrl: String, location: LatLng): String {
        // ~28 meter quantization grid so slightly different finger taps near same block hit cache
        val qLat = (location.latitude * 4000.0).roundToLong()
        val qLon = (location.longitude * 4000.0).roundToLong()
        return "$baseUrl|$qLat,$qLon"
    }

    @Serializable
    private data class NominatimReverseDto(
        val error: String? = null,
        @SerialName("place_id") val placeId: Long? = null,
        @SerialName("osm_type") val osmType: String? = null,
        @SerialName("osm_id") val osmId: Long? = null,
        val name: String? = null,
        @SerialName("display_name") val displayName: String? = null,
        val address: NominatimAddressDto? = null
    )

    @Serializable
    private data class NominatimAddressDto(
        @SerialName("house_number") val houseNumber: String? = null,
        val road: String? = null,
        val suburb: String? = null,
        val quarter: String? = null,
        val city: String? = null,
        val town: String? = null,
        val village: String? = null,
        val state: String? = null,
        val country: String? = null
    )

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 1_050L
        const val DEFAULT_CACHE_TTL_MS = 30L * 60L * 1000L
        const val DEFAULT_MAX_CACHE_ENTRIES = 128
    }
}
