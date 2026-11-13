package com.mbk.halatuju.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbk.halatuju.core.model.AppSettings
import com.mbk.halatuju.core.model.RoutingAvoidances
import com.mbk.halatuju.core.model.ServiceEndpoints
import com.mbk.halatuju.core.model.ThemeMode
import com.mbk.halatuju.core.model.UnitSystem
import com.mbk.halatuju.core.storage.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsStore.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AppSettings()
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsStore.updateThemeMode(mode) }
    }

    fun setUnitSystem(units: UnitSystem) {
        viewModelScope.launch { settingsStore.updateUnitSystem(units) }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.updateVoiceEnabled(enabled) }
    }

    fun setVoiceVolume(volume: Float) {
        viewModelScope.launch { settingsStore.updateVoiceVolume(volume) }
    }

    fun setOverspeedToleranceKmh(tolerance: Int) {
        viewModelScope.launch { settingsStore.updateOverspeedToleranceKmh(tolerance) }
    }

    fun setAvoidances(avoidances: RoutingAvoidances) {
        viewModelScope.launch { settingsStore.updateAvoidances(avoidances) }
    }

    fun setEndpoints(endpoints: ServiceEndpoints) {
        viewModelScope.launch { settingsStore.updateEndpoints(endpoints) }
    }

    fun setSimulationEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.updateSimulationEnabled(enabled) }
    }

    fun setSimulationSpeedMultiplier(multiplier: Float) {
        viewModelScope.launch { settingsStore.updateSimulationSpeedMultiplier(multiplier) }
    }

    fun setSimulationNoiseEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.updateSimulationNoiseEnabled(enabled) }
    }

    fun setDeveloperDebugOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.updateDeveloperDebugOverlayEnabled(enabled) }
    }
}
