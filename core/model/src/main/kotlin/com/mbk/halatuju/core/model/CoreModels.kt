package com.mbk.halatuju.core.model

import kotlinx.serialization.Serializable

object AppInfo {
    const val VERSION_NAME = "0.1.0"
    const val USER_AGENT = "HalaTuju/0.1.0 (Android; +https://github.com/zDarkChoco/halatuju)"
}

/**
 * Default Kuala Lumpur (KLCC / Petronas Twin Towers) anchor used when no GPS fix is yet available.
 */
object KlDefaults {
    val KLCC = LatLng(3.1579, 101.7116)
    val KLIA = LatLng(2.7456, 101.7099)
    val KL_SENTRAL = LatLng(3.1341, 101.6860)
}

@Serializable
data class LatLng(
    val latitude: Double,
    val longitude: Double
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid latitude: $latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude: $longitude" }
    }
}

@Serializable
data class GeoBounds(
    val southWest: LatLng,
    val northEast: LatLng
)

@Serializable
enum class ThemeMode {
    FOLLOW_SYSTEM,
    LIGHT,
    DARK
}

@Serializable
enum class UnitSystem {
    METRIC,
    IMPERIAL
}

@Serializable
enum class FavoriteKind {
    NONE,
    HOME,
    WORK
}

@Serializable
data class Place(
    val id: String,
    val name: String,
    val subtitle: String,
    val location: LatLng,
    val favoriteKind: FavoriteKind = FavoriteKind.NONE,
    val lastUsedEpochMs: Long = 0L
)

@Serializable
data class ServiceEndpoints(
    val lightStyleUrl: String = "https://tiles.openfreemap.org/styles/liberty",
    val darkTileSourceUrl: String = "https://tiles.openfreemap.org/planet",
    val valhallaBaseUrl: String = "https://valhalla1.openstreetmap.de",
    val photonBaseUrl: String = "https://photon.komoot.io",
    val nominatimBaseUrl: String = "https://nominatim.openstreetmap.org"
)

@Serializable
data class RoutingAvoidances(
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false
)

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val voiceEnabled: Boolean = true,
    val voiceVolume: Float = 1.0f,
    val overspeedToleranceKmh: Int = 5,
    val avoidances: RoutingAvoidances = RoutingAvoidances(),
    val endpoints: ServiceEndpoints = ServiceEndpoints(),
    val simulationEnabled: Boolean = false,
    val simulationSpeedMultiplier: Float = 1.0f,
    val simulationNoiseEnabled: Boolean = true,
    val developerDebugOverlayEnabled: Boolean = false
)
