package com.mbk.halatuju.core.navigation

import com.mbk.halatuju.core.location.LocationSource
import com.mbk.halatuju.core.location.LocationSourceCoordinator
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.MapCameraMode
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.UnitSystem
import com.mbk.halatuju.core.model.VoicePromptCue
import com.mbk.halatuju.core.routing.RouteRequest
import com.mbk.halatuju.core.routing.Router
import com.mbk.halatuju.core.routing.TrafficProvider
import com.mbk.halatuju.core.storage.SettingsStore
import com.mbk.halatuju.core.voice.NoOpVoiceGuidancePlayer
import com.mbk.halatuju.core.voice.VoiceGuidancePlayer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth owning active turn-by-turn navigation lifecycle across screens,
 * voice TTS / overspeed chime playback, and the foreground notification service.
 */
@Singleton
class NavigationSessionManager internal constructor(
    private val locationSource: LocationSource,
    private val onFixObserved: (LocationFix) -> Unit,
    private val router: Router,
    private val trafficProvider: TrafficProvider,
    private val settingsStore: SettingsStore,
    private val voiceGuidancePlayer: VoiceGuidancePlayer = NoOpVoiceGuidancePlayer,
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val epochMs: () -> Long = { System.currentTimeMillis() },
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    @Inject
    constructor(
        locationSourceCoordinator: LocationSourceCoordinator,
        router: Router,
        trafficProvider: TrafficProvider,
        settingsStore: SettingsStore,
        voiceGuidancePlayer: VoiceGuidancePlayer
    ) : this(
        locationSource = locationSourceCoordinator,
        onFixObserved = { fix -> locationSourceCoordinator.recordLatestFix(fix) },
        router = router,
        trafficProvider = trafficProvider,
        settingsStore = settingsStore,
        voiceGuidancePlayer = voiceGuidancePlayer
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val engineMutex = Mutex()
    private val engine = NavigationEngine()

    private val _state = MutableStateFlow(NavigationSessionState())
    val state: StateFlow<NavigationSessionState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private var rerouteJob: Job? = null
    private var lastCameraMode: MapCameraMode = MapCameraMode.NAVIGATION_25D
    private var isMuted: Boolean = false
    private var voiceVolume: Float = 1.0f

    fun startNavigation(route: NavRoute) {
        sessionJob?.cancel()
        rerouteJob?.cancel()
        voiceGuidancePlayer.warmup()
        sessionJob = scope.launch {
            val settings = settingsStore.settings.first()
            isMuted = !settings.voiceEnabled
            voiceVolume = settings.voiceVolume
            lastCameraMode = MapCameraMode.NAVIGATION_25D

            val nowEpoch = epochMs()
            val nowClock = clockMs()
            val prevChimeId: Long
            val initResult = engineMutex.withLock {
                prevChimeId = _state.value.overspeed.chimeTriggerId
                val init = engine.startRoute(
                    route = route,
                    nowEpochMs = nowEpoch,
                    clockMs = nowClock,
                    cameraMode = lastCameraMode,
                    isMuted = isMuted
                )
                _state.value = init.state
                init
            }
            dispatchAudioFeedback(
                previousChimeId = prevChimeId,
                nextState = initResult.state,
                cue = initResult.voiceCue,
                unitSystem = settings.unitSystem,
                volume = voiceVolume
            )

            val deadReckonLoop = launch {
                while (isActive) {
                    delay(1_000L)
                    val curSettings = settingsStore.settings.first()
                    voiceVolume = curSettings.voiceVolume
                    var drPrevChime = 0L
                    val drResult = engineMutex.withLock {
                        if (_state.value.status == NavigationStatus.ACTIVE) {
                            drPrevChime = _state.value.overspeed.chimeTriggerId
                            val dr = engine.onDeadReckonTick(
                                toleranceKmh = curSettings.overspeedToleranceKmh,
                                trafficProvider = trafficProvider,
                                nowEpochMs = epochMs(),
                                clockMs = clockMs(),
                                previousState = _state.value
                            )
                            if (dr != null) {
                                _state.value = dr.state
                            }
                            dr
                        } else {
                            null
                        }
                    }
                    if (drResult != null) {
                        dispatchAudioFeedback(
                            previousChimeId = drPrevChime,
                            nextState = drResult.state,
                            cue = drResult.voiceCue,
                            unitSystem = curSettings.unitSystem,
                            volume = voiceVolume
                        )
                    }
                }
            }

            try {
                locationSource.observeLocations(minIntervalMs = 1_000L).collect { fix ->
                    onFixObserved(fix)
                    val curSettings = settingsStore.settings.first()
                    voiceVolume = curSettings.voiceVolume

                    var fixPrevChime = 0L
                    val stepRes = engineMutex.withLock {
                        fixPrevChime = _state.value.overspeed.chimeTriggerId
                        engine.onLocationFix(
                            fix = fix,
                            toleranceKmh = curSettings.overspeedToleranceKmh,
                            trafficProvider = trafficProvider,
                            cameraMode = lastCameraMode,
                            isMuted = isMuted,
                            nowEpochMs = epochMs(),
                            clockMs = clockMs(),
                            previousState = _state.value
                        )
                    }
                    _state.value = stepRes.state
                    dispatchAudioFeedback(
                        previousChimeId = fixPrevChime,
                        nextState = stepRes.state,
                        cue = stepRes.voiceCue,
                        unitSystem = curSettings.unitSystem,
                        volume = voiceVolume
                    )

                    if (stepRes.shouldRequestReroute && stepRes.rerouteOrigin != null) {
                        triggerReroute(
                            origin = stepRes.rerouteOrigin,
                            headingDegrees = stepRes.rerouteBearingDegrees,
                            legIndex = stepRes.state.currentLegIndex
                        )
                    }
                }
            } finally {
                deadReckonLoop.cancel()
            }
        }
    }

    fun endNavigation() {
        sessionJob?.cancel()
        rerouteJob?.cancel()
        sessionJob = null
        rerouteJob = null
        voiceGuidancePlayer.stopSpeaking()
        scope.launch {
            engineMutex.withLock {
                _state.value = engine.stop()
            }
        }
    }

    fun continueToNextStop() {
        scope.launch {
            engineMutex.withLock {
                _state.value = engine.advancePastIntermediateStop(
                    nowEpochMs = epochMs(),
                    currentState = _state.value
                )
            }
        }
    }

    fun setCameraMode(mode: MapCameraMode) {
        lastCameraMode = mode
        _state.value = _state.value.copy(
            cameraMode = mode,
            cameraState = _state.value.cameraState.copy(
                mode = mode,
                tiltDegrees = if (mode == MapCameraMode.NAVIGATION_25D) 52.0 else 0.0
            )
        )
    }

    fun toggleMute() {
        isMuted = !isMuted
        _state.value = _state.value.copy(isMuted = isMuted)
        if (isMuted) {
            voiceGuidancePlayer.stopSpeaking()
        }
    }

    private fun dispatchAudioFeedback(
        previousChimeId: Long,
        nextState: NavigationSessionState,
        cue: VoicePromptCue?,
        unitSystem: UnitSystem,
        volume: Float
    ) {
        if (nextState.isMuted || volume <= 0.01f) return
        if (nextState.overspeed.chimeTriggerId > previousChimeId) {
            voiceGuidancePlayer.playOverspeedChime(volume)
        }
        if (cue != null) {
            voiceGuidancePlayer.speakCue(
                cue = cue,
                unitSystem = unitSystem,
                volume = volume
            )
        }
    }

    private fun triggerReroute(
        origin: LatLng,
        headingDegrees: Double?,
        legIndex: Int
    ) {
        if (rerouteJob?.isActive == true) return
        rerouteJob = scope.launch {
            val currentRoute = _state.value.route ?: return@launch
            val settings = settingsStore.settings.first()
            val originPlace = Place(
                id = "reroute:origin",
                name = "Current location",
                subtitle = "",
                location = origin
            )
            val remainingStops = currentRoute.waypoints.drop(legIndex + 1)
            if (remainingStops.isEmpty()) return@launch

            engineMutex.withLock {
                engine.setRerouting(true)
                _state.value = _state.value.copy(isRerouting = true)
            }

            val result = router.calculateRoutes(
                RouteRequest(
                    waypoints = listOf(originPlace) + remainingStops,
                    avoidances = settings.avoidances,
                    preferAlternates = false,
                    departureEpochMs = epochMs(),
                    vehicleHeadingDegrees = headingDegrees,
                    baseUrlOverride = settings.endpoints.valhallaBaseUrl
                )
            )

            engineMutex.withLock {
                result.fold(
                    onSuccess = { newRoutes ->
                        val newRoute = newRoutes.firstOrNull()
                        if (newRoute != null && _state.value.status != NavigationStatus.IDLE) {
                            _state.value = engine.applyReroutedRoute(
                                newRoute = newRoute,
                                nowEpochMs = epochMs(),
                                clockMs = clockMs(),
                                currentState = _state.value
                            )
                        } else {
                            engine.setRerouting(false)
                            _state.value = _state.value.copy(isRerouting = false)
                        }
                    },
                    onFailure = {
                        engine.setRerouting(false)
                        _state.value = _state.value.copy(isRerouting = false)
                    }
                )
            }
        }
    }
}
