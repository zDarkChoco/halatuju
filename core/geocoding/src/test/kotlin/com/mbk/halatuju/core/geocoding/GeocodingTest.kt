package com.mbk.halatuju.core.geocoding

import com.mbk.halatuju.core.model.AppInfo
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class GeocodingTest {

    private lateinit var server: MockWebServer
    private lateinit var okHttpClient: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        okHttpClient = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `photon parses recorded KLCC fixture with lon-lat order, location bias and User-Agent`() = runTest {
        val fixture = loadFixture("fixtures/photon_klcc.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))

        val photon = PhotonGeocoder(okHttpClient)
        val geocoder = OpenOsmGeocoder(
            photonGeocoder = photon,
            nominatimGeocoder = NominatimGeocoder(okHttpClient)
        )

        val result = geocoder.search(
            query = "KLCC",
            bias = KlDefaults.KLCC,
            limit = 5,
            baseUrlOverride = server.url("/").toString()
        )

        assertTrue(result.isSuccess)
        val places = result.getOrThrow()
        assertEquals(5, places.size)
        val first = places.first()
        assertEquals("KLCC", first.name)
        assertEquals(3.1592469, first.location.latitude, 1e-6)
        assertEquals(101.7133662, first.location.longitude, 1e-6)
        assertTrue(first.subtitle.contains("Kuala Lumpur"))
        assertEquals("KLCC Convention Center", places.last().name)

        val req = server.takeRequest()
        assertEquals("/api", req.requestUrl?.encodedPath)
        assertEquals("KLCC", req.requestUrl?.queryParameter("q"))
        assertEquals("5", req.requestUrl?.queryParameter("limit"))
        assertEquals("3.15790", req.requestUrl?.queryParameter("lat"))
        assertEquals("101.71160", req.requestUrl?.queryParameter("lon"))
        assertEquals(AppInfo.USER_AGENT, req.getHeader("User-Agent"))
    }

    @Test
    fun `photon uses cache for repeated searches with small GPS jitter`() = runTest {
        val fixture = loadFixture("fixtures/photon_klcc.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))

        val photon = PhotonGeocoder(okHttpClient)
        val baseUrl = server.url("/").toString()

        val first = photon.search("  KLCC  ", bias = KlDefaults.KLCC, limit = 5, baseUrl = baseUrl)
        // Small ~50m bias shift stays inside 0.01 deg cache bucket
        val second = photon.search(
            "klcc",
            bias = LatLng(KlDefaults.KLCC.latitude + 0.0004, KlDefaults.KLCC.longitude + 0.0004),
            limit = 5,
            baseUrl = baseUrl
        )

        assertTrue(first.isSuccess)
        assertEquals(first.getOrThrow(), second.getOrThrow())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `photon ignores short queries without network call and errors cleanly on HTTP failure`() = runTest {
        val photon = PhotonGeocoder(okHttpClient)
        val baseUrl = server.url("/").toString()

        val tooShort = photon.search("k", bias = KlDefaults.KLCC, baseUrl = baseUrl)
        assertEquals(emptyList<Any>(), tooShort.getOrThrow())
        assertEquals(0, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(429).setBody("Too Many Requests"))
        val failure = photon.search("KL Sentral", bias = KlDefaults.KLCC, baseUrl = baseUrl)
        assertTrue(failure.isFailure)
    }

    @Test
    fun `nominatim parses KLCC reverse fixture, checks User-Agent, and handles road fallback & error payload`() = runTest {
        val fixture = loadFixture("fixtures/nominatim_reverse_klcc.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))

        val nominatim = NominatimGeocoder(
            okHttpClient = okHttpClient,
            json = Json { ignoreUnknownKeys = true },
            minIntervalMs = 0L,
            clockMs = { 0L },
            cacheTtlMs = 60_000L,
            maxCacheEntries = 32
        )
        val baseUrl = server.url("/").toString()

        val tapped = KlDefaults.KLCC
        val res = nominatim.reverse(tapped, baseUrl = baseUrl)
        assertTrue(res.isSuccess)
        val place = res.getOrThrow()!!
        assertEquals("McKinsey & Company;Ramlee Mall", place.name)
        assertTrue(place.subtitle.contains("Lorong Kuda"))
        assertTrue(place.subtitle.contains("Kuala Lumpur"))
        assertEquals(tapped, place.location)

        val req = server.takeRequest()
        assertEquals("/reverse", req.requestUrl?.encodedPath)
        assertEquals("jsonv2", req.requestUrl?.queryParameter("format"))
        assertEquals("18", req.requestUrl?.queryParameter("zoom"))
        assertEquals("1", req.requestUrl?.queryParameter("addressdetails"))
        assertEquals(AppInfo.USER_AGENT, req.getHeader("User-Agent"))

        // Error payload returns null cleanly rather than throwing
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"error":"Unable to geocode"}"""))
        val ocean = nominatim.reverse(LatLng(0.5, 95.0), baseUrl = baseUrl)
        assertTrue(ocean.isSuccess)
        assertNull(ocean.getOrThrow())

        // Blank name falls back to road name
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"place_id":99,"name":"","display_name":"Jalan Tun Razak, Kuala Lumpur, Malaysia","address":{"road":"Jalan Tun Razak","city":"Kuala Lumpur","country":"Malaysia"}}"""
            )
        )
        val roadOnly = nominatim.reverse(LatLng(3.165, 101.720), baseUrl = baseUrl)
        assertEquals("Jalan Tun Razak", roadOnly.getOrThrow()?.name)
    }

    @Test
    fun `nominatim spatial cache prevents duplicate network request for ~10m taps`() = runTest {
        val fixture = loadFixture("fixtures/nominatim_reverse_klcc.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))

        val nominatim = NominatimGeocoder(okHttpClient)
        val baseUrl = server.url("/").toString()

        val first = nominatim.reverse(KlDefaults.KLCC, baseUrl = baseUrl)
        val nearby = nominatim.reverse(
            LatLng(KlDefaults.KLCC.latitude + 0.00005, KlDefaults.KLCC.longitude - 0.00005),
            baseUrl = baseUrl
        )

        assertTrue(first.isSuccess)
        assertTrue(nearby.isSuccess)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `nominatim enforces at least 1 req per second spacing between uncached reverse requests`() {
        val fixture = loadFixture("fixtures/nominatim_reverse_klcc.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))

        val dispatchTimesMs = ArrayList<Long>()
        val countingClient = okHttpClient.newBuilder()
            .addInterceptor { chain ->
                dispatchTimesMs += System.nanoTime() / 1_000_000L
                chain.proceed(chain.request())
            }
            .build()

        val nominatim = NominatimGeocoder(countingClient)
        val baseUrl = server.url("/").toString()

        kotlinx.coroutines.runBlocking {
            val first = async { nominatim.reverse(KlDefaults.KLCC, baseUrl = baseUrl) }
            val second = async { nominatim.reverse(KlDefaults.KL_SENTRAL, baseUrl = baseUrl) }
            assertTrue(first.await().isSuccess)
            assertTrue(second.await().isSuccess)
        }

        assertEquals(2, server.requestCount)
        assertEquals(2, dispatchTimesMs.size)
        val deltaMs = abs(dispatchTimesMs[1] - dispatchTimesMs[0])
        assertTrue("Expected Nominatim interval >= 1000 ms, was $deltaMs ms", deltaMs >= 1000L)
    }

    @Test
    fun `photon and nominatim read HTTP response bodies on ioDispatcher rather than caller thread and rethrow cancellation`() {
        val photonFixture = loadFixture("fixtures/photon_klcc.json")
        val nominatimFixture = loadFixture("fixtures/nominatim_reverse_klcc.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(photonFixture))
        server.enqueue(MockResponse().setResponseCode(200).setBody(nominatimFixture))
        server.enqueue(MockResponse().setResponseCode(200).setBody(photonFixture).setHeadersDelay(5, TimeUnit.SECONDS))

        val bodyReadThreads = ArrayList<String>()
        val threadRecordingClient = okHttpClient.newBuilder()
            .addInterceptor { chain ->
                val resp = chain.proceed(chain.request())
                val rawBody = resp.body
                val wrapped = object : ResponseBody() {
                    override fun contentType(): MediaType? = rawBody.contentType()
                    override fun contentLength(): Long = rawBody.contentLength()
                    override fun source(): BufferedSource {
                        val delegate = rawBody.source()
                        return object : ForwardingSource(delegate) {
                            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                                synchronized(bodyReadThreads) {
                                    bodyReadThreads += Thread.currentThread().name
                                }
                                return super.read(sink, byteCount)
                            }
                        }.buffer()
                    }
                }
                resp.newBuilder().body(wrapped).build()
            }
            .build()

        val mainExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "Test-Fake-Main") }
        val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "Test-Geocoding-IO") }
        val mainDispatcher = mainExecutor.asCoroutineDispatcher()
        val ioDispatcher = ioExecutor.asCoroutineDispatcher()

        try {
            val photon = PhotonGeocoder(
                okHttpClient = threadRecordingClient,
                json = Json { ignoreUnknownKeys = true },
                clockMs = { System.nanoTime() / 1_000_000L },
                cacheTtlMs = 60_000L,
                maxCacheEntries = 32,
                ioDispatcher = ioDispatcher
            )
            val nominatim = NominatimGeocoder(
                okHttpClient = threadRecordingClient,
                json = Json { ignoreUnknownKeys = true },
                minIntervalMs = 0L,
                clockMs = { 0L },
                cacheTtlMs = 60_000L,
                maxCacheEntries = 32,
                ioDispatcher = ioDispatcher
            )
            val baseUrl = server.url("/").toString()

            kotlinx.coroutines.runBlocking {
                withContext(mainDispatcher) {
                    assertTrue(Thread.currentThread().name.startsWith("Test-Fake-Main"))
                    assertTrue(photon.search("KLCC", bias = KlDefaults.KLCC, baseUrl = baseUrl).isSuccess)
                    assertTrue(nominatim.reverse(KlDefaults.KLCC, baseUrl = baseUrl).isSuccess)
                }

                // Verify CancellationException is rethrown cleanly instead of being swallowed by Result.failure
                val cancellationCaught = AtomicReference<Throwable?>(null)
                val job = launch(mainDispatcher) {
                    try {
                        photon.search("Subang Jaya", bias = KlDefaults.KLCC, baseUrl = baseUrl)
                    } catch (ce: CancellationException) {
                        cancellationCaught.set(ce)
                        throw ce
                    }
                }
                delay(100L)
                job.cancel()
                job.join()
                assertTrue("Expected CancellationException to propagate", cancellationCaught.get() is CancellationException)
            }

            assertTrue(bodyReadThreads.size >= 2)
            assertTrue(
                "Expected all body reads on Test-Geocoding-IO, got $bodyReadThreads",
                bodyReadThreads.all { it.startsWith("Test-Geocoding-IO") }
            )
        } finally {
            mainExecutor.shutdownNow()
            ioExecutor.shutdownNow()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `search debouncer waits ~300ms for typed queries and clears blank inputs immediately`() = runTest {
        val rawQueries = MutableSharedFlow<String>()
        val emitted = ArrayList<String>()

        val job = launch {
            SearchDebouncer.debounceQueries(rawQueries, debounceMs = 300L).collect {
                emitted += it
            }
        }
        runCurrent()

        rawQueries.emit("KL")
        advanceTimeBy(150L)
        runCurrent()
        assertTrue("Should not emit before 300ms", emitted.isEmpty())

        rawQueries.emit("KLC")
        advanceTimeBy(150L)
        runCurrent()
        assertTrue("Rapid keystroke resets 300ms window", emitted.isEmpty())

        rawQueries.emit("KLCC")
        advanceTimeBy(299L)
        runCurrent()
        assertTrue("Still within 300ms debounce window", emitted.isEmpty())

        advanceTimeBy(2L)
        runCurrent()
        assertEquals(listOf("KLCC"), emitted)

        // Clearing query passes through without waiting 300ms
        rawQueries.emit("")
        runCurrent()
        assertEquals(listOf("KLCC", ""), emitted)

        job.cancel()
    }

    private fun loadFixture(path: String): String {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing test resource: $path"
        }
        return stream.bufferedReader().use { it.readText() }
    }
}
