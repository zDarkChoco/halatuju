package com.mbk.halatuju

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbk.halatuju.core.location.LocationPermissionHelper
import com.mbk.halatuju.core.location.LocationSourceCoordinator
import com.mbk.halatuju.core.location.PermissionStatusSnapshot
import com.mbk.halatuju.core.map.MapRenderingState
import com.mbk.halatuju.core.map.TileSource
import com.mbk.halatuju.core.model.AppSettings
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.MapCameraMode
import com.mbk.halatuju.core.model.MapCameraState
import com.mbk.halatuju.core.model.PuckRenderState
import com.mbk.halatuju.core.storage.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
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
    val tiltPreviewEnabled: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val locationSourceCoordinator: LocationSourceCoordinator,
    val tileSource: TileSource
) : ViewModel() {

    private val permissionTick = MutableStateFlow(0)
    private val cameraMode = MutableStateFlow(MapCameraMode.FOLLOW_PUCK)
    private val tiltPreview = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val locationFixFlow: StateFlow<LocationFix?> = permissionTick
        .flatMapLatest {
            if (LocationPermissionHelper.hasAnyLocationPermission(context)) {
                kotlinx.coroutines.flow.flow<LocationFix?> {
                    emit(locationSourceCoordinator.latestFix.value)
                    locationSourceCoordinator.observeLocations(minIntervalMs = 1000L).collect { fix ->
                        locationSourceCoordinator.recordLatestFix(fix)
                        emit(fix)
                    }
                }
            } else {
                flowOf(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val uiState: StateFlow<MainUiState> = combine(
        settingsStore.settings,
        permissionTick,
        locationFixFlow,
        cameraMode,
        tiltPreview
    ) { settings, _, fix, camMode, tilt ->
        MainUiState(
            settings = settings,
            permissionStatus = LocationPermissionHelper.snapshot(context),
            latestFix = fix,
            cameraMode = camMode,
            tiltPreviewEnabled = tilt
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

    fun onRecenterClick() {
        if (cameraMode.value == MapCameraMode.FREE_PAN) {
            cameraMode.value = MapCameraMode.FOLLOW_PUCK
        } else {
            tiltPreview.value = !tiltPreview.value
        }
    }

    fun toMapRenderingState(
        state: MainUiState,
        isDarkTheme: Boolean
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
            debugOverlayEnabled = state.settings.developerDebugOverlayEnabled
        )
    }
}
