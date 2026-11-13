package com.mbk.halatuju.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbk.halatuju.core.geocoding.Geocoder
import com.mbk.halatuju.core.geocoding.SearchDebouncer
import com.mbk.halatuju.core.location.LocationSourceCoordinator
import com.mbk.halatuju.core.model.AppSettings
import com.mbk.halatuju.core.model.FavoriteKind
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.storage.PlaceRepository
import com.mbk.halatuju.core.storage.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

private data class DraftSearchState(
    val fromEndpoint: RouteEndpoint = RouteEndpoint.MyLocation,
    val toEndpoint: RouteEndpoint? = null,
    val intermediateStops: List<Place> = emptyList(),
    val focusedField: SearchField? = null,
    val fromQueryText: String = SearchUiState.MY_LOCATION_LABEL,
    val toQueryText: String = "",
    val pendingFavoriteAssignment: FavoriteKind? = null,
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val suggestions: List<Place> = emptyList(),
    val droppedPin: DroppedPinState? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val geocoder: Geocoder,
    private val placeRepository: PlaceRepository,
    private val settingsStore: SettingsStore,
    private val locationSourceCoordinator: LocationSourceCoordinator
) : ViewModel() {

    private val draft = MutableStateFlow(DraftSearchState())
    private val rawQueryFlow = MutableStateFlow("")
    private var latestSettings: AppSettings = AppSettings()

    val uiState: StateFlow<SearchUiState> = combine(
        draft,
        placeRepository.observeRecents(limit = 15),
        placeRepository.observeFavorites(),
        locationSourceCoordinator.latestFix,
        settingsStore.settings
    ) { d, recents, favorites, latestFix, settings ->
        latestSettings = settings
        SearchUiState(
            fromEndpoint = d.fromEndpoint,
            toEndpoint = d.toEndpoint,
            intermediateStops = d.intermediateStops,
            focusedField = d.focusedField,
            fromQueryText = d.fromQueryText,
            toQueryText = d.toQueryText,
            pendingFavoriteAssignment = d.pendingFavoriteAssignment,
            isSearching = d.isSearching,
            searchError = d.searchError,
            suggestions = d.suggestions,
            recents = recents,
            favorites = favorites,
            droppedPin = d.droppedPin,
            distanceAnchor = latestFix?.position ?: KlDefaults.KLCC,
            unitSystem = settings.unitSystem
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SearchUiState()
    )

    init {
        viewModelScope.launch {
            SearchDebouncer.debounceQueries(rawQueryFlow, SearchDebouncer.DEFAULT_DEBOUNCE_MS)
                .collectLatest { trimmed ->
                    val focused = draft.value.focusedField
                    if (focused == null ||
                        trimmed.length < 2 ||
                        trimmed.equals(SearchUiState.MY_LOCATION_LABEL, ignoreCase = true)
                    ) {
                        draft.update {
                            it.copy(
                                isSearching = false,
                                searchError = null,
                                suggestions = emptyList()
                            )
                        }
                        return@collectLatest
                    }

                    draft.update { it.copy(isSearching = true, searchError = null) }
                    val bias = locationSourceCoordinator.latestFix.value?.position ?: KlDefaults.KLCC
                    val photonUrl = latestSettings.endpoints.photonBaseUrl
                    val result = geocoder.search(
                        query = trimmed,
                        bias = bias,
                        limit = 8,
                        baseUrlOverride = photonUrl
                    )
                    result.fold(
                        onSuccess = { places ->
                            draft.update {
                                it.copy(
                                    isSearching = false,
                                    searchError = null,
                                    suggestions = places
                                )
                            }
                        },
                        onFailure = { error ->
                            draft.update {
                                it.copy(
                                    isSearching = false,
                                    searchError = error.message ?: "Unable to fetch places right now",
                                    suggestions = emptyList()
                                )
                            }
                        }
                    )
                }
        }
    }

    fun onFieldFocused(field: SearchField) {
        draft.update { current ->
            if (current.focusedField == field) return@update current
            val updatedFrom = if (field == SearchField.FROM && current.fromEndpoint is RouteEndpoint.MyLocation) {
                ""
            } else {
                endpointLabel(current.fromEndpoint, defaultLabel = SearchUiState.MY_LOCATION_LABEL)
            }
            val updatedTo = if (field == SearchField.TO && current.toEndpoint is RouteEndpoint.MyLocation) {
                ""
            } else {
                endpointLabel(current.toEndpoint, defaultLabel = "")
            }
            current.copy(
                focusedField = field,
                fromQueryText = updatedFrom,
                toQueryText = updatedTo,
                searchError = null,
                suggestions = emptyList()
            )
        }
        val activeQuery = when (field) {
            SearchField.FROM -> draft.value.fromQueryText
            SearchField.TO -> draft.value.toQueryText
        }
        rawQueryFlow.value = activeQuery
    }

    fun onQueryChanged(field: SearchField, value: String) {
        draft.update { current ->
            when (field) {
                SearchField.FROM -> current.copy(focusedField = field, fromQueryText = value, searchError = null)
                SearchField.TO -> current.copy(focusedField = field, toQueryText = value, searchError = null)
            }
        }
        rawQueryFlow.value = value
    }

    fun onClearFieldQuery(field: SearchField) {
        draft.update { current ->
            when (field) {
                SearchField.FROM -> current.copy(
                    fromEndpoint = RouteEndpoint.MyLocation,
                    fromQueryText = if (current.focusedField == SearchField.FROM) "" else SearchUiState.MY_LOCATION_LABEL,
                    suggestions = emptyList(),
                    searchError = null
                )
                SearchField.TO -> current.copy(
                    toEndpoint = null,
                    toQueryText = "",
                    suggestions = emptyList(),
                    searchError = null
                )
            }
        }
        if (draft.value.focusedField == field) {
            rawQueryFlow.value = ""
        }
    }

    fun dismissSearchFocus() {
        draft.update { current ->
            if (current.focusedField == null) return@update current
            current.copy(
                focusedField = null,
                pendingFavoriteAssignment = null,
                fromQueryText = endpointLabel(current.fromEndpoint, defaultLabel = SearchUiState.MY_LOCATION_LABEL),
                toQueryText = endpointLabel(current.toEndpoint, defaultLabel = ""),
                isSearching = false,
                searchError = null,
                suggestions = emptyList()
            )
        }
        rawQueryFlow.value = ""
    }

    fun onRetrySearch() {
        val currentQuery = uiState.value.activeQuery.trim()
        if (currentQuery.length >= 2) {
            rawQueryFlow.value = ""
            rawQueryFlow.value = currentQuery
        }
    }

    fun onUseMyLocationSelected() {
        val targetField = draft.value.focusedField ?: SearchField.FROM
        draft.update { current ->
            when (targetField) {
                SearchField.FROM -> current.copy(
                    fromEndpoint = RouteEndpoint.MyLocation,
                    fromQueryText = SearchUiState.MY_LOCATION_LABEL,
                    focusedField = null,
                    pendingFavoriteAssignment = null,
                    suggestions = emptyList()
                )
                SearchField.TO -> current.copy(
                    toEndpoint = RouteEndpoint.MyLocation,
                    toQueryText = SearchUiState.MY_LOCATION_LABEL,
                    focusedField = null,
                    pendingFavoriteAssignment = null,
                    suggestions = emptyList()
                )
            }
        }
        rawQueryFlow.value = ""
    }

    fun onPlaceSelected(place: Place) {
        val currentDraft = draft.value
        val targetField = currentDraft.focusedField ?: SearchField.TO
        val pendingFav = currentDraft.pendingFavoriteAssignment

        viewModelScope.launch {
            if (pendingFav == FavoriteKind.HOME || pendingFav == FavoriteKind.WORK) {
                placeRepository.saveFavorite(place, pendingFav)
            }
            placeRepository.recordRecent(place)
        }

        val selected = RouteEndpoint.SelectedPlace(place)
        draft.update { d ->
            when (targetField) {
                SearchField.FROM -> d.copy(
                    fromEndpoint = selected,
                    fromQueryText = place.name,
                    focusedField = null,
                    pendingFavoriteAssignment = null,
                    suggestions = emptyList(),
                    searchError = null
                )
                SearchField.TO -> d.copy(
                    toEndpoint = selected,
                    toQueryText = place.name,
                    focusedField = null,
                    pendingFavoriteAssignment = null,
                    suggestions = emptyList(),
                    searchError = null
                )
            }
        }
        rawQueryFlow.value = ""
    }

    fun onSwapEndpoints() {
        draft.update { current ->
            val canSwap = current.toEndpoint != null || current.fromEndpoint !is RouteEndpoint.MyLocation
            if (!canSwap) return@update current
            val newFrom = current.toEndpoint ?: RouteEndpoint.MyLocation
            val newTo = current.fromEndpoint
            current.copy(
                fromEndpoint = newFrom,
                toEndpoint = newTo,
                fromQueryText = endpointLabel(newFrom, defaultLabel = SearchUiState.MY_LOCATION_LABEL),
                toQueryText = endpointLabel(newTo, defaultLabel = ""),
                focusedField = null,
                pendingFavoriteAssignment = null,
                suggestions = emptyList(),
                searchError = null
            )
        }
        rawQueryFlow.value = ""
    }

    fun onFavoriteShortcutClick(kind: FavoriteKind) {
        val existing = uiState.value.favorites.firstOrNull { it.favoriteKind == kind }
        if (existing != null && draft.value.pendingFavoriteAssignment == null) {
            onPlaceSelected(existing)
        } else {
            draft.update { current ->
                current.copy(
                    focusedField = current.focusedField ?: SearchField.TO,
                    pendingFavoriteAssignment = if (current.pendingFavoriteAssignment == kind) null else kind
                )
            }
        }
    }

    fun onToggleSavePlace(place: Place) {
        viewModelScope.launch {
            val existing = uiState.value.favorites.firstOrNull { it.id == place.id }
            if (existing != null) {
                val key = when (existing.favoriteKind) {
                    FavoriteKind.HOME -> "HOME"
                    FavoriteKind.WORK -> "WORK"
                    FavoriteKind.NONE -> existing.id
                }
                placeRepository.removeFavorite(key)
            } else {
                placeRepository.saveFavorite(place, FavoriteKind.NONE)
            }
        }
    }

    fun onRemoveFavorite(place: Place) {
        viewModelScope.launch {
            val key = when (place.favoriteKind) {
                FavoriteKind.HOME -> "HOME"
                FavoriteKind.WORK -> "WORK"
                FavoriteKind.NONE -> place.id
            }
            placeRepository.removeFavorite(key)
        }
    }

    fun onClearRecents() {
        viewModelScope.launch {
            placeRepository.clearRecents()
        }
    }

    fun onMapLongClick(location: LatLng) {
        draft.update { current ->
            current.copy(
                focusedField = null,
                pendingFavoriteAssignment = null,
                droppedPin = DroppedPinState(
                    location = location,
                    resolvedPlace = null,
                    isLoading = true,
                    errorMessage = null
                )
            )
        }
        viewModelScope.launch {
            val result = geocoder.reverse(
                location = location,
                baseUrlOverride = latestSettings.endpoints.nominatimBaseUrl
            )
            draft.update { current ->
                val pin = current.droppedPin
                if (pin == null || pin.location != location) return@update current
                result.fold(
                    onSuccess = { place ->
                        val fallbackPlace = Place(
                            id = "pin:${location.latitude}_${location.longitude}",
                            name = "Dropped pin",
                            subtitle = formatCoords(location),
                            location = location
                        )
                        current.copy(
                            droppedPin = pin.copy(
                                resolvedPlace = place ?: fallbackPlace,
                                isLoading = false,
                                errorMessage = null
                            )
                        )
                    },
                    onFailure = { err ->
                        current.copy(
                            droppedPin = pin.copy(
                                resolvedPlace = Place(
                                    id = "pin:${location.latitude}_${location.longitude}",
                                    name = "Pinned location",
                                    subtitle = formatCoords(location),
                                    location = location
                                ),
                                isLoading = false,
                                errorMessage = err.message
                            )
                        )
                    }
                )
            }
        }
    }

    fun onApplyDroppedPinAs(field: SearchField) {
        val pin = draft.value.droppedPin ?: return
        val place = pin.resolvedPlace ?: Place(
            id = "pin:${pin.location.latitude}_${pin.location.longitude}",
            name = "Dropped pin",
            subtitle = formatCoords(pin.location),
            location = pin.location
        )
        viewModelScope.launch {
            placeRepository.recordRecent(place)
        }
        draft.update { current ->
            val selected = RouteEndpoint.SelectedPlace(place)
            when (field) {
                SearchField.FROM -> current.copy(
                    fromEndpoint = selected,
                    fromQueryText = place.name,
                    droppedPin = null
                )
                SearchField.TO -> current.copy(
                    toEndpoint = selected,
                    toQueryText = place.name,
                    droppedPin = null
                )
            }
        }
    }

    fun onSaveDroppedPinFavorite(kind: FavoriteKind) {
        val pin = draft.value.droppedPin ?: return
        val place = pin.resolvedPlace ?: Place(
            id = "pin:${pin.location.latitude}_${pin.location.longitude}",
            name = when (kind) {
                FavoriteKind.HOME -> "Home"
                FavoriteKind.WORK -> "Work"
                FavoriteKind.NONE -> "Saved pin"
            },
            subtitle = formatCoords(pin.location),
            location = pin.location
        )
        viewModelScope.launch {
            placeRepository.saveFavorite(place, kind)
        }
    }

    fun onDismissDroppedPin() {
        draft.update { it.copy(droppedPin = null) }
    }

    private fun endpointLabel(endpoint: RouteEndpoint?, defaultLabel: String): String {
        return when (endpoint) {
            null -> defaultLabel
            is RouteEndpoint.MyLocation -> SearchUiState.MY_LOCATION_LABEL
            is RouteEndpoint.SelectedPlace -> endpoint.place.name
        }
    }

    private fun formatCoords(location: LatLng): String {
        return String.format(Locale.US, "%.5f, %.5f", location.latitude, location.longitude)
    }
}
