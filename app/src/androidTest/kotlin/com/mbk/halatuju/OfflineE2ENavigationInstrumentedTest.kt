package com.mbk.halatuju

import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToLog
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.mbk.halatuju.core.location.LocationSourceCoordinator
import com.mbk.halatuju.core.location.RouteSimulationController
import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.LocationProviderKind
import com.mbk.halatuju.core.model.ServiceEndpoints
import com.mbk.halatuju.core.navigation.NavigationSessionManager
import com.mbk.halatuju.core.navigation.NavigationStatus
import com.mbk.halatuju.core.routing.PolylineCodec
import com.mbk.halatuju.core.storage.PlaceRepository
import com.mbk.halatuju.core.storage.SettingsStore
import com.mbk.halatuju.feature.navigation.NavigationNotificationFactory
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Full offline end-to-end instrumented journey backed by a local loopback [MockWebServer] and
 * [RouteSimulationController]:
 *   launch -> Photon search ("KLIA") -> Preview -> Add stop ("KL Sentral") -> multi-stop route ->
 *   Start navigation -> live lane strip + speed badge + OS Live Update / ongoing notification ->
 *   Intermediate stop arrival ("Continue") -> Off-route detour injection & Valhalla reroute ->
 *   Destination arrival ("Done") -> clean return to IDLE and notification removal.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OfflineE2ENavigationInstrumentedTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val permissionsRule = RuntimePermissionsRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var sessionManager: NavigationSessionManager

    @Inject
    lateinit var locationSourceCoordinator: LocationSourceCoordinator

    @Inject
    lateinit var simulationController: RouteSimulationController

    @Inject
    lateinit var placeRepository: PlaceRepository

    private lateinit var mockWebServer: MockWebServer

    private val styleRequestCount = AtomicInteger(0)
    private val routeRequestCount = AtomicInteger(0)
    private val rerouteRequestCount = AtomicInteger(0)
    private val nonRerouteRoutesDuringNav = AtomicInteger(0)
    private val activeNavigationEntered = AtomicBoolean(false)
    private val sawHeadingOnReroute = AtomicBoolean(false)

    private val originKlcc = KlDefaults.KLCC
    private val stopSentral = GeoMath.fromLocalEnMeters(originKlcc, eastMeters = 280.0, northMeters = 0.0)
    private val destKlia = GeoMath.fromLocalEnMeters(originKlcc, eastMeters = 1_400.0, northMeters = 0.0)

    @Before
    fun setUp() {
        hiltRule.inject()
        styleRequestCount.set(0)
        routeRequestCount.set(0)
        rerouteRequestCount.set(0)
        nonRerouteRoutesDuringNav.set(0)
        activeNavigationEntered.set(false)
        sawHeadingOnReroute.set(false)

        mockWebServer = MockWebServer()
        mockWebServer.dispatcher = OfflineMockDispatcher()
        mockWebServer.start(InetAddress.getByName("127.0.0.1"), 0)
        val baseUrl = "http://127.0.0.1:${mockWebServer.port}"
        Log.i("HalaTujuE2ETest", "MockWebServer started at $baseUrl")

        runBlocking {
            sessionManager.endNavigation()
            simulationController.clearFaults()
            placeRepository.clearRecents()
            settingsStore.updateEndpoints(
                ServiceEndpoints(
                    lightStyleUrl = "$baseUrl/style.json",
                    darkTileSourceUrl = "$baseUrl/tiles.json",
                    valhallaBaseUrl = baseUrl,
                    photonBaseUrl = baseUrl,
                    nominatimBaseUrl = baseUrl
                )
            )
            settingsStore.updateSimulationEnabled(true)
            settingsStore.updateSimulationSpeedMultiplier(2.0f)
            settingsStore.updateSimulationNoiseEnabled(true)
            settingsStore.updateDeveloperDebugOverlayEnabled(true)
            settingsStore.settings.first {
                it.simulationEnabled && it.endpoints.valhallaBaseUrl == baseUrl
            }
            locationSourceCoordinator.hasActiveOverride.first { it }
            locationSourceCoordinator.recordLatestFix(
                LocationFix(
                    position = originKlcc,
                    accuracyMeters = 6f,
                    speedMps = 0f,
                    bearingDegrees = 90f,
                    timestampMs = System.currentTimeMillis(),
                    provider = LocationProviderKind.SIMULATED
                )
            )
        }
    }

    @After
    fun tearDown() {
        if (::sessionManager.isInitialized) {
            runCatching { sessionManager.endNavigation() }
        }
        if (::simulationController.isInitialized) {
            runCatching { simulationController.clearFaults() }
        }
        if (::settingsStore.isInitialized) {
            runBlocking {
                runCatching {
                    settingsStore.updateEndpoints(ServiceEndpoints())
                    settingsStore.updateSimulationEnabled(false)
                    settingsStore.updateSimulationSpeedMultiplier(1.0f)
                    settingsStore.updateSimulationNoiseEnabled(true)
                    settingsStore.updateDeveloperDebugOverlayEnabled(false)
                }
            }
        }
        if (::placeRepository.isInitialized) {
            runBlocking { runCatching { placeRepository.clearRecents() } }
        }
        runCatching {
            val manager = InstrumentationRegistry.getInstrumentation().targetContext
                .getSystemService(NotificationManager::class.java)
            manager.cancel(NavigationNotificationFactory.NOTIFICATION_ID)
        }
        if (::mockWebServer.isInitialized) {
            runCatching { mockWebServer.shutdown() }
        }
    }

    @Test
    fun fullMultiStopSimulatedNavigationWithRerouteAndArrival() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        CiArtifactCapture.prepareDir(device)

        try {
            composeRule.waitForIdle()
            composeRule.onNodeWithText("© OpenStreetMap contributors").assertIsDisplayed()
            composeRule.onNodeWithTag("home_search_card").assertIsDisplayed()
            assertTrue(
                "Expected MapLibre to request mock /style.json on Home screen",
                waitUntil(timeoutMs = 10_000L) { styleRequestCount.get() >= 1 }
            )

            // 1. Search destination via local mock Photon server (debounced 300ms)
            focusAndTypeSearchQuery(tag = "search_to_input", query = "KLIA")

            composeRule.waitUntil(timeoutMillis = 8_000L) {
                composeRule.onAllNodesWithText("KLIA Terminal 1").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodesWithText("KLIA Terminal 1").onFirst().performClick()

            // 2. Verify multi-alternative route preview + estimated traffic badge
            composeRule.waitUntil(timeoutMillis = 8_000L) {
                composeRule.onAllNodesWithTag("route_preview_sheet").fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodesWithTag("alternative_routes_strip").fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodes(hasTestTag("start_navigation_button") and isEnabled())
                        .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("route_traffic_label").assertIsDisplayed()
            composeRule.waitForIdle()
            CiArtifactCapture.captureScreenshot(device, "ci_03_e2e_route_preview.png")

            // 3. Add intermediate stop ("KL Sentral") and trigger automatic multi-leg reroute
            composeRule.onNodeWithTag("add_stop_button").performClick()
            focusAndTypeSearchQuery(tag = "search_stop_input", query = "Sentral", alreadyFocusedClick = false)
            composeRule.waitUntil(timeoutMillis = 8_000L) {
                composeRule.onAllNodesWithText("KL Sentral").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodesWithText("KL Sentral").onFirst().performClick()

            composeRule.waitUntil(timeoutMillis = 8_000L) {
                routeRequestCount.get() >= 2 &&
                    composeRule.onAllNodesWithTag("intermediate_stops_section").fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodesWithTag("alternative_routes_strip").fetchSemanticsNodes().isEmpty() &&
                    sessionManager.state.value.status == NavigationStatus.IDLE &&
                    composeRule.onAllNodesWithText("1 stop").fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodes(hasTestTag("start_navigation_button") and isEnabled())
                        .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.waitForIdle()
            activeNavigationEntered.set(true)
            composeRule.onNodeWithTag("start_navigation_button").assertIsEnabled().performClick()

            // 4. Verify active turn-by-turn UI, lane guidance, speed badge, debug overlay & OS notification
            composeRule.waitUntil(timeoutMillis = 10_000L) {
                composeRule.onAllNodesWithTag("active_navigation_screen").fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodesWithTag("nav_lane_guidance_strip").fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodesWithTag("nav_speed_limit_badge").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("nav_maneuver_banner").assertIsDisplayed()
            composeRule.onNodeWithTag("nav_end_button").assertIsDisplayed()
            composeRule.onNodeWithText("© OpenStreetMap contributors").assertIsDisplayed()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                val attr = composeRule.onNodeWithTag("osm_attribution").getBoundsInRoot()
                val bottomBar = composeRule.onNodeWithTag("nav_bottom_bar").getBoundsInRoot()
                attr.bottom.value <= bottomBar.top.value + 0.5f ||
                    attr.left.value >= bottomBar.right.value - 0.5f
            }
            val attributionBounds = composeRule.onNodeWithTag("osm_attribution").getBoundsInRoot()
            val bottomBarBounds = composeRule.onNodeWithTag("nav_bottom_bar").getBoundsInRoot()
            val speedClusterBounds = composeRule.onNodeWithTag("nav_speed_cluster").getBoundsInRoot()
            assertTrue(
                "Attribution ($attributionBounds) must not overlap nav_bottom_bar ($bottomBarBounds)",
                attributionBounds.bottom.value <= bottomBarBounds.top.value + 0.5f ||
                    bottomBarBounds.bottom.value <= attributionBounds.top.value + 0.5f ||
                    attributionBounds.left.value >= bottomBarBounds.right.value - 0.5f
            )
            assertTrue(
                "Attribution ($attributionBounds) must not overlap nav_speed_cluster ($speedClusterBounds)",
                attributionBounds.bottom.value <= speedClusterBounds.top.value + 0.5f ||
                    speedClusterBounds.bottom.value <= attributionBounds.top.value + 0.5f ||
                    attributionBounds.right.value <= speedClusterBounds.left.value + 0.5f ||
                    speedClusterBounds.right.value <= attributionBounds.left.value + 0.5f
            )

            val activeNotif = waitUntilNotNull(timeoutMs = 10_000L) {
                notificationManager.activeNotifications.firstOrNull {
                    it.id == NavigationNotificationFactory.NOTIFICATION_ID
                }
            }
            assertNotNull("Expected active navigation OS notification during simulated drive", activeNotif)
            if (Build.VERSION.SDK_INT >= 36) {
                assertTrue(NotificationCompat.isRequestPromotedOngoing(activeNotif!!.notification))
            }
            CiArtifactCapture.captureScreenshot(device, "ci_04_e2e_active_nav.png")

            // 5. Wait for simulated arrival at intermediate Stop 1 and continue to Leg 2
            composeRule.waitUntil(timeoutMillis = 25_000L) {
                composeRule.onAllNodesWithTag("nav_stop_arrival_card").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Arrived at stop 1").assertIsDisplayed()
            CiArtifactCapture.captureScreenshot(device, "ci_05_e2e_stop_arrival.png")
            composeRule.onNodeWithTag("nav_continue_stop_button").performClick()

            composeRule.waitUntil(timeoutMillis = 8_000L) {
                sessionManager.state.value.status == NavigationStatus.ACTIVE &&
                    sessionManager.state.value.currentLegIndex == 1 &&
                    composeRule.onAllNodesWithTag("debug_inject_offroute_button").fetchSemanticsNodes().isNotEmpty()
            }

            // 6. Trigger real off-route detour button on Debug Overlay -> wait for reroute call with heading
            val offrouteButton = composeRule.onNodeWithTag("debug_inject_offroute_button")
            runCatching { offrouteButton.performScrollTo() }
            offrouteButton.performClick()
            composeRule.waitUntil(timeoutMillis = 22_000L) {
                val st = sessionManager.state.value
                rerouteRequestCount.get() >= 1 &&
                    sawHeadingOnReroute.get() &&
                    st.route?.legs?.size == 1 &&
                    !st.isRerouting &&
                    !st.debugOverlayData.isOffRoute &&
                    st.debugOverlayData.snappedPosition != null &&
                    st.distanceAlongRouteMeters > 0.0
            }
            composeRule.waitForIdle()
            Thread.sleep(350L)
            CiArtifactCapture.captureScreenshot(device, "ci_06_e2e_post_reroute.png")

            // 7. Wait for arrival at final destination ("You have arrived") -> tap Done -> clean IDLE
            composeRule.waitUntil(timeoutMillis = 45_000L) {
                composeRule.onAllNodesWithTag("nav_destination_arrival_card").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("You have arrived").assertIsDisplayed()
            CiArtifactCapture.captureScreenshot(device, "ci_07_e2e_dest_arrival.png")
            composeRule.onNodeWithTag("nav_done_button").performClick()

            val endedCleanly = waitUntil(timeoutMs = 8_000L) {
                sessionManager.state.value.status == NavigationStatus.IDLE &&
                    notificationManager.activeNotifications.none {
                        it.id == NavigationNotificationFactory.NOTIFICATION_ID
                    }
            }
            assertTrue("Expected Done button to return navigation to IDLE and clear notification", endedCleanly)
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithTag("home_search_card").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.waitForIdle()
            assertEquals(
                "Expected zero non-reroute /route requests while active navigation ran",
                0,
                nonRerouteRoutesDuringNav.get()
            )
        } catch (t: Throwable) {
            CiArtifactCapture.captureScreenshot(device, "ci_e2e_failure.png")
            runCatching { composeRule.onRoot(useUnmergedTree = true).printToLog("E2E_FAILURE_TREE") }
            throw t
        }
    }

    private fun focusAndTypeSearchQuery(
        tag: String,
        query: String,
        alreadyFocusedClick: Boolean = true
    ) {
        if (alreadyFocusedClick) {
            composeRule.onNodeWithTag(tag).performClick()
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("search_focused_panel").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithTag(tag).performClick()
            composeRule.waitUntil(timeoutMillis = 3_000L) {
                composeRule.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(tag).performTextInput(query)
        composeRule.waitForIdle()
        if (composeRule.onAllNodes(hasTestTag(tag) and hasText(query)).fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithTag(tag).performTextReplacement(query)
            composeRule.waitForIdle()
        }
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(150L)
        }
        return predicate()
    }

    private fun <T> waitUntilNotNull(timeoutMs: Long, producer: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            producer()?.let { return it }
            Thread.sleep(150L)
        }
        return producer()
    }

    private inner class OfflineMockDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            return runCatching {
                val path = request.requestUrl?.encodedPath.orEmpty()
                Log.i("HalaTujuE2EMock", "${request.method} ${request.requestUrl}")
                when {
                    path.endsWith("/style.json") -> {
                        styleRequestCount.incrementAndGet()
                        jsonResponse(minimalOfflineStyleJson())
                    }
                    path.endsWith("/tiles.json") -> jsonResponse(minimalTileJson())
                    path.startsWith("/api") -> handlePhotonSearch(request.requestUrl?.queryParameter("q").orEmpty())
                    path.startsWith("/reverse") -> jsonResponse(nominatimReverseJson())
                    path.startsWith("/route") -> handleValhallaRoute(request.body.readUtf8())
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"Not found: $path"}""")
                }
            }.getOrElse { err ->
                Log.e("HalaTujuE2EMock", "Dispatcher failure for ${request.requestUrl}", err)
                MockResponse().setResponseCode(500).setBody("""{"error":"${err.message ?: "internal"}"}""")
            }
        }

        private fun handlePhotonSearch(query: String): MockResponse {
            val q = query.lowercase()
            val features = JSONArray()
            if (q.contains("klia") || q.contains("sepang")) {
                features.put(
                    photonFeature(
                        osmId = 7001L,
                        name = "KLIA Terminal 1",
                        street = "Lebuhraya KLIA",
                        city = "Sepang",
                        pos = destKlia
                    )
                )
            }
            if (q.contains("sentral") || q == "kl") {
                features.put(
                    photonFeature(
                        osmId = 7002L,
                        name = "KL Sentral",
                        street = "Jalan Tun Sambanthan",
                        city = "Kuala Lumpur",
                        pos = stopSentral
                    )
                )
            }
            return jsonResponse(
                JSONObject()
                    .put("type", "FeatureCollection")
                    .put("features", features)
                    .toString()
            )
        }

        private fun handleValhallaRoute(rawBody: String): MockResponse {
            routeRequestCount.incrementAndGet()
            val req = JSONObject(rawBody)
            val locations = req.getJSONArray("locations")
            val pts = (0 until locations.length()).map { idx ->
                val obj = locations.getJSONObject(idx)
                LatLng(obj.getDouble("lat"), obj.getDouble("lon"))
            }
            val firstLoc = locations.getJSONObject(0)
            val isReroute = firstLoc.has("heading") && pts.size == 2

            return if (isReroute) {
                rerouteRequestCount.incrementAndGet()
                sawHeadingOnReroute.set(true)
                val origin = pts[0]
                val dest = pts[1]
                jsonResponse(buildSingleLegValhallaResponse(origin, dest, includeAlternative = false))
            } else {
                if (activeNavigationEntered.get()) {
                    nonRerouteRoutesDuringNav.incrementAndGet()
                }
                if (pts.size >= 3) {
                    jsonResponse(buildTwoLegValhallaResponse(pts[0], pts[1], pts[2]))
                } else {
                    jsonResponse(buildSingleLegValhallaResponse(pts[0], pts.last(), includeAlternative = true))
                }
            }
        }
    }

    private fun buildSingleLegValhallaResponse(
        origin: LatLng,
        dest: LatLng,
        includeAlternative: Boolean
    ): String {
        val mid1 = GeoMath.interpolateLatLng(origin, dest, 0.33)
        val mid2 = GeoMath.interpolateLatLng(origin, dest, 0.67)
        val routePts = listOf(origin, mid1, mid2, dest)
        val totalDist = GeoMath.haversineMeters(origin, dest).coerceAtLeast(120.0)
        val dEdge = totalDist / 3.0
        val dur = (totalDist / 16.0).coerceAtLeast(15.0)
        val route0 = buildRouteJson(
            fullGeom = routePts,
            totalDist = totalDist,
            totalDur = dur,
            legs = listOf(
                LegSpec(
                    summary = "Jalan Ampang → Lebuhraya KLIA",
                    points = routePts,
                    speedLimitKmh = 70,
                    edgeDist = dEdge,
                    edgeDur = dur / 3.0,
                    exitShield = "608",
                    firstRoad = "Jalan Ampang",
                    turnRoad = "Lebuhraya KLIA"
                )
            )
        )

        val routes = JSONArray().put(route0)
        if (includeAlternative) {
            val detourMid = GeoMath.fromLocalEnMeters(
                GeoMath.interpolateLatLng(origin, dest, 0.5),
                eastMeters = 0.0,
                northMeters = 90.0
            )
            val altPts = listOf(origin, detourMid, dest)
            routes.put(
                buildRouteJson(
                    fullGeom = altPts,
                    totalDist = totalDist * 1.15,
                    totalDur = dur * 1.2,
                    legs = listOf(
                        LegSpec(
                            summary = "AKLEH",
                            points = listOf(origin, detourMid, GeoMath.interpolateLatLng(detourMid, dest, 0.5), dest),
                            speedLimitKmh = 60,
                            edgeDist = (totalDist * 1.15) / 3.0,
                            edgeDur = (dur * 1.2) / 3.0,
                            exitShield = null,
                            firstRoad = "Jalan Ampang",
                            turnRoad = "AKLEH"
                        )
                    )
                )
            )
        }
        return JSONObject().put("code", "Ok").put("routes", routes).toString()
    }

    private fun buildTwoLegValhallaResponse(
        origin: LatLng,
        stop: LatLng,
        dest: LatLng
    ): String {
        val leg0Pts = listOf(
            origin,
            GeoMath.interpolateLatLng(origin, stop, 0.34),
            GeoMath.interpolateLatLng(origin, stop, 0.67),
            stop
        )
        val leg1Pts = listOf(
            stop,
            GeoMath.interpolateLatLng(stop, dest, 0.25),
            GeoMath.interpolateLatLng(stop, dest, 0.50),
            GeoMath.interpolateLatLng(stop, dest, 0.75),
            dest
        )
        val fullGeom = leg0Pts + leg1Pts.drop(1)
        val leg0Dist = GeoMath.haversineMeters(origin, stop).coerceAtLeast(100.0)
        val leg1Dist = GeoMath.haversineMeters(stop, dest).coerceAtLeast(250.0)
        val leg0Dur = leg0Dist / 13.9
        val leg1Dur = leg1Dist / 16.7

        val route0 = buildRouteJson(
            fullGeom = fullGeom,
            totalDist = leg0Dist + leg1Dist,
            totalDur = leg0Dur + leg1Dur,
            legs = listOf(
                LegSpec(
                    summary = "Jalan Ampang → KL Sentral",
                    points = leg0Pts,
                    speedLimitKmh = 50,
                    edgeDist = leg0Dist / 3.0,
                    edgeDur = leg0Dur / 3.0,
                    exitShield = "1",
                    firstRoad = "Jalan Ampang",
                    turnRoad = "Jalan Tun Sambanthan"
                ),
                LegSpec(
                    summary = "Lebuhraya Mahameru → Lebuhraya KLIA",
                    points = leg1Pts,
                    speedLimitKmh = 60,
                    edgeDist = leg1Dist / 4.0,
                    edgeDur = leg1Dur / 4.0,
                    exitShield = "608",
                    firstRoad = "Lebuhraya Mahameru",
                    turnRoad = "Lebuhraya KLIA"
                )
            )
        )
        return JSONObject().put("code", "Ok").put("routes", JSONArray().put(route0)).toString()
    }

    private data class LegSpec(
        val summary: String,
        val points: List<LatLng>,
        val speedLimitKmh: Int,
        val edgeDist: Double,
        val edgeDur: Double,
        val exitShield: String?,
        val firstRoad: String,
        val turnRoad: String
    )

    private fun buildRouteJson(
        fullGeom: List<LatLng>,
        totalDist: Double,
        totalDur: Double,
        legs: List<LegSpec>
    ): JSONObject {
        val legsJson = JSONArray()
        legs.forEach { leg ->
            val edgeCount = leg.points.size - 1
            val maxSpeeds = JSONArray()
            val distances = JSONArray()
            val durations = JSONArray()
            repeat(edgeCount) {
                maxSpeeds.put(JSONObject().put("speed", leg.speedLimitKmh).put("unit", "km/h"))
                distances.put(leg.edgeDist)
                durations.put(leg.edgeDur)
            }

            val splitEdge = (edgeCount / 2).coerceAtLeast(1)
            val step0Pts = leg.points.subList(0, splitEdge + 1)
            val step1Pts = leg.points.subList(splitEdge, leg.points.size)
            val destPt = leg.points.last()

            val sampleLanes = JSONArray()
                .put(JSONObject().put("indications", JSONArray().put("straight")).put("valid", false))
                .put(JSONObject().put("indications", JSONArray().put("straight").put("right")).put("valid", true))

            val step0 = JSONObject()
                .put("geometry", PolylineCodec.encodePolyline6(step0Pts))
                .put("distance", leg.edgeDist * splitEdge)
                .put("duration", leg.edgeDur * splitEdge)
                .put("name", leg.firstRoad)
                .put(
                    "maneuver",
                    JSONObject()
                        .put("type", "depart")
                        .put("modifier", "straight")
                        .put("instruction", "Head east on ${leg.firstRoad}")
                        .put("bearing_before", 90.0)
                        .put("bearing_after", 90.0)
                        .put("location", JSONArray().put(step0Pts.first().longitude).put(step0Pts.first().latitude))
                )
                .put(
                    "intersections",
                    JSONArray().put(
                        JSONObject()
                            .put("location", JSONArray().put(step0Pts.first().longitude).put(step0Pts.first().latitude))
                            .put("geometry_index", 0)
                            .put("lanes", sampleLanes)
                    )
                )

            val step1 = JSONObject()
                .put("geometry", PolylineCodec.encodePolyline6(step1Pts))
                .put("distance", leg.edgeDist * (edgeCount - splitEdge))
                .put("duration", leg.edgeDur * (edgeCount - splitEdge))
                .put("name", leg.turnRoad)
                .apply {
                    leg.exitShield?.let { put("exits", it) }
                }
                .put(
                    "maneuver",
                    JSONObject()
                        .put("type", "turn")
                        .put("modifier", "right")
                        .put("instruction", "Turn right onto ${leg.turnRoad}")
                        .put("bearing_before", 90.0)
                        .put("bearing_after", 90.0)
                        .put("location", JSONArray().put(step1Pts.first().longitude).put(step1Pts.first().latitude))
                )
                .put(
                    "intersections",
                    JSONArray().put(
                        JSONObject()
                            .put("location", JSONArray().put(step1Pts.first().longitude).put(step1Pts.first().latitude))
                            .put("geometry_index", splitEdge)
                            .put("lanes", sampleLanes)
                    )
                )

            val stepArrive = JSONObject()
                .put("geometry", PolylineCodec.encodePolyline6(listOf(destPt)))
                .put("distance", 0.0)
                .put("duration", 0.0)
                .put("name", leg.turnRoad)
                .put(
                    "maneuver",
                    JSONObject()
                        .put("type", "arrive")
                        .put("instruction", "You have arrived")
                        .put("bearing_before", 90.0)
                        .put("bearing_after", 90.0)
                        .put("location", JSONArray().put(destPt.longitude).put(destPt.latitude))
                )

            legsJson.put(
                JSONObject()
                    .put("summary", leg.summary)
                    .put("distance", leg.edgeDist * edgeCount)
                    .put("duration", leg.edgeDur * edgeCount)
                    .put("steps", JSONArray().put(step0).put(step1).put(stepArrive))
                    .put(
                        "annotation",
                        JSONObject()
                            .put("maxspeed", maxSpeeds)
                            .put("distance", distances)
                            .put("duration", durations)
                    )
            )
        }
        return JSONObject()
            .put("geometry", PolylineCodec.encodePolyline6(fullGeom))
            .put("distance", totalDist)
            .put("duration", totalDur)
            .put("legs", legsJson)
    }

    private fun photonFeature(
        osmId: Long,
        name: String,
        street: String,
        city: String,
        pos: LatLng
    ): JSONObject {
        return JSONObject()
            .put("type", "Feature")
            .put(
                "geometry",
                JSONObject()
                    .put("type", "Point")
                    .put("coordinates", JSONArray().put(pos.longitude).put(pos.latitude))
            )
            .put(
                "properties",
                JSONObject()
                    .put("osm_type", "W")
                    .put("osm_id", osmId)
                    .put("name", name)
                    .put("street", street)
                    .put("city", city)
                    .put("country", "Malaysia")
            )
    }

    private fun minimalOfflineStyleJson(): String = """
        {
          "version": 8,
          "name": "HalaTujuOfflineTest",
          "sources": {},
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": { "background-color": "#EDF2F1" }
            }
          ]
        }
    """.trimIndent()

    private fun minimalTileJson(): String = """
        {
          "tilejson": "3.0.0",
          "name": "offline",
          "tiles": []
        }
    """.trimIndent()

    private fun nominatimReverseJson(): String = """
        {
          "place_id": 1001,
          "lat": "3.1579",
          "lon": "101.7116",
          "name": "KLCC",
          "display_name": "KLCC, Jalan Ampang, Kuala Lumpur, Malaysia"
        }
    """.trimIndent()

    private fun jsonResponse(body: String): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)
    }
}
