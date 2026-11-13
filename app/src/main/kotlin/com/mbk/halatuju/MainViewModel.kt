package com.mbk.halatuju

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbk.halatuju.core.location.GpxRecorder
import com.mbk.halatuju.core.location.GpxReplayController
import com.mbk.halatuju.core.location.LocationPermissionHelper
import com.mbk.halatuju.core.location.LocationSourceCoordinator
import com.mbk.halatuju.core.location.PermissionStatusSnapshot
import com.mbk.halatuju.core.location.RouteSimulationController
import com.mbk.halatuju.core.location.SimulationRuntimeConfig
import com.mbk.halatuju.core.map.MapRenderingState
import com.mbk.halatuju.core.map.TileSource
import com.mbk.halatuju.core.model.AppSettings
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.LocationProviderKind
import com.mbk.halatuju.core.model.MapCameraMode
import com.mbk.halatuju.core.model.MapCameraState
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.PuckRenderState
import com.mbk.halatuju.core.navigation.NavigationSessionManager
import com.mbk.halatuju.core.navigation.NavigationStatus
import com.mbk.halatuju.core.storage.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val permissionStatus: PermissionStatusSnapshot = PermissionStatusSnapshot(
        hasFineLocation = false,
        hasAnyLocation = false,
        hasNotificationPermission = true,
        isLocationEnabledOnDevice = true,
        isIgnoringBatteryOptimizations = true
    ),
    val latestFix: LocationFix? = null,
    val cameraMode: MapCameraMode = MapCameraMode.FOLLOW_PUCK,
    val tiltPreviewEnabled: Boolean = false,
    val isGpxReplayActive: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val locationSourceCoordinator: LocationSourceCoordinator,
    private val routeSimulationController: RouteSimulationController,
    private val gpxReplayController: GpxReplayController,
    private val gpxRecorder: GpxRecorder,
    private val navigationSessionManager: NavigationSessionManager,
    val tileSource: TileSource
) : ViewModel() {

    private val permissionTick = MutableStateFlow(0)
    private val cameraMode = MutableStateFlow(MapCameraMode.FOLLOW_PUCK)
    private val tiltPreview = MutableStateFlow(false)

    private val runtimeConfigFlow = combine(
        settingsStore.settings,
        gpxReplayController.state
    ) { settings, replay ->
        settings to replay
    }

    init {
        viewModelScope.launch {
            runtimeConfigFlow.collect { (settings, replay) ->
                routeSimulationController.updateRuntimeConfig(
                    config = SimulationRuntimeConfig(
                        enabled = settings.simulationEnabled,
                        speedMultiplier = settings.simulationSpeedMultiplier,
                        noiseEnabled = settings.simulationNoiseEnabled
                    ),
                    hasActiveGpxReplay = replay.isActive
                )
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val locationFixFlow: StateFlow<LocationFix?> = combine(
        permissionTick,
        locationSourceCoordinator.hasActiveOverride,
        settingsStore.settings,
        navigationSessionManager.state.map { it.status == NavigationStatus.IDLE }.distinctUntilChanged()
    ) { _, hasOverride, settings, isNavIdle ->
        val hasPerm = LocationPermissionHelper.hasAnyLocationPermission(context)
        val canObserve = hasPerm || hasOverride || settings.simulationEnabled
        canObserve to isNavIdle
    }.distinctUntilChanged().flatMapLatest { (canObserve, isNavIdle) ->
        if (!canObserve) {
            flowOf(null)
        } else if (!isNavIdle) {
            // Active navigation already owns the single live/simulated location collector inside NavigationSessionManager.
            locationSourceCoordinator.latestFix
        } else {
            flow {
                val hasPerm = LocationPermissionHelper.hasAnyLocationPermission(context)
                val initial = locationSourceCoordinator.latestFix.value ?: if (!hasPerm) {
                    LocationFix(
                        position = KlDefaults.KLCC,
                        accuracyMeters = 8f,
                        speedMps = 0f,
                        bearingDegrees = 0f,
                        timestampMs = System.currentTimeMillis(),
                        provider = LocationProviderKind.SIMULATED
                    ).also { locationSourceCoordinator.recordLatestFix(it) }
                } else {
                    null
                }
                emit(initial)
                locationSourceCoordinator.observeLocations(minIntervalMs = 1000L).collect { fix ->
                    locationSourceCoordinator.recordLatestFix(fix)
                    gpxRecorder.recordFix(fix)
                    emit(fix)
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val uiState: StateFlow<MainUiState> = combine(
        runtimeConfigFlow,
        permissionTick,
        locationFixFlow,
        cameraMode,
        tiltPreview
    ) { (settings, replay), _, fix, camMode, tilt ->
        MainUiState(
            settings = settings,
            permissionStatus = LocationPermissionHelper.snapshot(context),
            latestFix = fix,
            cameraMode = camMode,
            tiltPreviewEnabled = tilt,
            isGpxReplayActive = replay.isActive
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MainUiState(
            permissionStatus = LocationPermissionHelper.snapshot(context)
        )
    )

    fun refreshPermissions() {
        permissionTick.value += 1
    }

    fun onUserPanMap() {
        cameraMode.value = MapCameraMode.FREE_PAN
    }

    fun enterRouteOverview() {
        cameraMode.value = MapCameraMode.OVERVIEW
    }

    fun returnToFollowPuck() {
        if (cameraMode.value == MapCameraMode.OVERVIEW) {
            cameraMode.value = MapCameraMode.FOLLOW_PUCK
        }
    }

    fun onRecenterClick() {
        if (cameraMode.value == MapCameraMode.FREE_PAN || cameraMode.value == MapCameraMode.OVERVIEW) {
            cameraMode.value = MapCameraMode.FOLLOW_PUCK
        } else {
            tiltPreview.value = !tiltPreview.value
        }
    }

    fun toMapRenderingState(
        state: MainUiState,
        isDarkTheme: Boolean,
        waypoints: List<Place> = emptyList(),
        droppedPin: Place? = null,
        activeRoute: NavRoute? = null,
        alternativeRoutes: List<NavRoute> = emptyList(),
        selectedRouteIndex: Int = 0
    ): MapRenderingState {
        val fix = state.latestFix
        val center = fix?.position ?: KlDefaults.KLCC
        val puck = fix?.let {
            PuckRenderState(
                position = it.position,
                bearingDegrees = it.bearingDegrees ?: 0f,
                accuracyMeters = it.accuracyMeters
            )
        }
        return MapRenderingState(
            isDarkTheme = isDarkTheme,
            endpoints = state.settings.endpoints,
            cameraState = MapCameraState(
                mode = state.cameraMode,
                center = center,
                zoom = 15.6,
                bearingDegrees = if (state.tiltPreviewEnabled) (fix?.bearingDegrees?.toDouble() ?: 0.0) else 0.0,
                tiltDegrees = if (state.tiltPreviewEnabled) 48.0 else 0.0
            ),
            puckState = puck,
            activeRoute = activeRoute,
            alternativeRoutes = alternativeRoutes,
            selectedRouteIndex = selectedRouteIndex,
            waypoints = waypoints,
            droppedPin = droppedPin,
            debugOverlayEnabled = state.settings.developerDebugOverlayEnabled
        )
    }
}
