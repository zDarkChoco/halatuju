package com.mbk.halatuju.feature.search

import com.mbk.halatuju.core.model.FavoriteKind
import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.UnitSystem
import java.util.Locale

enum class SearchField {
    FROM,
    TO
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

data class SearchUiState(
    val fromEndpoint: RouteEndpoint = RouteEndpoint.MyLocation,
    val toEndpoint: RouteEndpoint? = null,
    val intermediateStops: List<Place> = emptyList(),
    val focusedField: SearchField? = null,
    val fromQueryText: String = MY_LOCATION_LABEL,
    val toQueryText: String = "",
    val pendingFavoriteAssignment: FavoriteKind? = null,
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val suggestions: List<Place> = emptyList(),
    val recents: List<Place> = emptyList(),
    val favorites: List<Place> = emptyList(),
    val droppedPin: DroppedPinState? = null,
    val distanceAnchor: LatLng = KlDefaults.KLCC,
    val unitSystem: UnitSystem = UnitSystem.METRIC
) {
    val isFocused: Boolean
        get() = focusedField != null

    val activeQuery: String
        get() = when (focusedField) {
            SearchField.FROM -> fromQueryText
            SearchField.TO -> toQueryText
            null -> ""
        }

    val canSwap: Boolean
        get() = toEndpoint != null || fromEndpoint !is RouteEndpoint.MyLocation

    fun homeFavorite(): Place? = favorites.firstOrNull { it.favoriteKind == FavoriteKind.HOME }

    fun workFavorite(): Place? = favorites.firstOrNull { it.favoriteKind == FavoriteKind.WORK }

    fun savedFavorites(): List<Place> = favorites.filter { it.favoriteKind == FavoriteKind.NONE }

    companion object {
        const val MY_LOCATION_LABEL = "My location"
    }
}

object SearchDistanceFormatter {
    fun formatDistance(
        from: LatLng,
        to: LatLng,
        unitSystem: UnitSystem
    ): String {
        val meters = GeoMath.haversineMeters(from, to)
        return when (unitSystem) {
            UnitSystem.METRIC -> {
                if (meters < 1000.0) {
                    "${((meters / 10.0).toInt() * 10).coerceAtLeast(10)} m"
                } else {
                    String.format(Locale.US, "%.1f km", meters / 1000.0)
                }
            }
            UnitSystem.IMPERIAL -> {
                val feet = meters * 3.28084
                if (feet < 1000.0) {
                    "${((feet / 10.0).toInt() * 10).coerceAtLeast(10)} ft"
                } else {
                    String.format(Locale.US, "%.1f mi", meters / 1609.344)
                }
            }
        }
    }
}
