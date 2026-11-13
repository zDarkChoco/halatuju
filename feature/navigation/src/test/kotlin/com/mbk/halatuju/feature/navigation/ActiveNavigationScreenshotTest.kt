package com.mbk.halatuju.feature.navigation

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.Lifecycle
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.mbk.halatuju.core.map.OpenFreeMapTileSource
import com.mbk.halatuju.core.map.theme.HalaTujuTheme
import com.mbk.halatuju.core.model.AppSettings
import com.mbk.halatuju.core.model.DebugOverlayData
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LaneIndication
import com.mbk.halatuju.core.model.LaneInfo
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.LocationProviderKind
import com.mbk.halatuju.core.model.ManeuverModifier
import com.mbk.halatuju.core.model.ManeuverType
import com.mbk.halatuju.core.model.MapCameraMode
import com.mbk.halatuju.core.model.MapCameraState
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.PuckRenderState
import com.mbk.halatuju.core.model.SegmentSpeedLimit
import com.mbk.halatuju.core.model.ThemeMode
import com.mbk.halatuju.core.navigation.GuidanceBannerState
import com.mbk.halatuju.core.navigation.NavigationSessionState
import com.mbk.halatuju.core.navigation.NavigationStatus
import com.mbk.halatuju.core.navigation.OverspeedState
import com.mbk.halatuju.core.navigation.SecondaryManeuverPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

class LaneGuidanceUnitTest {

    @Test
    fun `lane sanitizer hides empty, none-only, or all-invalid lanes and cleans valid combinations`() {
        assertTrue(sanitizeLanesForDisplay(emptyList()).isEmpty())
        assertTrue(
            sanitizeLanesForDisplay(
                listOf(LaneInfo(listOf(LaneIndication.NONE), valid = true))
            ).isEmpty()
        )
        assertTrue(
            sanitizeLanesForDisplay(
                listOf(
                    LaneInfo(listOf(LaneIndication.STRAIGHT), valid = false),
                    LaneInfo(listOf(LaneIndication.RIGHT), valid = false)
                )
            ).isEmpty()
        )

        val cleaned = sanitizeLanesForDisplay(
            listOf(
                LaneInfo(listOf(LaneIndication.NONE), valid = false),
                LaneInfo(listOf(LaneIndication.STRAIGHT, LaneIndication.NONE, LaneIndication.STRAIGHT), valid = false),
                LaneInfo(listOf(LaneIndication.STRAIGHT, LaneIndication.SLIGHT_RIGHT), valid = true)
            )
        )
        assertEquals(2, cleaned.size)
        assertEquals(listOf(LaneIndication.STRAIGHT), cleaned[0].indications)
        assertEquals(listOf(LaneIndication.STRAIGHT, LaneIndication.SLIGHT_RIGHT), cleaned[1].indications)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = RobolectricDeviceQualifiers.Pixel5)
class ActiveNavigationScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val klZone = ZoneId.of("Asia/Kuala_Lumpur")

    private fun sampleRoute(): NavRoute {
        val pts = listOf(
            LatLng(3.1579, 101.7116),
            LatLng(3.1120, 101.7010),
            LatLng(3.0450, 101.6890),
            LatLng(2.9180, 101.6820),
            KlDefaults.KLIA
        )
        return NavRoute(
            id = "nav_mex",
            geometry = pts,
            cumulativeDistancesMeters = listOf(0.0, 5_500.0, 13_500.0, 29_000.0, 54_200.0),
            totalDistanceMeters = 54_200.0,
            baseDurationSeconds = 2_700.0,
            adjustedDurationSeconds = 3_120.0,
            trafficEstimated = true,
            trafficDelayFactor = 1.15,
            trafficLabel = "estimated",
            legs = emptyList(),
            waypoints = listOf(
                Place("p1", "KLCC", "Kuala Lumpur", pts.first()),
                Place("p2", "KLIA Terminal 1", "Sepang", pts.last())
            ),
            summaryLabel = "MEX / E20 → E6"
        )
    }

