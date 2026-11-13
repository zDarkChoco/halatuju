package com.mbk.halatuju.core.routing

import com.mbk.halatuju.core.model.AppInfo
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LaneIndication
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.ManeuverType
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.RoutingAvoidances
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RoutingTest {

    private lateinit var server: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var trafficProvider: KlPeakTrafficProvider

    private val klcc = Place("klcc", "KLCC", "Kuala Lumpur", KlDefaults.KLCC)
    private val klSentral = Place("kls", "KL Sentral", "Brickfields", KlDefaults.KL_SENTRAL)
    private val klia = Place("klia", "KLIA", "Sepang", KlDefaults.KLIA)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        okHttpClient = OkHttpClient.Builder().build()
        trafficProvider = KlPeakTrafficProvider()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `polyline6 codec round-trips Malaysia and southern-western hemisphere coordinates accurately`() {
        val original = listOf(
            KlDefaults.KLCC,
            KlDefaults.KL_SENTRAL,
            KlDefaults.KLIA,
            LatLng(-33.86882, 151.20929),
            LatLng(37.77493, -122.41942)
        )
        val encoded = PolylineCodec.encodePolyline6(original)
        val decoded = PolylineCodec.decodePolyline6(encoded)

        assertEquals(original.size, decoded.size)
        original.zip(decoded).forEach { (exp, act) ->
            assertEquals(exp.latitude, act.latitude, 1e-6)
            assertEquals(exp.longitude, act.longitude, 1e-6)
        }
    }

    @Test
    fun `valhalla router parses KLCC to KLIA alternates, lanes, maxspeed, exits and roundabout`() = runTest {
        val fixture = loadFixture("fixtures/valhalla_klcc_to_klia.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))

        val router = ValhallaRouter(okHttpClient, trafficProvider)
        // Monday 11:00 MYT (off-peak baseline)
        val offPeakMs = ZonedDateTime.of(2026, 5, 11, 11, 0, 0, 0, ZoneId.of("Asia/Kuala_Lumpur"))
            .toInstant().toEpochMilli()

        val res = router.calculateRoutes(
            RouteRequest(
                waypoints = listOf(klcc, klia),
                preferAlternates = true,
                departureEpochMs = offPeakMs,
                baseUrlOverride = server.url("/").toString()
            )
        )

        assertTrue(res.isSuccess)
        val routes = res.getOrThrow()
        assertEquals(3, routes.size)

        val primary = routes[0]
        assertEquals("E20, E6", primary.summaryLabel)
        assertEquals(965, primary.geometry.size)
        assertEquals(964, primary.legs[0].segmentMaxSpeeds.size)
        assertTrue(primary.trafficEstimated)

        // Verify both unknown and known speed limits preserved faithfully
        assertTrue(primary.legs[0].segmentMaxSpeeds.any { it.isUnknown })
        assertTrue(primary.legs[0].segmentMaxSpeeds.any { !it.isUnknown && it.speedKmh == 110 })

        // Verify lane guidance & exit shield separated cleanly (step 1 banner previews step 2's 1201B)
        assertEquals("1201B", primary.legs[0].steps[1].bannerInstructions.firstOrNull()?.shieldText)
        assertNull(primary.legs[0].steps[1].exits)

        val stepWithExit = primary.legs[0].steps.firstOrNull { it.exits == "1201B" }
        assertNotNull("Expected step with Exit 1201B", stepWithExit)
        assertTrue(stepWithExit!!.intersections.any { it.lanes.isNotEmpty() })

        val stepWithMultiLanes = primary.legs[0].steps[3]
        assertTrue(stepWithMultiLanes.intersections.any { ix ->
            ix.lanes.any { lane -> lane.indications.contains(LaneIndication.STRAIGHT) && lane.valid }
        })

        // Verify roundabout exit number parsed
        val roundabout = primary.legs[0].steps.firstOrNull { it.maneuverType == ManeuverType.ROUNDABOUT }
        assertNotNull(roundabout)
        assertEquals(2, roundabout!!.rotaryExitNumber)

        // Verify outbound Valhalla JSON request body & User-Agent
        val recorded = server.takeRequest()
        assertEquals("/route", recorded.requestUrl?.encodedPath)
        assertEquals(AppInfo.USER_AGENT, recorded.getHeader("User-Agent"))
        val sentJson = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("osrm", sentJson["format"]!!.jsonPrimitive.content)
        assertEquals("polyline6", sentJson["shape_format"]!!.jsonPrimitive.content)
        assertEquals(2, sentJson["alternates"]!!.jsonPrimitive.intOrNull)
        assertTrue(sentJson["banner_instructions"]!!.jsonPrimitive.booleanOrNull == true)
        assertTrue(sentJson["voice_instructions"]!!.jsonPrimitive.booleanOrNull == true)
        val attrs = sentJson["filters"]!!.jsonObject["attributes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("shape_attributes.speed_limit" in attrs)
    }

    @Test
    fun `valhalla router honors avoid tolls flag and parses no-toll fixture`() = runTest {
        val fixture = loadFixture("fixtures/valhalla_klcc_to_klia_notoll.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))

        val router = ValhallaRouter(okHttpClient, trafficProvider)
        val res = router.calculateRoutes(
            RouteRequest(
                waypoints = listOf(klcc, klia),
                avoidances = RoutingAvoidances(avoidTolls = true, avoidFerries = true),
                baseUrlOverride = server.url("/").toString()
            )
        )

        assertTrue(res.isSuccess)
        val routes = res.getOrThrow()
        assertEquals(2, routes.size)
        assertTrue(routes[0].summaryLabel.contains("B48") || routes[0].summaryLabel.contains("Seremban"))

        val recorded = server.takeRequest()
        val sentAuto = Json.parseToJsonElement(recorded.body.readUtf8())
            .jsonObject["costing_options"]!!.jsonObject["auto"]!!.jsonObject
        assertEquals(true, sentAuto["exclude_tolls"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(true, sentAuto["exclude_ferries"]!!.jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `valhalla router parses multi-stop 2-leg route, sends heading & date_time on reroute, and caches identical queries`() = runTest {
        val fixture = loadFixture("fixtures/valhalla_klcc_klsentral_klia.json")
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))

        val router = ValhallaRouter(okHttpClient, trafficProvider)
        val baseUrl = server.url("/").toString()

        val req = RouteRequest(
            waypoints = listOf(klcc, klSentral, klia),
            preferAlternates = false,
            baseUrlOverride = baseUrl
        )
        val first = router.calculateRoutes(req).getOrThrow()
        val cached = router.calculateRoutes(req).getOrThrow()

        assertEquals(1, server.requestCount)
        assertEquals(first.size, cached.size)

        val route = first[0]
        assertEquals(2, route.legs.size)
        assertEquals(1222, route.geometry.size)
        assertEquals(0, route.legs[0].segmentStartGeometryIndex)
        assertEquals(291, route.legs[0].steps.last().geometryEndIndex)
        assertEquals(291, route.legs[1].segmentStartGeometryIndex)
        assertEquals(291, route.legs[1].steps.first().geometryStartIndex)
        assertEquals(1221, route.legs[1].steps.last().geometryEndIndex)

        val body1 = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertNotNull("date_time expected on single-route / multi-stop request", body1["date_time"])

        // With vehicleHeadingDegrees present, cache is bypassed and heading is sent for reroute
        val reroute = router.calculateRoutes(req.copy(vehicleHeadingDegrees = 301.0))
        assertTrue(reroute.isSuccess)
        assertEquals(2, server.requestCount)
        val rerouteJson = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val firstLoc = rerouteJson["locations"]!!.jsonArray[0].jsonObject
        assertEquals(301, firstLoc["heading"]!!.jsonPrimitive.intOrNull)
    }

    @Test
    fun `kl peak traffic provider computes smooth peak ramp, off-peak baseline, and live adaptation`() = runTest {
        val klRoute = NavRoute(
            id = "test_kl",
            geometry = listOf(KlDefaults.KLCC, KlDefaults.KLIA),
            cumulativeDistancesMeters = listOf(0.0, 50_000.0),
            totalDistanceMeters = 50_000.0,
            baseDurationSeconds = 3_600.0,
            adjustedDurationSeconds = 3_600.0,
            trafficEstimated = false,
            legs = emptyList(),
            waypoints = listOf(klcc, klia)
        )

        val klZone = ZoneId.of("Asia/Kuala_Lumpur")
        val morningPeakMs = ZonedDateTime.of(2026, 5, 11, 8, 15, 0, 0, klZone).toInstant().toEpochMilli()
        val rampMidMs = ZonedDateTime.of(2026, 5, 11, 16, 50, 0, 0, klZone).toInstant().toEpochMilli()
        val eveningPeakMs = ZonedDateTime.of(2026, 5, 11, 18, 30, 0, 0, klZone).toInstant().toEpochMilli()
        val sundayMs = ZonedDateTime.of(2026, 5, 10, 18, 30, 0, 0, klZone).toInstant().toEpochMilli()

        val morning = trafficProvider.estimateRouteEta(klRoute, morningPeakMs)
        val rampMid = trafficProvider.estimateRouteEta(klRoute, rampMidMs)
        val evening = trafficProvider.estimateRouteEta(klRoute, eveningPeakMs)
        val sunday = trafficProvider.estimateRouteEta(klRoute, sundayMs)

        assertTrue(morning.isEstimated)
        assertTrue(morning.congestionFactor > 1.25)
        assertTrue(morning.label.contains("estimated"))
        assertTrue(rampMid.congestionFactor > sunday.congestionFactor && rampMid.congestionFactor < evening.congestionFactor)
        assertTrue(evening.congestionFactor > 1.30)
        assertTrue(sunday.congestionFactor < 1.05)

        // Live slow traffic pace (observed = 600s vs expected = 400s after 2.4km) lifts remaining ETA above prior
        val adaptedSlow = trafficProvider.adaptRemainingEta(
            route = klRoute,
            distanceAlongRouteMeters = 2_400.0,
            baseRemainingSeconds = 3_000.0,
            traveledObservedSeconds = 600.0,
            traveledExpectedSeconds = 400.0,
            departureTimeEpochMs = sundayMs
        )
        assertTrue(adaptedSlow.adjustedDurationSeconds > 3_500.0)
        assertTrue(adaptedSlow.label.contains("estimated"))

        // Route outside Malaysia does not apply KL peak curves
        val londonRoute = klRoute.copy(
            geometry = listOf(LatLng(51.5074, -0.1278), LatLng(51.4700, -0.4543)),
            waypoints = listOf(
                Place("lon1", "London", "", LatLng(51.5074, -0.1278)),
                Place("lon2", "Heathrow", "", LatLng(51.4700, -0.4543))
            )
        )
        val londonEst = trafficProvider.estimateRouteEta(londonRoute, eveningPeakMs)
        assertEquals(1.0, londonEst.congestionFactor, 1e-6)
        assertFalse(londonEst.label.contains("peak"))
    }

    private fun loadFixture(path: String): String {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing test resource: $path"
        }
        return stream.bufferedReader().use { it.readText() }
    }
}
