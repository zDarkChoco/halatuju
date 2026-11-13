package com.mbk.halatuju.core.navigation

import com.mbk.halatuju.core.location.LocationSource
import com.mbk.halatuju.core.model.AppSettings
import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.LocationProviderKind
import com.mbk.halatuju.core.model.ManeuverModifier
import com.mbk.halatuju.core.model.ManeuverType
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.RouteLeg
import com.mbk.halatuju.core.model.RouteStep
import com.mbk.halatuju.core.model.RoutingAvoidances
import com.mbk.halatuju.core.model.SegmentSpeedLimit
import com.mbk.halatuju.core.model.ServiceEndpoints
import com.mbk.halatuju.core.model.ThemeMode
import com.mbk.halatuju.core.model.UnitSystem
import com.mbk.halatuju.core.model.VoicePromptCue
import com.mbk.halatuju.core.model.VoiceStage
import com.mbk.halatuju.core.routing.RouteRequest
import com.mbk.halatuju.core.routing.Router
import com.mbk.halatuju.core.routing.TrafficEstimate
import com.mbk.halatuju.core.routing.TrafficProvider
import com.mbk.halatuju.core.storage.SettingsStore
import com.mbk.halatuju.core.voice.VoiceGuidancePlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationSessionManagerTest {

    @Test
    fun `session manager dispatches voice cues and single overspeed chime and respects mute toggle`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val locationFlow = MutableSharedFlow<LocationFix>(extraBufferCapacity = 32)
        val fakeLocationSource = object : LocationSource {
            override val providerKind = LocationProviderKind.SIMULATED
            override fun observeLocations(minIntervalMs: Long): Flow<LocationFix> = locationFlow
        }
        val settingsStore = FakeSettingsStore(
            AppSettings(
                voiceEnabled = true,
                voiceVolume = 0.75f,
                overspeedToleranceKmh = 5
            )
        )
        val voiceRecorder = RecordingVoicePlayer()
        val foregroundRecorder = RecordingForegroundController()
        var clockNowMs = 1_000L
        var epochNowMs = 10_000_000L

        val manager = NavigationSessionManager(
            locationSource = fakeLocationSource,
            onFixObserved = {},
            router = NoOpTestRouter,
            trafficProvider = FlatTrafficProvider,
            settingsStore = settingsStore,
            voiceGuidancePlayer = voiceRecorder,
            foregroundController = foregroundRecorder,
            clockMs = { clockNowMs },
            epochMs = { epochNowMs },
            dispatcher = dispatcher
        )

        val route = sampleCorridorRoute(speedLimitKmh = 60)
        manager.startNavigation(route)
        runCurrent()

        assertEquals(1, foregroundRecorder.startedCount)
        assertTrue(foregroundRecorder.observedStates.isNotEmpty())
        assertTrue(voiceRecorder.warmedUp)
        assertEquals(1, voiceRecorder.spokenCues.size)
        assertEquals(VoiceStage.DEPART, voiceRecorder.spokenCues.first().first.stage)
        assertEquals(0.75f, voiceRecorder.spokenCues.first().second, 0.001f)

        // Drive safely along corridor, then exceed limit+tolerance (60 + 5 -> drive 22 m/s = 79 km/h)
        var prevN = 0.0
        val points = listOf(
            50.0 to 15f,   // 54 km/h -> safe
            150.0 to 22f,  // 79 km/h -> triggers chime #1
            260.0 to 22f,  // still overspeeding -> MUST NOT re-chime
            650.0 to 15f   // drops below 60 km/h + hits medium window (250 m before turn at 900 m)
        )
        points.forEach { (northM, spd) ->
            val dt = (((northM - prevN) / spd) * 1000.0).toLong()
            clockNowMs += dt
            epochNowMs += dt
            prevN = northM
            locationFlow.tryEmit(
                LocationFix(
                    position = GeoMath.fromLocalEnMeters(KlDefaults.KLCC, 0.0, northM),
                    accuracyMeters = 5f,
                    speedMps = spd,
                    bearingDegrees = 0f,
                    timestampMs = epochNowMs,
                    provider = LocationProviderKind.SIMULATED
                )
            )
            runCurrent()
        }

        assertEquals(1, voiceRecorder.chimeVolumes.size)
        assertEquals(0.75f, voiceRecorder.chimeVolumes.first(), 0.001f)
        assertTrue(
            "Expected maneuver cue dispatched during drive",
            voiceRecorder.spokenCues.size >= 2
        )

        // Mute silences active TTS immediately and stops future chimes/cues while muted
        manager.toggleMute()
        runCurrent()
        assertTrue(voiceRecorder.stoppedCount >= 1)
        val cuesBeforeMute = voiceRecorder.spokenCues.size
        val chimesBeforeMute = voiceRecorder.chimeVolumes.size

        clockNowMs += 2_000L
        epochNowMs += 2_000L
        locationFlow.tryEmit(
            LocationFix(
                position = GeoMath.fromLocalEnMeters(KlDefaults.KLCC, 0.0, 865.0),
                accuracyMeters = 5f,
                speedMps = 25f, // 90 km/h (overspeed after dropping < 60 earlier)
                bearingDegrees = 0f,
                timestampMs = epochNowMs,
                provider = LocationProviderKind.SIMULATED
            )
        )
        runCurrent()

        assertEquals(cuesBeforeMute, voiceRecorder.spokenCues.size)
        assertEquals(chimesBeforeMute, voiceRecorder.chimeVolumes.size)

        manager.endNavigation()
        runCurrent()
        assertEquals(NavigationStatus.IDLE, manager.state.value.status)
        assertEquals(1, foregroundRecorder.stoppedCount)
    }

    private fun sampleCorridorRoute(speedLimitKmh: Int): NavRoute {
        val segMeters = 100.0
        val pts = (0..12).map { i -> GeoMath.fromLocalEnMeters(KlDefaults.KLCC, 0.0, i * segMeters) }
        val cum = (0..12).map { i -> i * segMeters }
        val steps = listOf(
            RouteStep(
                instructionText = "Head north",
                roadName = "Jalan Ampang",
                maneuverType = ManeuverType.DEPART,
                maneuverModifier = ManeuverModifier.STRAIGHT,
                maneuverLocation = pts[0],
                bearingBefore = 0.0,
                bearingAfter = 0.0,
                distanceMeters = 900.0,
                durationSeconds = 60.0,
                geometryStartIndex = 0,
                geometryEndIndex = 9
            ),
            RouteStep(
                instructionText = "Turn right",
                roadName = "Jalan Tun Razak",
                maneuverType = ManeuverType.TURN,
                maneuverModifier = ManeuverModifier.RIGHT,
                maneuverLocation = pts[9],
                bearingBefore = 0.0,
                bearingAfter = 90.0,
                distanceMeters = 300.0,
                durationSeconds = 20.0,
                geometryStartIndex = 9,
                geometryEndIndex = 12
            )
        )
        return NavRoute(
            id = "session_voice_test",
            geometry = pts,
            cumulativeDistancesMeters = cum,
            totalDistanceMeters = 1_200.0,
            baseDurationSeconds = 80.0,
            adjustedDurationSeconds = 80.0,
            trafficEstimated = false,
            legs = listOf(
                RouteLeg(
                    summary = "Jalan Ampang",
                    distanceMeters = 1_200.0,
                    durationSeconds = 80.0,
                    steps = steps,
                    segmentMaxSpeeds = List(12) {
                        SegmentSpeedLimit(speedKmh = speedLimitKmh, isExplicitNone = false, isUnknown = false)
                    }
                )
            ),
            waypoints = listOf(
                Place("a", "KLCC", "", pts.first()),
                Place("b", "Destino", "", pts.last())
            )
        )
    }

    private class RecordingVoicePlayer : VoiceGuidancePlayer {
        var warmedUp = false
        var stoppedCount = 0
        val spokenCues = mutableListOf<Pair<VoicePromptCue, Float>>()
        val chimeVolumes = mutableListOf<Float>()

        override fun warmup() {
            warmedUp = true
        }

        override fun speakCue(cue: VoicePromptCue, unitSystem: UnitSystem, volume: Float) {
            spokenCues.add(cue to volume)
        }

        override fun speakText(text: String, volume: Float, interrupt: Boolean) = Unit

        override fun playOverspeedChime(volume: Float) {
            chimeVolumes.add(volume)
        }

        override fun stopSpeaking() {
            stoppedCount += 1
        }

        override fun shutdown() = Unit
    }

    private class FakeSettingsStore(initial: AppSettings) : SettingsStore {
        private val flow = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = flow
        override suspend fun updateThemeMode(mode: ThemeMode) = Unit
        override suspend fun updateUnitSystem(units: UnitSystem) = Unit
        override suspend fun updateVoiceEnabled(enabled: Boolean) = Unit
        override suspend fun updateVoiceVolume(volume: Float) = Unit
        override suspend fun updateOverspeedToleranceKmh(toleranceKmh: Int) = Unit
        override suspend fun updateAvoidances(avoidances: RoutingAvoidances) = Unit
        override suspend fun updateEndpoints(endpoints: ServiceEndpoints) = Unit
        override suspend fun updateSimulationEnabled(enabled: Boolean) = Unit
        override suspend fun updateSimulationSpeedMultiplier(multiplier: Float) = Unit
        override suspend fun updateSimulationNoiseEnabled(enabled: Boolean) = Unit
        override suspend fun updateDeveloperDebugOverlayEnabled(enabled: Boolean) = Unit
    }

    private object NoOpTestRouter : Router {
        override suspend fun calculateRoutes(request: RouteRequest): Result<List<NavRoute>> {
            return Result.success(emptyList())
        }
    }

    private object FlatTrafficProvider : TrafficProvider {
        override suspend fun estimateRouteEta(route: NavRoute, departureTimeEpochMs: Long) =
            TrafficEstimate(route.baseDurationSeconds, 1.0, false, "estimated")

        override fun adaptRemainingEta(
            route: NavRoute,
            distanceAlongRouteMeters: Double,
            baseRemainingSeconds: Double,
            traveledObservedSeconds: Double,
            traveledExpectedSeconds: Double,
            departureTimeEpochMs: Long
        ) = TrafficEstimate(baseRemainingSeconds, 1.0, false, "estimated")
    }

    private class RecordingForegroundController : NavigationForegroundController {
        var startedCount = 0
        var stoppedCount = 0
        val observedStates = mutableListOf<NavigationSessionState>()

        override fun onNavigationStarted() {
            startedCount += 1
        }

        override fun onNavigationStateChanged(state: NavigationSessionState, unitSystem: UnitSystem) {
            observedStates.add(state)
        }

        override fun onNavigationStopped() {
            stoppedCount += 1
        }
    }
}