    @Test
    fun m4ActiveNavLight() {
        val tileSource = OpenFreeMapTileSource(RuntimeEnvironment.getApplication())
        val route = sampleRoute()
        val puckPos = LatLng(3.0450, 101.6890)
        val uiState = ActiveNavigationUiState(
            session = NavigationSessionState(
                status = NavigationStatus.ACTIVE,
                route = route,
                distanceAlongRouteMeters = 13_500.0,
                remainingDistanceMeters = 40_700.0,
                remainingDurationSeconds = 2_340.0,
                estimatedArrivalEpochMs = 1_778_583_540_000L,
                etaTrafficLabel = "estimated · evening peak traffic",
                progressFraction = 0.25f,
                cameraMode = MapCameraMode.NAVIGATION_25D,
                cameraState = MapCameraState(
                    mode = MapCameraMode.NAVIGATION_25D,
                    center = puckPos,
                    zoom = 15.1,
                    bearingDegrees = 185.0,
                    tiltDegrees = 52.0
                ),
                puckState = PuckRenderState(
                    position = puckPos,
                    bearingDegrees = 185f,
                    accuracyMeters = 7f
                ),
                banner = GuidanceBannerState(
                    maneuverType = ManeuverType.OFF_RAMP,
                    maneuverModifier = ManeuverModifier.SLIGHT_LEFT,
                    distanceToManeuverMeters = 280.0,
                    primaryText = "Lebuhraya KLIA",
                    secondaryDestinations = "KLIA / Seremban / Putrajaya",
                    exitShieldText = "608",
                    thenStep = SecondaryManeuverPreview(
                        maneuverType = ManeuverType.ROUNDABOUT,
                        maneuverModifier = ManeuverModifier.RIGHT,
                        roadName = "Bulatan KLIA"
                    )
                ),
                overspeed = OverspeedState(
                    currentSpeedKmh = 98,
                    speedLimit = SegmentSpeedLimit(speedKmh = 110, isExplicitNone = false, isUnknown = false),
                    isOverspeeding = false
                )
            ),
            settings = AppSettings(themeMode = ThemeMode.LIGHT)
        )

        composeRule.setContent {
            HalaTujuTheme(themeMode = ThemeMode.LIGHT) {
                ActiveNavigationScreen(
                    uiState = uiState,
                    tileSource = tileSource,
                    isDarkTheme = false,
                    onUserPanMap = {},
                    onToggleOverview = {},
                    onRecenterClick = {},
                    onToggleMute = {},
                    onContinueToNextStop = {},
                    onEndNavigation = {},
                    onOpenAbout = {},
                    clockZoneId = klZone
                )
            }
        }

        composeRule.onNodeWithTag("active_navigation_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_maneuver_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_then_chip").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_lane_guidance_strip").assertDoesNotExist()
        composeRule.onNodeWithTag("nav_current_speed").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_speed_limit_badge").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_overview_button").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_end_button").assertIsDisplayed()
        composeRule.onNodeWithText("© OpenStreetMap contributors").assertIsDisplayed()

