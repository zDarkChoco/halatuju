package com.mbk.halatuju.core.navigation

import com.mbk.halatuju.core.location.LocationSourceCoordinator
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.MapCameraMode
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.routing.RouteRequest
import com.mbk.halatuju.core.routing.Router
import com.mbk.halatuju.core.routing.TrafficProvider
import com.mbk.halatuju.core.storage.SettingsStore
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
 * Single source of truth owning active turn-by-turn navigation lifecycle across screens and the
 * foreground notification service.
 */
@Singleton
class NavigationSessionManager @Inject constructor(
    private val locationSourceCoordinator: LocationSourceCoordinator,
    private val router: Router,
    private val trafficProvider: TrafficProvider,
    private val settingsStore: SettingsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineMutex = Mutex()
    private val engine = NavigationEngine()

    private val _state = MutableStateFlow(NavigationSessionState())
    val state: StateFlow<NavigationSessionState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private var rerouteJob: Job? = null
    private var lastCameraMode: MapCameraMode = MapCameraMode.NAVIGATION_25D
    private var isMuted: Boolean = false

    fun startNavigation(route: NavRoute) {
        sessionJob?.cancel()
        rerouteJob?.cancel()
        sessionJob = scope.launch {
            val settings = settingsStore.settings.first()
            isMuted = !settings.voiceEnabled
            lastCameraMode = MapCameraMode.NAVIGATION_25D

            val nowMs = System.currentTimeMillis()
            val clockMs = androidClockMs()
            engineMutex.withLock {
                val init = engine.startRoute(
                    route = route,
                    nowEpochMs = nowMs,
                    clockMs = clockMs,
                    cameraMode = lastCameraMode,
                    isMuted = isMuted
                )
                _state.value = init.state
            }

            val deadReckonLoop = launch {
                while (isActive) {
                    delay(1_000L)
                    val curSettings = settingsStore.settings.first()
                    engineMutex.withLock {
                        if (_state.value.status == NavigationStatus.ACTIVE) {
                            val dr = engine.onDeadReckonTick(
                                toleranceKmh = curSettings.overspeedToleranceKmh,
                                trafficProvider = trafficProvider,
                                nowEpochMs = System.currentTimeMillis(),
                                clockMs = androidClockMs(),
                                previousState = _state.value
                            )
                            if (dr != null) {
                                _state.value = dr.state
                            }
                        }
                    }
                }
            }

            try {
                locationSourceCoordinator.observeLocations(minIntervalMs = 1_000L).collect { fix ->
                    locationSourceCoordinator.recordLatestFix(fix)
                    val curSettings = settingsStore.settings.first()
                    val stepRes = engineMutex.withLock {
                        engine.onLocationFix(
                            fix = fix,
                            toleranceKmh = curSettings.overspeedToleranceKmh,
                            trafficProvider = trafficProvider,
                            cameraMode = lastCameraMode,
                            isMuted = isMuted,
                            nowEpochMs = System.currentTimeMillis(),
                            clockMs = androidClockMs(),
                            previousState = _state.value
                        )
                    }
                    _state.value = stepRes.state

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
                    nowEpochMs = System.currentTimeMillis(),
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
            // Keep all remaining stops from (legIndex + 1) onward through final destination
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
                    departureEpochMs = System.currentTimeMillis(),
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
                                nowEpochMs = System.currentTimeMillis(),
                                clockMs = androidClockMs(),
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

    private fun androidClockMs(): Long = android.os.SystemClock.elapsedRealtime()
}
