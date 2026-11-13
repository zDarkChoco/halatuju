package com.mbk.halatuju.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mbk.halatuju.core.model.AppSettings
import com.mbk.halatuju.core.model.RoutingAvoidances
import com.mbk.halatuju.core.model.ServiceEndpoints
import com.mbk.halatuju.core.model.ThemeMode
import com.mbk.halatuju.core.model.UnitSystem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.halatujuSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "halatuju_settings"
)

interface SettingsStore {
    val settings: Flow<AppSettings>

    suspend fun updateThemeMode(mode: ThemeMode)
    suspend fun updateUnitSystem(units: UnitSystem)
    suspend fun updateVoiceEnabled(enabled: Boolean)
    suspend fun updateVoiceVolume(volume: Float)
    suspend fun updateOverspeedToleranceKmh(toleranceKmh: Int)
    suspend fun updateAvoidances(avoidances: RoutingAvoidances)
    suspend fun updateEndpoints(endpoints: ServiceEndpoints)
    suspend fun updateSimulationEnabled(enabled: Boolean)
    suspend fun updateSimulationSpeedMultiplier(multiplier: Float)
    suspend fun updateSimulationNoiseEnabled(enabled: Boolean)
    suspend fun updateDeveloperDebugOverlayEnabled(enabled: Boolean)
}

@Singleton
class DataStoreSettingsStore(
    private val dataStore: DataStore<Preferences>
) : SettingsStore {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.applicationContext.halatujuSettingsDataStore
    )

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { err ->
            if (err is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw err
        }
        .map { prefs -> prefs.toAppSettings() }

    override suspend fun updateThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    override suspend fun updateUnitSystem(units: UnitSystem) {
        dataStore.edit { it[Keys.UNIT_SYSTEM] = units.name }
    }

    override suspend fun updateVoiceEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.VOICE_ENABLED] = enabled }
    }

    override suspend fun updateVoiceVolume(volume: Float) {
        dataStore.edit { it[Keys.VOICE_VOLUME] = volume.coerceIn(0f, 1f) }
    }

    override suspend fun updateOverspeedToleranceKmh(toleranceKmh: Int) {
        dataStore.edit { it[Keys.OVERSPEED_TOLERANCE_KMH] = toleranceKmh.coerceIn(0, 30) }
    }

    override suspend fun updateAvoidances(avoidances: RoutingAvoidances) {
        dataStore.edit {
            it[Keys.AVOID_TOLLS] = avoidances.avoidTolls
            it[Keys.AVOID_HIGHWAYS] = avoidances.avoidHighways
            it[Keys.AVOID_FERRIES] = avoidances.avoidFerries
        }
    }

    override suspend fun updateEndpoints(endpoints: ServiceEndpoints) {
        val defaults = ServiceEndpoints()
        dataStore.edit {
            it[Keys.LIGHT_STYLE_URL] = endpoints.lightStyleUrl.ifBlank { defaults.lightStyleUrl }
            it[Keys.DARK_TILE_URL] = endpoints.darkTileSourceUrl.ifBlank { defaults.darkTileSourceUrl }
            it[Keys.VALHALLA_URL] = endpoints.valhallaBaseUrl.ifBlank { defaults.valhallaBaseUrl }
            it[Keys.PHOTON_URL] = endpoints.photonBaseUrl.ifBlank { defaults.photonBaseUrl }
            it[Keys.NOMINATIM_URL] = endpoints.nominatimBaseUrl.ifBlank { defaults.nominatimBaseUrl }
        }
    }

    override suspend fun updateSimulationEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SIM_ENABLED] = enabled }
    }

    override suspend fun updateSimulationSpeedMultiplier(multiplier: Float) {
        dataStore.edit { it[Keys.SIM_SPEED_MULT] = multiplier.coerceIn(0.5f, 8.0f) }
    }

    override suspend fun updateSimulationNoiseEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SIM_NOISE] = enabled }
    }

    override suspend fun updateDeveloperDebugOverlayEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.DEV_DEBUG_OVERLAY] = enabled }
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = this[Keys.THEME_MODE]?.let { name ->
                ThemeMode.entries.firstOrNull { it.name == name }
            } ?: defaults.themeMode,
            unitSystem = this[Keys.UNIT_SYSTEM]?.let { name ->
                UnitSystem.entries.firstOrNull { it.name == name }
            } ?: defaults.unitSystem,
            voiceEnabled = this[Keys.VOICE_ENABLED] ?: defaults.voiceEnabled,
            voiceVolume = this[Keys.VOICE_VOLUME] ?: defaults.voiceVolume,
            overspeedToleranceKmh = this[Keys.OVERSPEED_TOLERANCE_KMH] ?: defaults.overspeedToleranceKmh,
            avoidances = RoutingAvoidances(
                avoidTolls = this[Keys.AVOID_TOLLS] ?: defaults.avoidances.avoidTolls,
                avoidHighways = this[Keys.AVOID_HIGHWAYS] ?: defaults.avoidances.avoidHighways,
                avoidFerries = this[Keys.AVOID_FERRIES] ?: defaults.avoidances.avoidFerries
            ),
            endpoints = ServiceEndpoints(
                lightStyleUrl = this[Keys.LIGHT_STYLE_URL] ?: defaults.endpoints.lightStyleUrl,
                darkTileSourceUrl = this[Keys.DARK_TILE_URL] ?: defaults.endpoints.darkTileSourceUrl,
                valhallaBaseUrl = this[Keys.VALHALLA_URL] ?: defaults.endpoints.valhallaBaseUrl,
                photonBaseUrl = this[Keys.PHOTON_URL] ?: defaults.endpoints.photonBaseUrl,
                nominatimBaseUrl = this[Keys.NOMINATIM_URL] ?: defaults.endpoints.nominatimBaseUrl
            ),
            simulationEnabled = this[Keys.SIM_ENABLED] ?: defaults.simulationEnabled,
            simulationSpeedMultiplier = this[Keys.SIM_SPEED_MULT] ?: defaults.simulationSpeedMultiplier,
            simulationNoiseEnabled = this[Keys.SIM_NOISE] ?: defaults.simulationNoiseEnabled,
            developerDebugOverlayEnabled = this[Keys.DEV_DEBUG_OVERLAY] ?: defaults.developerDebugOverlayEnabled
        )
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val UNIT_SYSTEM = stringPreferencesKey("unit_system")
        val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
        val VOICE_VOLUME = floatPreferencesKey("voice_volume")
        val OVERSPEED_TOLERANCE_KMH = intPreferencesKey("overspeed_tolerance_kmh")
        val AVOID_TOLLS = booleanPreferencesKey("avoid_tolls")
        val AVOID_HIGHWAYS = booleanPreferencesKey("avoid_highways")
        val AVOID_FERRIES = booleanPreferencesKey("avoid_ferries")
        val LIGHT_STYLE_URL = stringPreferencesKey("light_style_url")
        val DARK_TILE_URL = stringPreferencesKey("dark_tile_source_url")
        val VALHALLA_URL = stringPreferencesKey("valhalla_base_url")
        val PHOTON_URL = stringPreferencesKey("photon_base_url")
        val NOMINATIM_URL = stringPreferencesKey("nominatim_base_url")
        val SIM_ENABLED = booleanPreferencesKey("simulation_enabled")
        val SIM_SPEED_MULT = floatPreferencesKey("simulation_speed_multiplier")
        val SIM_NOISE = booleanPreferencesKey("simulation_noise_enabled")
        val DEV_DEBUG_OVERLAY = booleanPreferencesKey("developer_debug_overlay_enabled")
    }
}
