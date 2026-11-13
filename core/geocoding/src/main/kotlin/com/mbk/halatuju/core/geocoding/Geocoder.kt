package com.mbk.halatuju.core.geocoding

import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.ServiceEndpoints
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Abstraction for forward search and reverse geocoding providers.
 */
interface Geocoder {
    suspend fun search(
        query: String,
        bias: LatLng? = null,
        limit: Int = 8,
        baseUrlOverride: String? = null
    ): Result<List<Place>>

    suspend fun reverse(
        location: LatLng,
        baseUrlOverride: String? = null
    ): Result<Place?>
}

object SearchDebouncer {
    const val DEFAULT_DEBOUNCE_MS = 300L

    /**
     * Debounces search-as-you-type queries to ~300 ms while immediately passing through
     * blank/cleared inputs so the UI clears suggestions right away.
     */
    @OptIn(FlowPreview::class)
    fun debounceQueries(
        queries: Flow<String>,
        debounceMs: Long = DEFAULT_DEBOUNCE_MS
    ): Flow<String> = queries
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce { query -> if (query.length < 2) 0L else debounceMs }
}

/**
 * Default HalaTuju [Geocoder] combining Photon (search-as-you-type with bias & cache)
 * and Nominatim (reverse geocoding with strict <= 1 req/s rate limiting & spatial cache).
 */
@Singleton
class OpenOsmGeocoder @Inject constructor(
    private val photonGeocoder: PhotonGeocoder,
    private val nominatimGeocoder: NominatimGeocoder
) : Geocoder {

    private val defaultEndpoints = ServiceEndpoints()

    override suspend fun search(
        query: String,
        bias: LatLng?,
        limit: Int,
        baseUrlOverride: String?
    ): Result<List<Place>> {
        val baseUrl = baseUrlOverride?.takeIf { it.isNotBlank() } ?: defaultEndpoints.photonBaseUrl
        return photonGeocoder.search(
            query = query,
            bias = bias,
            limit = limit,
            baseUrl = baseUrl
        )
    }

    override suspend fun reverse(
        location: LatLng,
        baseUrlOverride: String?
    ): Result<Place?> {
        val baseUrl = baseUrlOverride?.takeIf { it.isNotBlank() } ?: defaultEndpoints.nominatimBaseUrl
        return nominatimGeocoder.reverse(
            location = location,
            baseUrl = baseUrl
        )
    }
}

internal suspend fun OkHttpClient.awaitExecute(request: Request): Response {
    return suspendCancellableCoroutine { cont: CancellableContinuation<Response> ->
        val call = newCall(request)
        cont.invokeOnCancellation {
            runCatching { call.cancel() }
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) {
                    cont.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) {
                    cont.resume(response)
                } else {
                    response.close()
                }
            }
        })
    }
}