        composeRule.onRoot().captureRoboImage("../../docs/screenshots/m4_active_nav_light.png")
    }

    @Test
    fun m4ActiveNavDarkWithRecenterReroutingAndDebugOverlay() {
        val tileSource = OpenFreeMapTileSource(RuntimeEnvironment.getApplication())
        val route = sampleRoute()
        val puckPos = LatLng(3.0450, 101.6890)
        val rawPos = LatLng(3.04508, 101.68955)
        val kalmanPos = LatLng(3.04505, 101.68942)

        val uiState = ActiveNavigationUiState(
            session = NavigationSessionState(
                status = NavigationStatus.ACTIVE,
                route = route,
                distanceAlongRouteMeters = 13_500.0,
                remainingDistanceMeters = 40_700.0,
                remainingDurationSeconds = 2_400.0,
                estimatedArrivalEpochMs = 1_778_583_540_000L,
                etaTrafficLabel = "estimated",
                progressFraction = 0.25f,
                cameraMode = MapCameraMode.FREE_PAN,
                cameraState = MapCameraState(
                    mode = MapCameraMode.FREE_PAN,
                    center = puckPos,
                    zoom = 15.8,
                    bearingDegrees = 180.0,
                    tiltDegrees = 0.0
                ),
                puckState = PuckRenderState(
                    position = puckPos,
                    bearingDegrees = 180f,
                    accuracyMeters = 12f,
                    isDeadReckoning = true
                ),
                banner = GuidanceBannerState(
                    maneuverType = ManeuverType.ROUNDABOUT,
                    maneuverModifier = ManeuverModifier.RIGHT,
                    distanceToManeuverMeters = 140.0,
                    primaryText = "Bulatan Kampung Pandan",
                    rotaryExitNumber = 2
                ),
                overspeed = OverspeedState(
                    currentSpeedKmh = 92,
                    speedLimit = SegmentSpeedLimit(speedKmh = 80, isExplicitNone = false, isUnknown = false),
                    isOverspeeding = true
                ),
                isRerouting = true,
                isGpsSignalWeak = true,
                isMuted = true,
                debugOverlayData = DebugOverlayData(
                    rawFix = LocationFix(
                        position = rawPos,
                        accuracyMeters = 28f,
                        speedMps = 25f,
                        bearingDegrees = 180f,
                        timestampMs = 1_000L,
                        provider = LocationProviderKind.GPS
                    ),
                    kalmanPosition = kalmanPos,
                    snappedPosition = puckPos,
                    crossTrackErrorMeters = 48.0,
                    isOffRoute = true,
                    isDeadReckoning = true
                )
            ),
            settings = AppSettings(
                themeMode = ThemeMode.DARK,
                developerDebugOverlayEnabled = true
            )
        )

        composeRule.setContent {
            HalaTujuTheme(themeMode = ThemeMode.DARK) {
                ActiveNavigationScreen(
                    uiState = uiState,
                    tileSource = tileSource,
                    isDarkTheme = true,
                    onUserPanMap = {},
                    onToggleOverview = {},
                    onRecenterClick = {},
                    onToggleMute = {},
                    onContinueToNextStop = {},
                    onEndNavigation = {},
                    onOpenAbout = {},
                    clockZoneId = klZone
                )
            }
        }

        composeRule.onNodeWithTag("nav_rotary_exit_badge").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_current_speed").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_speed_limit_badge").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_recenter_button").assertIsDisplayed()
        composeRule.onNodeWithTag("rerouting_pill").assertIsDisplayed()
        composeRule.onNodeWithTag("gps_weak_pill").assertIsDisplayed()
        composeRule.onNodeWithText("© OpenStreetMap contributors").assertIsDisplayed()

        composeRule.onRoot().captureRoboImage("../../docs/screenshots/m4_active_nav_dark.png")
    }

    @Test
    fun m5NavWithMultiLaneGuidanceCombinationsLight() {
        val tileSource = OpenFreeMapTileSource(RuntimeEnvironment.getApplication())
        val route = sampleRoute()
        val puckPos = LatLng(3.0450, 101.6890)
        val uiState = ActiveNavigationUiState(
            session = NavigationSessionState(
                status = NavigationStatus.ACTIVE,
                route = route,
                distanceAlongRouteMeters = 13_500.0,
                remainingDistanceMeters = 40_700.0,
                remainingDurationSeconds = 2_340.0,
                estimatedArrivalEpochMs = 1_778_583_540_000L,
                etaTrafficLabel = "estimated · evening peak traffic",
                cameraMode = MapCameraMode.NAVIGATION_25D,
                cameraState = MapCameraState(
                    mode = MapCameraMode.NAVIGATION_25D,
                    center = puckPos,
                    zoom = 16.5,
                    bearingDegrees = 185.0,
                    tiltDegrees = 52.0
                ),
                puckState = PuckRenderState(
                    position = puckPos,
                    bearingDegrees = 185f,
                    accuracyMeters = 6f
                ),
                banner = GuidanceBannerState(
                    maneuverType = ManeuverType.OFF_RAMP,
                    maneuverModifier = ManeuverModifier.SLIGHT_LEFT,
                    distanceToManeuverMeters = 240.0,
                    primaryText = "Susur Keluar KLIA",
                    secondaryDestinations = "KLIA Terminal 1 / Terminal 2",
                    exitShieldText = "1201B",
                    lanes = listOf(
                        LaneInfo(listOf(LaneIndication.SHARP_LEFT, LaneIndication.SLIGHT_LEFT), valid = true),
                        LaneInfo(listOf(LaneIndication.SLIGHT_LEFT, LaneIndication.STRAIGHT), valid = true),
                        LaneInfo(listOf(LaneIndication.STRAIGHT), valid = false),
                        LaneInfo(listOf(LaneIndication.STRAIGHT), valid = false),
                        LaneInfo(listOf(LaneIndication.STRAIGHT, LaneIndication.RIGHT), valid = false)
                    ),
                    thenStep = SecondaryManeuverPreview(
                        maneuverType = ManeuverType.MERGE,
                        maneuverModifier = ManeuverModifier.SLIGHT_RIGHT,
                        roadName = "Lebuhraya Elite"
                    )
                ),
                overspeed = OverspeedState(
                    currentSpeedKmh = 96,
                    speedLimit = SegmentSpeedLimit(speedKmh = 110, isExplicitNone = false, isUnknown = false),
                    isOverspeeding = false
                )
            ),
            settings = AppSettings(themeMode = ThemeMode.LIGHT)
        )

        composeRule.setContent {
            HalaTujuTheme(themeMode = ThemeMode.LIGHT) {
                ActiveNavigationScreen(
                    uiState = uiState,
                    tileSource = tileSource,
                    isDarkTheme = false,
                    onUserPanMap = {},
                    onToggleOverview = {},
                    onRecenterClick = {},
                    onToggleMute = {},
                    onContinueToNextStop = {},
                    onEndNavigation = {},
                    onOpenAbout = {},
                    clockZoneId = klZone
                )
            }
        }

        composeRule.onNodeWithTag("nav_lane_guidance_strip").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_lane_slot_0").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_lane_slot_4").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_then_chip").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_exit_shield").assertIsDisplayed()

        composeRule.onRoot().captureRoboImage("../../docs/screenshots/m5_nav_lanes_light.png")
    }

    @Test
    fun m5NavOverspeedAlertWithComplexLanesDarkAndUnknownSpeedLimitHiddenCheck() {
        val tileSource = OpenFreeMapTileSource(RuntimeEnvironment.getApplication())
        val route = sampleRoute()
        val puckPos = LatLng(3.0450, 101.6890)
        var showUnknownLimit by mutableStateOf(false)

        composeRule.setContent {
            val uiState = ActiveNavigationUiState(
                session = NavigationSessionState(
                    status = NavigationStatus.ACTIVE,
                    route = route,
                    distanceAlongRouteMeters = 13_500.0,
                    remainingDistanceMeters = 18_400.0,
                    remainingDurationSeconds = 1_140.0,
                    estimatedArrivalEpochMs = 1_778_582_340_000L,
                    etaTrafficLabel = "estimated",
                    cameraMode = MapCameraMode.NAVIGATION_25D,
                    cameraState = MapCameraState(
                        mode = MapCameraMode.NAVIGATION_25D,
                        center = puckPos,
                        zoom = 15.8,
                        bearingDegrees = 185.0,
                        tiltDegrees = 52.0
                    ),
                    puckState = PuckRenderState(
                        position = puckPos,
                        bearingDegrees = 185f,
                        accuracyMeters = 7f
                    ),
                    banner = GuidanceBannerState(
                        maneuverType = ManeuverType.TURN,
                        maneuverModifier = ManeuverModifier.RIGHT,
                        distanceToManeuverMeters = 350.0,
                        primaryText = "Jalan Tun Razak",
                        secondaryDestinations = "Ampang / Cheras",
                        lanes = if (showUnknownLimit) {
                            listOf(LaneInfo(listOf(LaneIndication.NONE), valid = true))
                        } else {
                            listOf(
                                LaneInfo(listOf(LaneIndication.UTURN, LaneIndication.LEFT), valid = false),
                                LaneInfo(listOf(LaneIndication.STRAIGHT), valid = false),
                                LaneInfo(listOf(LaneIndication.MERGE_RIGHT, LaneIndication.RIGHT), valid = true),
                                LaneInfo(listOf(LaneIndication.SHARP_RIGHT), valid = false)
                            )
                        }
                    ),
                    overspeed = OverspeedState(
                        currentSpeedKmh = 94,
                        speedLimit = if (showUnknownLimit) {
                            SegmentSpeedLimit(isUnknown = true)
                        } else {
                            SegmentSpeedLimit(speedKmh = 80, isExplicitNone = false, isUnknown = false)
                        },
                        isOverspeeding = !showUnknownLimit
                    )
                ),
                settings = AppSettings(themeMode = ThemeMode.DARK)
            )

            HalaTujuTheme(themeMode = ThemeMode.DARK) {
                ActiveNavigationScreen(
                    uiState = uiState,
                    tileSource = tileSource,
                    isDarkTheme = true,
                    onUserPanMap = {},
                    onToggleOverview = {},
                    onRecenterClick = {},
                    onToggleMute = {},
                    onContinueToNextStop = {},
                    onEndNavigation = {},
                    onOpenAbout = {},
                    clockZoneId = klZone
                )
            }
        }

        composeRule.onNodeWithTag("nav_lane_guidance_strip").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_current_speed").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_speed_limit_badge").assertIsDisplayed()

        composeRule.onRoot().captureRoboImage("../../docs/screenshots/m5_nav_overspeed_dark.png")

        // Flip to unknown maxspeed and NONE-only lanes -> both speed limit badge and lane strip must hide cleanly
        showUnknownLimit = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_current_speed").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_speed_limit_badge").assertDoesNotExist()
        composeRule.onNodeWithTag("nav_lane_guidance_strip").assertDoesNotExist()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeepScreenOnWakeLockTest {

    @get:Rule
    val activityRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `keep screen on flag tracks active navigation state and foreground lifecycle`() {
        var keepAwake by mutableStateOf(true)
        var capturedView: android.view.View? = null

        activityRule.setContent {
            capturedView = LocalView.current
            KeepScreenOnEffect(keepScreenOn = keepAwake)
        }

        activityRule.runOnIdle {
            val flags = activityRule.activity.window.attributes.flags
            assertTrue(
                "FLAG_KEEP_SCREEN_ON must be set while active guidance is visible",
                (flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
            )
            assertTrue(capturedView!!.keepScreenOn)
        }

        // Simulate arrival or End button -> releases FLAG_KEEP_SCREEN_ON immediately
        keepAwake = false
        activityRule.waitForIdle()
        activityRule.runOnIdle {
            val flags = activityRule.activity.window.attributes.flags
            assertFalse(
                "FLAG_KEEP_SCREEN_ON must clear on End or Arrival",
                (flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
            )
            assertFalse(capturedView!!.keepScreenOn)
        }

        // Re-enable active guidance, then move Activity to CREATED (backgrounded / ON_STOP)
        keepAwake = true
        activityRule.waitForIdle()
        activityRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        activityRule.runOnIdle {
            val flags = activityRule.activity.window.attributes.flags
            assertFalse(
                "FLAG_KEEP_SCREEN_ON must release immediately when app is backgrounded",
                (flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
            )
            assertFalse(capturedView!!.keepScreenOn)
        }
    }
}
