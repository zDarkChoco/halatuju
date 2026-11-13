package com.mbk.halatuju.feature.search

import com.mbk.halatuju.core.model.FavoriteKind
import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.RoutingAvoidances
import com.mbk.halatuju.core.model.UnitSystem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class SearchField {
    FROM,
    TO,
    STOP
}

sealed interface RouteEndpoint {
    data object MyLocation : RouteEndpoint
    data class SelectedPlace(val place: Place) : RouteEndpoint
}

data class DroppedPinState(
    val location: LatLng,
    val resolvedPlace: Place? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class RoutePreviewUiState(
    val isLoading: Boolean = false,
    val candidateRoutes: List<NavRoute> = emptyList(),
    val selectedRouteIndex: Int = 0,
    val errorMessage: String? = null,
    val referenceEpochMs: Long = 0L,
    val activeAvoidances: RoutingAvoidances = RoutingAvoidances()
) {
    val selectedRoute: NavRoute?
        get() = candidateRoutes.getOrNull(selectedRouteIndex) ?: candidateRoutes.firstOrNull()

    val hasContent: Boolean
        get() = isLoading || candidateRoutes.isNotEmpty() || errorMessage != null
}

data class SearchUiState(
    val fromEndpoint: RouteEndpoint = RouteEndpoint.MyLocation,
    val toEndpoint: RouteEndpoint? = null,
    val intermediateStops: List<Place> = emptyList(),
    val focusedField: SearchField? = null,
    val fromQueryText: String = MY_LOCATION_LABEL,
    val toQueryText: String = "",
    val stopQueryText: String = "",
    val pendingFavoriteAssignment: FavoriteKind? = null,
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val suggestions: List<Place> = emptyList(),
    val recents: List<Place> = emptyList(),
    val favorites: List<Place> = emptyList(),
    val droppedPin: DroppedPinState? = null,
    val routePreview: RoutePreviewUiState = RoutePreviewUiState(),
    val distanceAnchor: LatLng = KlDefaults.KLCC,
    val unitSystem: UnitSystem = UnitSystem.METRIC
) {
    val isFocused: Boolean
        get() = focusedField != null

    val activeQuery: String
        get() = when (focusedField) {
            SearchField.FROM -> fromQueryText
            SearchField.TO -> toQueryText
            SearchField.STOP -> stopQueryText
            null -> ""
        }

    val canSwap: Boolean
        get() = toEndpoint != null || fromEndpoint !is RouteEndpoint.MyLocation

    val isRoutePreviewVisible: Boolean
        get() = !isFocused && toEndpoint != null && droppedPin == null

    fun homeFavorite(): Place? = favorites.firstOrNull { it.favoriteKind == FavoriteKind.HOME }

    fun workFavorite(): Place? = favorites.firstOrNull { it.favoriteKind == FavoriteKind.WORK }

    fun savedFavorites(): List<Place> = favorites.filter { it.favoriteKind == FavoriteKind.NONE }

    companion object {
        const val MY_LOCATION_LABEL = "My location"
        const val MAX_INTERMEDIATE_STOPS = 4
    }
}

object SearchDistanceFormatter {
    fun formatDistance(
        from: LatLng,
        to: LatLng,
        unitSystem: UnitSystem
    ): String {
        val meters = GeoMath.haversineMeters(from, to)
        return RoutePreviewFormatter.formatDistanceMeters(meters, unitSystem)
    }
}

object RoutePreviewFormatter {
    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun formatDurationSeconds(seconds: Double): String {
        val totalMinutes = (seconds / 60.0).roundToInt().coerceAtLeast(0)
        return when {
            totalMinutes < 1 -> "< 1 min"
            totalMinutes < 60 -> "$totalMinutes min"
            else -> {
                val hours = totalMinutes / 60
                val mins = totalMinutes % 60
                if (mins == 0) "$hours h" else "$hours h $mins min"
            }
        }
    }

    fun formatDistanceMeters(meters: Double, unitSystem: UnitSystem): String {
        val safeMeters = meters.coerceAtLeast(0.0)
        return when (unitSystem) {
            UnitSystem.METRIC -> {
                if (safeMeters < 1000.0) {
                    "${((safeMeters / 10.0).toInt() * 10).coerceAtLeast(10)} m"
                } else if (safeMeters >= 100_000.0) {
                    "${(safeMeters / 1000.0).roundToInt()} km"
                } else {
                    String.format(Locale.US, "%.1f km", safeMeters / 1000.0)
                }
            }
            UnitSystem.IMPERIAL -> {
                val feet = safeMeters * 3.28084
                if (feet < 1000.0) {
                    "${((feet / 10.0).toInt() * 10).coerceAtLeast(10)} ft"
                } else {
                    String.format(Locale.US, "%.1f mi", safeMeters / 1609.344)
                }
            }
        }
    }

    fun formatArrivalClock(
        departureEpochMs: Long,
        durationSeconds: Double,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val baseMs = if (departureEpochMs > 0L) departureEpochMs else System.currentTimeMillis()
        val arrivalEpochMs = baseMs + (durationSeconds * 1000.0).roundToLong().coerceAtLeast(0L)
        return Instant.ofEpochMilli(arrivalEpochMs)
            .atZone(zoneId)
            .format(TIME_FORMATTER)
    }

    fun formatAvoidancesSummary(avoidances: RoutingAvoidances): List<String> = buildList {
        if (avoidances.avoidTolls) add("No tolls")
        if (avoidances.avoidHighways) add("No highways")
        if (avoidances.avoidFerries) add("No ferries")
    }
}
