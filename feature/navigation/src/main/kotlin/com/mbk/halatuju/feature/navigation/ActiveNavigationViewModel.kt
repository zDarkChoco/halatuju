package com.mbk.halatuju.feature.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbk.halatuju.core.map.MapRenderingState
import com.mbk.halatuju.core.map.TileSource
import com.mbk.halatuju.core.model.AppSettings
import com.mbk.halatuju.core.model.MapCameraMode
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.navigation.NavigationSessionManager
import com.mbk.halatuju.core.navigation.NavigationSessionState
import com.mbk.halatuju.core.navigation.NavigationStatus
import com.mbk.halatuju.core.storage.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ActiveNavigationUiState(
    val session: NavigationSessionState = NavigationSessionState(),
    val settings: AppSettings = AppSettings()
) {
    val isNavigationActive: Boolean
        get() = session.status == NavigationStatus.ACTIVE || session.status == NavigationStatus.ARRIVED_STOP

    val keepScreenOn: Boolean
        get() = session.status == NavigationStatus.ACTIVE || session.status == NavigationStatus.ARRIVED_STOP

    fun toMapRenderingState(isDarkTheme: Boolean): MapRenderingState {
        return MapRenderingState(
            isDarkTheme = isDarkTheme,
            endpoints = settings.endpoints,
            cameraState = session.cameraState,
            puckState = session.puckState,
            activeRoute = session.route,
            alternativeRoutes = emptyList(),
            selectedRouteIndex = 0,
            traveledDistanceMeters = session.distanceAlongRouteMeters,
            waypoints = session.route?.waypoints.orEmpty(),
            droppedPin = null,
            debugOverlayEnabled = settings.developerDebugOverlayEnabled,
            debugOverlayData = session.debugOverlayData
        )
    }
}

@HiltViewModel
class ActiveNavigationViewModel @Inject constructor(
    private val sessionManager: NavigationSessionManager,
    settingsStore: SettingsStore,
    val tileSource: TileSource
) : ViewModel() {

    val uiState: StateFlow<ActiveNavigationUiState> = combine(
        sessionManager.state,
        settingsStore.settings
    ) { session, settings ->
        ActiveNavigationUiState(
            session = session,
            settings = settings
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ActiveNavigationUiState(session = sessionManager.state.value)
    )

    fun startNavigation(route: NavRoute) {
        sessionManager.startNavigation(route)
    }

    fun endNavigation() {
        sessionManager.endNavigation()
    }

    fun onContinueToNextStop() {
        sessionManager.continueToNextStop()
    }

    fun onToggleOverview() {
        val current = sessionManager.state.value.cameraMode
        val next = if (current == MapCameraMode.OVERVIEW) {
            MapCameraMode.NAVIGATION_25D
        } else {
            MapCameraMode.OVERVIEW
        }
        sessionManager.setCameraMode(next)
    }

    fun onRecenterClick() {
        sessionManager.setCameraMode(MapCameraMode.NAVIGATION_25D)
    }

    fun onUserPanMap() {
        if (sessionManager.state.value.cameraMode != MapCameraMode.FREE_PAN) {
            sessionManager.setCameraMode(MapCameraMode.FREE_PAN)
        }
    }

    fun onToggleMute() {
        sessionManager.toggleMute()
    }
}
