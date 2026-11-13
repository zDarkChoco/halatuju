package com.mbk.halatuju

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.mbk.halatuju.core.map.MapRenderingState
import com.mbk.halatuju.core.map.OpenFreeMapTileSource
import com.mbk.halatuju.core.map.theme.HalaTujuTheme
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.MapCameraMode
import com.mbk.halatuju.core.model.MapCameraState
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.PuckRenderState
import com.mbk.halatuju.core.model.RoutingAvoidances
import com.mbk.halatuju.core.model.ThemeMode
import com.mbk.halatuju.feature.search.RouteEndpoint
import com.mbk.halatuju.feature.search.RoutePreviewUiState
import com.mbk.halatuju.feature.search.SearchUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = RobolectricDeviceQualifiers.Pixel5)
class RoutePreviewScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val klcc = Place("p1", "KLCC", "Jalan Ampang, Kuala Lumpur", KlDefaults.KLCC)
    private val klSentral = Place("p2", "KL Sentral", "Brickfields, Kuala Lumpur", KlDefaults.KL_SENTRAL)
    private val klia = Place("p3", "KLIA Terminal 1", "Sepang, Selangor", KlDefaults.KLIA)

    private val primaryGeometry = listOf(
        KlDefaults.KLCC,
        LatLng(3.1120, 101.7010),
        LatLng(3.0450, 101.6890),
        LatLng(2.9180, 101.6820),
        KlDefaults.KLIA
    )

    private val alt1Geometry = listOf(
        KlDefaults.KLCC,
        LatLng(3.1050, 101.7320),
        LatLng(2.9710, 101.7410),
        LatLng(2.8380, 101.7300),
        KlDefaults.KLIA
    )

    private val alt2Geometry = listOf(
        KlDefaults.KLCC,
        KlDefaults.KL_SENTRAL,
        LatLng(3.0120, 101.6440),
        LatLng(2.8520, 101.6620),
        KlDefaults.KLIA
    )

    @Test
    fun m3RoutePreviewLightWith3Alternatives() {
        val tileSource = OpenFreeMapTileSource(RuntimeEnvironment.getApplication())
        val routes = listOf(
            NavRoute(
                id = "r0",
                geometry = primaryGeometry,
                cumulativeDistancesMeters = listOf(0.0, 6_000.0, 15_000.0, 32_000.0, 56_800.0),
                totalDistanceMeters = 56_800.0,
                baseDurationSeconds = 2_820.0,
                adjustedDurationSeconds = 3_600.0,
                trafficEstimated = true,
                trafficDelayFactor = 1.28,
                trafficLabel = "estimated · evening peak traffic",
                legs = emptyList(),
                waypoints = listOf(klcc, klia),
                summaryLabel = "E20, E6"
            ),
            NavRoute(
                id = "r1",
                geometry = alt1Geometry,
                cumulativeDistancesMeters = listOf(0.0, 7_000.0, 19_000.0, 38_000.0, 60_200.0),
                totalDistanceMeters = 60_200.0,
                baseDurationSeconds = 3_120.0,
                adjustedDurationSeconds = 3_960.0,
                trafficEstimated = true,
                trafficDelayFactor = 1.27,
                trafficLabel = "estimated · evening peak traffic",
                legs = emptyList(),
                waypoints = listOf(klcc, klia),
                summaryLabel = "BESRAYA, E2"
            ),
            NavRoute(
                id = "r2",
                geometry = alt2Geometry,
                cumulativeDistancesMeters = listOf(0.0, 5_000.0, 21_000.0, 44_000.0, 66_400.0),
                totalDistanceMeters = 66_400.0,
                baseDurationSeconds = 3_780.0,
                adjustedDurationSeconds = 4_620.0,
                trafficEstimated = true,
                trafficDelayFactor = 1.22,
                trafficLabel = "estimated · evening peak traffic",
                legs = emptyList(),
                waypoints = listOf(klcc, klia),
                summaryLabel = "LDP, E6"
            )
        )

        val searchState = SearchUiState(
            fromEndpoint = RouteEndpoint.MyLocation,
            toEndpoint = RouteEndpoint.SelectedPlace(klia),
            fromQueryText = SearchUiState.MY_LOCATION_LABEL,
            toQueryText = klia.name,
            routePreview = RoutePreviewUiState(
                candidateRoutes = routes,
                selectedRouteIndex = 0,
                referenceEpochMs = 1_778_581_200_000L // fixed deterministic clock
            )
        )

        composeRule.setContent {
            HalaTujuTheme(themeMode = ThemeMode.LIGHT) {
                HalaTujuHomeMapShell(
                    mapState = MapRenderingState(
                        isDarkTheme = false,
                        cameraState = MapCameraState(mode = MapCameraMode.OVERVIEW, center = KlDefaults.KLCC),
                        puckState = PuckRenderState(position = KlDefaults.KLCC, bearingDegrees = 180f, accuracyMeters = 8f),
                        activeRoute = routes[0],
                        alternativeRoutes = routes,
                        selectedRouteIndex = 0,
                        waypoints = listOf(klcc, klia)
                    ),
                    tileSource = tileSource,
                    hasLocationPermission = true,
                    tiltPreviewEnabled = false,
                    searchState = searchState,
                    onRequestLocationPermission = {},
                    onUserPanMap = {},
                    onRecenterClick = {},
                    onOpenSettings = {},
                    onOpenAbout = {}
                )
            }
        }

        composeRule.onNodeWithTag("route_preview_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("route_traffic_label").assertIsDisplayed()
        composeRule.onNodeWithTag("alternative_routes_strip").assertIsDisplayed()
        composeRule.onNodeWithTag("add_stop_button").assertIsDisplayed()
        composeRule.onNodeWithTag("start_navigation_button").assertIsEnabled()
        composeRule.onNodeWithText("Start").assertIsDisplayed()

        composeRule.onRoot().captureRoboImage("../docs/screenshots/m3_route_preview_light.png")
    }

    @Test
    fun m3RoutePreviewDarkWithStopsAndAvoidTolls() {
        val tileSource = OpenFreeMapTileSource(RuntimeEnvironment.getApplication())
        val multiStopRoute = NavRoute(
            id = "multi_0",
            geometry = listOf(KlDefaults.KLCC, KlDefaults.KL_SENTRAL, LatLng(2.94, 101.69), KlDefaults.KLIA),
            cumulativeDistancesMeters = listOf(0.0, 5_800.0, 31_000.0, 64_100.0),
            totalDistanceMeters = 64_100.0,
            baseDurationSeconds = 4_200.0,
            adjustedDurationSeconds = 4_320.0,
            trafficEstimated = true,
            trafficDelayFactor = 1.03,
            trafficLabel = "estimated",
            legs = emptyList(),
            waypoints = listOf(klcc, klSentral, klia),
            summaryLabel = "Lebuhraya Kuala Lumpur–Seremban → B48"
        )

        val searchState = SearchUiState(
            fromEndpoint = RouteEndpoint.SelectedPlace(klcc),
            toEndpoint = RouteEndpoint.SelectedPlace(klia),
            intermediateStops = listOf(klSentral),
            fromQueryText = klcc.name,
            toQueryText = klia.name,
            routePreview = RoutePreviewUiState(
                candidateRoutes = listOf(multiStopRoute),
                selectedRouteIndex = 0,
                referenceEpochMs = 1_778_554_800_000L,
                activeAvoidances = RoutingAvoidances(avoidTolls = true)
            )
        )

        composeRule.setContent {
            HalaTujuTheme(themeMode = ThemeMode.DARK) {
                HalaTujuHomeMapShell(
                    mapState = MapRenderingState(
                        isDarkTheme = true,
                        cameraState = MapCameraState(mode = MapCameraMode.OVERVIEW, center = KlDefaults.KLCC),
                        puckState = PuckRenderState(position = KlDefaults.KLCC, bearingDegrees = 210f, accuracyMeters = 8f),
                        activeRoute = multiStopRoute,
                        alternativeRoutes = listOf(multiStopRoute),
                        selectedRouteIndex = 0,
                        waypoints = listOf(klcc, klSentral, klia)
                    ),
                    tileSource = tileSource,
                    hasLocationPermission = true,
                    tiltPreviewEnabled = false,
                    searchState = searchState,
                    onRequestLocationPermission = {},
                    onUserPanMap = {},
                    onRecenterClick = {},
                    onOpenSettings = {},
                    onOpenAbout = {}
                )
            }
        }

        composeRule.onNodeWithTag("route_preview_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("intermediate_stops_section").assertIsDisplayed()
        composeRule.onNodeWithText("KL Sentral").assertIsDisplayed()
        composeRule.onNodeWithTag("start_navigation_button").assertIsEnabled()

        composeRule.onRoot().captureRoboImage("../docs/screenshots/m3_route_preview_dark.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w360dp-h640dp-port-xxhdpi")
class MultiDeviceRoutePreviewScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val klcc = Place("p1", "KLCC", "Jalan Ampang, Kuala Lumpur", KlDefaults.KLCC)
    private val klSentral = Place("p2", "KL Sentral", "Brickfields, Kuala Lumpur", KlDefaults.KL_SENTRAL)
    private val klia = Place("p3", "KLIA Terminal 1", "Sepang, Selangor", KlDefaults.KLIA)

    private fun sampleRoutes(): List<NavRoute> {
        val geom0 = listOf(KlDefaults.KLCC, LatLng(3.1120, 101.7010), LatLng(2.9180, 101.6820), KlDefaults.KLIA)
        val geom1 = listOf(KlDefaults.KLCC, LatLng(3.1050, 101.7320), LatLng(2.8380, 101.7300), KlDefaults.KLIA)
        val geom2 = listOf(KlDefaults.KLCC, KlDefaults.KL_SENTRAL, LatLng(2.8520, 101.6620), KlDefaults.KLIA)
        return listOf(
            NavRoute(
                id = "r0",
                geometry = geom0,
                cumulativeDistancesMeters = listOf(0.0, 6_000.0, 32_000.0, 56_800.0),
                totalDistanceMeters = 56_800.0,
                baseDurationSeconds = 2_820.0,
                adjustedDurationSeconds = 3_600.0,
                trafficEstimated = true,
                trafficDelayFactor = 1.28,
                trafficLabel = "estimated · evening peak traffic",
                legs = emptyList(),
                waypoints = listOf(klcc, klia),
                summaryLabel = "E20, E6"
            ),
            NavRoute(
                id = "r1",
                geometry = geom1,
                cumulativeDistancesMeters = listOf(0.0, 7_000.0, 38_000.0, 60_200.0),
                totalDistanceMeters = 60_200.0,
                baseDurationSeconds = 3_120.0,
                adjustedDurationSeconds = 3_960.0,
                trafficEstimated = true,
                trafficDelayFactor = 1.27,
                trafficLabel = "estimated · evening peak traffic",
                legs = emptyList(),
                waypoints = listOf(klcc, klia),
                summaryLabel = "BESRAYA, E2"
            ),
            NavRoute(
                id = "r2",
                geometry = geom2,
                cumulativeDistancesMeters = listOf(0.0, 5_000.0, 44_000.0, 66_400.0),
                totalDistanceMeters = 66_400.0,
                baseDurationSeconds = 3_780.0,
                adjustedDurationSeconds = 4_620.0,
                trafficEstimated = true,
                trafficDelayFactor = 1.22,
                trafficLabel = "estimated · evening peak traffic",
                legs = emptyList(),
                waypoints = listOf(klcc, klia),
                summaryLabel = "LDP, E6"
            )
        )
    }

    private fun sampleSearchState(routes: List<NavRoute>, stops: List<Place> = emptyList()): SearchUiState {
        return SearchUiState(
            fromEndpoint = RouteEndpoint.MyLocation,
            toEndpoint = RouteEndpoint.SelectedPlace(klia),
            intermediateStops = stops,
            fromQueryText = SearchUiState.MY_LOCATION_LABEL,
            toQueryText = klia.name,
            routePreview = RoutePreviewUiState(
                candidateRoutes = routes,
                selectedRouteIndex = 0,
                referenceEpochMs = 1_778_581_200_000L,
                activeAvoidances = if (stops.isNotEmpty()) RoutingAvoidances(avoidTolls = true) else RoutingAvoidances()
            )
        )
    }

    private fun renderPreviewShell(themeMode: ThemeMode, fontScale: Float = 1.0f, withStop: Boolean = false) {
        val tileSource = OpenFreeMapTileSource(RuntimeEnvironment.getApplication())
        val routes = sampleRoutes()
        val stops = if (withStop) listOf(klSentral) else emptyList()
        composeRule.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    density = base.density,
                    fontScale = fontScale
                )
            ) {
                HalaTujuTheme(themeMode = themeMode) {
                    HalaTujuHomeMapShell(
                        mapState = MapRenderingState(
                            isDarkTheme = themeMode == ThemeMode.DARK,
                            cameraState = MapCameraState(mode = MapCameraMode.OVERVIEW, center = KlDefaults.KLCC),
                            puckState = PuckRenderState(position = KlDefaults.KLCC, bearingDegrees = 180f, accuracyMeters = 8f),
                            activeRoute = routes[0],
                            alternativeRoutes = routes,
                            selectedRouteIndex = 0,
                            waypoints = listOf(klcc) + stops + listOf(klia)
                        ),
                        tileSource = tileSource,
                        hasLocationPermission = true,
                        tiltPreviewEnabled = false,
                        searchState = sampleSearchState(routes, stops),
                        onRequestLocationPermission = {},
                        onUserPanMap = {},
                        onRecenterClick = {},
                        onOpenSettings = {},
                        onOpenAbout = {}
                    )
                }
            }
        }
    }

    private fun assertPreviewNonOverlappingAndAccessible() {
        composeRule.onNodeWithTag("home_search_card").assertIsDisplayed()
        composeRule.onNodeWithTag("route_preview_sheet").assertIsDisplayed()

        val searchBounds = composeRule.onNodeWithTag("home_search_card").getBoundsInRoot()
        val previewBounds = composeRule.onNodeWithTag("route_preview_sheet").getBoundsInRoot()
        val recenterBounds = composeRule.onNodeWithTag("recenter_button").getBoundsInRoot()
        fun noOverlap(a: androidx.compose.ui.unit.DpRect, b: androidx.compose.ui.unit.DpRect): Boolean =
            a.right.value <= b.left.value + 0.5f ||
                b.right.value <= a.left.value + 0.5f ||
                a.bottom.value <= b.top.value + 0.5f ||
                b.bottom.value <= a.top.value + 0.5f

        org.junit.Assert.assertTrue(
            "Expected home_search_card ($searchBounds) and route_preview_sheet ($previewBounds) not to overlap",
            noOverlap(searchBounds, previewBounds)
        )
        org.junit.Assert.assertTrue(
            "Expected recenter_button ($recenterBounds) and route_preview_sheet ($previewBounds) not to overlap",
            noOverlap(recenterBounds, previewBounds)
        )

        listOf(
            "settings_button",
            "recenter_button",
            "swap_endpoints_button",
            "close_route_preview_button"
        ).forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).getBoundsInRoot()
            org.junit.Assert.assertTrue(
                "Expected $tag width >= 48dp (was ${bounds.right.value - bounds.left.value})",
                (bounds.right.value - bounds.left.value) >= 47.9f
            )
            org.junit.Assert.assertTrue(
                "Expected $tag height >= 48dp (was ${bounds.bottom.value - bounds.top.value})",
                (bounds.bottom.value - bounds.top.value) >= 47.9f
            )
        }

        listOf("start_navigation_button", "add_stop_button").forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val bounds = composeRule.onNodeWithTag(tag).getBoundsInRoot()
            org.junit.Assert.assertTrue(
                "Expected $tag width >= 48dp (was ${bounds.right.value - bounds.left.value})",
                (bounds.right.value - bounds.left.value) >= 47.9f
            )
            org.junit.Assert.assertTrue(
                "Expected $tag height >= 48dp (was ${bounds.bottom.value - bounds.top.value})",
                (bounds.bottom.value - bounds.top.value) >= 47.9f
            )
        }
    }

    @Test
    fun m8Compact360RoutePreviewLight() {
        renderPreviewShell(ThemeMode.LIGHT)
        assertPreviewNonOverlappingAndAccessible()
        composeRule.onRoot().captureRoboImage("../docs/screenshots/m8_compact360_preview_light.png")
    }

    @Test
    @Config(qualifiers = "w1024dp-h768dp-land-xhdpi")
    fun m8TabletLandscapeRoutePreviewDark() {
        renderPreviewShell(ThemeMode.DARK, withStop = true)
        assertPreviewNonOverlappingAndAccessible()
        composeRule.onRoot().captureRoboImage("../docs/screenshots/m8_tablet_preview_dark.png")
    }

    @Test
    fun m8FontScale200RoutePreviewLight() {
        renderPreviewShell(ThemeMode.LIGHT, fontScale = 2.0f)
        composeRule.onRoot().captureRoboImage("../docs/screenshots/m8_font200_preview_light.png")
        assertPreviewNonOverlappingAndAccessible()
    }
}

