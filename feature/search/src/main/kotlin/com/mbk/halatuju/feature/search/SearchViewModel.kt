package com.mbk.halatuju.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mbk.halatuju.core.geocoding.Geocoder
import com.mbk.halatuju.core.geocoding.SearchDebouncer
import com.mbk.halatuju.core.location.LocationSourceCoordinator
import com.mbk.halatuju.core.model.FavoriteKind
import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.RoutingAvoidances
import com.mbk.halatuju.core.routing.RouteRequest
import com.mbk.halatuju.core.routing.Router
import com.mbk.halatuju.core.storage.PlaceRepository
import com.mbk.halatuju.core.storage.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    val stopQueryText: String = "",
    val pendingFavoriteAssignment: FavoriteKind? = null,
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val suggestions: List<Place> = emptyList(),
    val droppedPin: DroppedPinState? = null,
    val routePreview: RoutePreviewUiState = RoutePreviewUiState()
)

private data class RouteInputs(
    val fromEndpoint: RouteEndpoint,
    val intermediateStops: List<Place>,
    val toEndpoint: RouteEndpoint?
)

private data class ResolvedRouteQuery(
    val waypoints: List<Place>,
    val avoidances: RoutingAvoidances,
    val valhallaBaseUrl: String,
    val refreshTick: Int
)

private sealed interface RouteTriggerState {
    data object Suspended : RouteTriggerState
    data object Empty : RouteTriggerState
    data class Query(val query: ResolvedRouteQuery) : RouteTriggerState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel internal constructor(
    private val geocoder: Geocoder,
    private val router: Router,
    private val placeRepository: PlaceRepository,
    private val settingsStore: SettingsStore,
    private val locationSourceCoordinator: LocationSourceCoordinator,
    private val clockMs: () -> Long
) : ViewModel() {

    @Inject
    constructor(
        geocoder: Geocoder,
        router: Router,
        placeRepository: PlaceRepository,
        settingsStore: SettingsStore,
        locationSourceCoordinator: LocationSourceCoordinator
    ) : this(
        geocoder = geocoder,
        router = router,
        placeRepository = placeRepository,
        settingsStore = settingsStore,
        locationSourceCoordinator = locationSourceCoordinator,
        clockMs = { System.currentTimeMillis() }
    )

    private val draft = MutableStateFlow(DraftSearchState())
    private val rawQueryFlow = MutableStateFlow("")
    private val routeRefreshTrigger = MutableStateFlow(0)
    private val isGuidanceSuspended = MutableStateFlow(false)
    private var myLocationRouteAnchor: LatLng? = null
    private var lastCompletedQuery: ResolvedRouteQuery? = null

    val uiState: StateFlow<SearchUiState> = combine(
        draft,
        placeRepository.observeRecents(limit = 15),
        placeRepository.observeFavorites(),
        locationSourceCoordinator.latestFix,
        settingsStore.settings
    ) { d, recents, favorites, latestFix, settings ->
        SearchUiState(
            fromEndpoint = d.fromEndpoint,
            toEndpoint = d.toEndpoint,
            intermediateStops = d.intermediateStops,
            focusedField = d.focusedField,
            fromQueryText = d.fromQueryText,
            toQueryText = d.toQueryText,
            stopQueryText = d.stopQueryText,
            pendingFavoriteAssignment = d.pendingFavoriteAssignment,
            isSearching = d.isSearching,
            searchError = d.searchError,
            suggestions = d.suggestions,
            recents = recents,
            favorites = favorites,
            droppedPin = d.droppedPin,
            routePreview = d.routePreview,
            distanceAnchor = latestFix?.position ?: KlDefaults.KLCC,
            unitSystem = settings.unitSystem
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SearchUiState()
    )

    init {
        // Photon search-as-you-type debounced to 300 ms with device location bias
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
                    val photonUrl = settingsStore.settings.first().endpoints.photonBaseUrl
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

        // Reactive routing when endpoints, intermediate stops, avoidances, or Valhalla server URL change.
        // Suspended cleanly while active turn-by-turn navigation owns the session to respect public-server fair use.
        val stabilizedAnchorFlow = locationSourceCoordinator.latestFix
            .map { fix ->
                val rawPos = fix?.position ?: KlDefaults.KLCC
                val current = myLocationRouteAnchor
                if (current == null || GeoMath.haversineMeters(current, rawPos) > 150.0) {
                    myLocationRouteAnchor = rawPos
                    rawPos
                } else {
                    current
                }
            }
            .distinctUntilChanged()

        viewModelScope.launch {
            isGuidanceSuspended
                .flatMapLatest { suspended ->
                    if (suspended) {
                        flowOf(RouteTriggerState.Suspended)
                    } else {
                        combine(
                            draft.map { RouteInputs(it.fromEndpoint, it.intermediateStops, it.toEndpoint) }
                                .distinctUntilChanged(),
                            settingsStore.settings
                                .map { it.avoidances to it.endpoints.valhallaBaseUrl }
                                .distinctUntilChanged(),
                            stabilizedAnchorFlow,
                            routeRefreshTrigger
                        ) { inputs, (avoidances, valhallaUrl), anchor, refreshTick ->
                            val destEndpoint = inputs.toEndpoint
                            if (destEndpoint == null) {
                                RouteTriggerState.Empty
                            } else {
                                val originPlace = resolveEndpointPlace(inputs.fromEndpoint, anchor, id = "origin:me")
                                val destPlace = resolveEndpointPlace(destEndpoint, anchor, id = "dest:me")
                                RouteTriggerState.Query(
                                    ResolvedRouteQuery(
                                        waypoints = listOf(originPlace) + inputs.intermediateStops + destPlace,
                                        avoidances = avoidances,
                                        valhallaBaseUrl = valhallaUrl,
                                        refreshTick = refreshTick
                                    )
                                )
                            }
                        }
                    }
                }
                .distinctUntilChanged()
                .collectLatest { trigger ->
                    when (trigger) {
                        RouteTriggerState.Suspended -> {
                            draft.update { cur ->
                                if (cur.routePreview.isLoading) {
                                    cur.copy(routePreview = cur.routePreview.copy(isLoading = false))
                                } else {
                                    cur
                                }
                            }
                            return@collectLatest
                        }

                        RouteTriggerState.Empty -> {
                            lastCompletedQuery = null
                            draft.update { it.copy(routePreview = RoutePreviewUiState()) }
                            return@collectLatest
                        }

                        is RouteTriggerState.Query -> {
                            val query = trigger.query
                            if (query == lastCompletedQuery &&
                                draft.value.routePreview.candidateRoutes.isNotEmpty() &&
                                draft.value.routePreview.errorMessage == null
                            ) {
                                draft.update { cur ->
                                    if (cur.routePreview.isLoading) {
                                        cur.copy(routePreview = cur.routePreview.copy(isLoading = false))
                                    } else {
                                        cur
                                    }
                                }
                                return@collectLatest
                            }

                            val first = query.waypoints.first()
                            val last = query.waypoints.last()
                            if (query.waypoints.size == 2 &&
                                GeoMath.haversineMeters(first.location, last.location) < 20.0
                            ) {
                                lastCompletedQuery = null
                                draft.update {
                                    it.copy(
                                        routePreview = RoutePreviewUiState(
                                            isLoading = false,
                                            candidateRoutes = emptyList(),
                                            errorMessage = "Origin and destination are the same location.",
                                            activeAvoidances = query.avoidances
                                        )
                                    )
                                }
                                return@collectLatest
                            }

                            draft.update {
                                it.copy(
                                    routePreview = it.routePreview.copy(
                                        isLoading = true,
                                        errorMessage = null,
                                        activeAvoidances = query.avoidances
                                    )
                                )
                            }

                            val nowMs = clockMs()
                            val result = router.calculateRoutes(
                                RouteRequest(
                                    waypoints = query.waypoints,
                                    avoidances = query.avoidances,
                                    preferAlternates = query.waypoints.size <= 2,
                                    departureEpochMs = nowMs,
                                    baseUrlOverride = query.valhallaBaseUrl
                                )
                            )
                            result.fold(
                                onSuccess = { routes ->
                                    lastCompletedQuery = query
                                    draft.update {
                                        it.copy(
                                            routePreview = RoutePreviewUiState(
                                                isLoading = false,
                                                candidateRoutes = routes.take(3),
                                                selectedRouteIndex = 0,
                                                errorMessage = null,
                                                referenceEpochMs = nowMs,
                                                activeAvoidances = query.avoidances
                                            )
                                        )
                                    }
                                },
                                onFailure = { err ->
                                    lastCompletedQuery = null
                                    draft.update {
                                        it.copy(
                                            routePreview = RoutePreviewUiState(
                                                isLoading = false,
                                                candidateRoutes = emptyList(),
                                                selectedRouteIndex = 0,
                                                errorMessage = err.message ?: "Unable to calculate route right now.",
                                                referenceEpochMs = nowMs,
                                                activeAvoidances = query.avoidances
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
        }
    }

    fun setGuidanceSuspended(suspended: Boolean) {
        isGuidanceSuspended.value = suspended
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
                stopQueryText = if (field == SearchField.STOP) "" else current.stopQueryText,
                searchError = null,
                suggestions = emptyList()
            )
        }
        val activeQuery = when (field) {
            SearchField.FROM -> draft.value.fromQueryText
            SearchField.TO -> draft.value.toQueryText
            SearchField.STOP -> draft.value.stopQueryText
        }
        rawQueryFlow.value = activeQuery
    }

    fun onQueryChanged(field: SearchField, value: String) {
        draft.update { current ->
            when (field) {
                SearchField.FROM -> current.copy(focusedField = field, fromQueryText = value, searchError = null)
                SearchField.TO -> current.copy(focusedField = field, toQueryText = value, searchError = null)
                SearchField.STOP -> current.copy(focusedField = field, stopQueryText = value, searchError = null)
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
                    intermediateStops = emptyList(),
                    toQueryText = "",
                    suggestions = emptyList(),
                    searchError = null,
                    routePreview = RoutePreviewUiState()
                )
                SearchField.STOP -> current.copy(
                    stopQueryText = "",
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
                stopQueryText = "",
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
        if (targetField == SearchField.STOP) {
            dismissSearchFocus()
            return
        }
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
                SearchField.STOP -> current
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
                    searchError = null,
                    routePreview = if (d.toEndpoint != null) {
                        d.routePreview.copy(isLoading = true, errorMessage = null)
                    } else {
                        d.routePreview
                    }
                )
                SearchField.TO -> d.copy(
                    toEndpoint = selected,
                    toQueryText = place.name,
                    focusedField = null,
                    pendingFavoriteAssignment = null,
                    suggestions = emptyList(),
                    searchError = null,
                    routePreview = d.routePreview.copy(isLoading = true, errorMessage = null)
                )
                SearchField.STOP -> {
                    val updatedStops = if (d.intermediateStops.size < SearchUiState.MAX_INTERMEDIATE_STOPS) {
                        d.intermediateStops + place
                    } else {
                        d.intermediateStops
                    }
                    d.copy(
                        intermediateStops = updatedStops,
                        stopQueryText = "",
                        focusedField = null,
                        pendingFavoriteAssignment = null,
                        suggestions = emptyList(),
                        searchError = null,
                        routePreview = if (d.toEndpoint != null && updatedStops != d.intermediateStops) {
                            d.routePreview.copy(isLoading = true, errorMessage = null)
                        } else {
                            d.routePreview
                        }
                    )
                }
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
                intermediateStops = current.intermediateStops.reversed(),
                fromQueryText = endpointLabel(newFrom, defaultLabel = SearchUiState.MY_LOCATION_LABEL),
                toQueryText = endpointLabel(newTo, defaultLabel = ""),
                focusedField = null,
                pendingFavoriteAssignment = null,
                suggestions = emptyList(),
                searchError = null,
                routePreview = current.routePreview.copy(isLoading = true, errorMessage = null)
            )
        }
        rawQueryFlow.value = ""
    }

    fun onAddStopClick() {
        if (draft.value.intermediateStops.size >= SearchUiState.MAX_INTERMEDIATE_STOPS) return
        onFieldFocused(SearchField.STOP)
    }

    fun onMoveStopUp(index: Int) {
        draft.update { current ->
            if (index !in 1 until current.intermediateStops.size) return@update current
            val mutable = current.intermediateStops.toMutableList()
            val item = mutable.removeAt(index)
            mutable.add(index - 1, item)
            current.copy(
                intermediateStops = mutable,
                routePreview = if (current.toEndpoint != null) {
                    current.routePreview.copy(isLoading = true, errorMessage = null)
                } else {
                    current.routePreview
                }
            )
        }
    }

    fun onMoveStopDown(index: Int) {
        draft.update { current ->
            if (index < 0 || index >= current.intermediateStops.lastIndex) return@update current
            val mutable = current.intermediateStops.toMutableList()
            val item = mutable.removeAt(index)
            mutable.add(index + 1, item)
            current.copy(
                intermediateStops = mutable,
                routePreview = if (current.toEndpoint != null) {
                    current.routePreview.copy(isLoading = true, errorMessage = null)
                } else {
                    current.routePreview
                }
            )
        }
    }

    fun onRemoveStop(index: Int) {
        draft.update { current ->
            if (index !in current.intermediateStops.indices) return@update current
            val mutable = current.intermediateStops.toMutableList()
            mutable.removeAt(index)
            current.copy(
                intermediateStops = mutable,
                routePreview = if (current.toEndpoint != null) {
                    current.routePreview.copy(isLoading = true, errorMessage = null)
                } else {
                    current.routePreview
                }
            )
        }
    }

    fun onSelectAlternativeRoute(index: Int) {
        draft.update { current ->
            if (index !in current.routePreview.candidateRoutes.indices) return@update current
            current.copy(
                routePreview = current.routePreview.copy(selectedRouteIndex = index)
            )
        }
    }

    fun onRetryRoute() {
        routeRefreshTrigger.value += 1
    }

    fun onClearRoute() {
        draft.update { current ->
            current.copy(
                toEndpoint = null,
                intermediateStops = emptyList(),
                toQueryText = "",
                stopQueryText = "",
                focusedField = null,
                suggestions = emptyList(),
                searchError = null,
                routePreview = RoutePreviewUiState()
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
            val nominatimUrl = settingsStore.settings.first().endpoints.nominatimBaseUrl
            val result = geocoder.reverse(
                location = location,
                baseUrlOverride = nominatimUrl
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
                SearchField.STOP -> {
                    val updatedStops = if (current.intermediateStops.size < SearchUiState.MAX_INTERMEDIATE_STOPS) {
                        current.intermediateStops + place
                    } else {
                        current.intermediateStops
                    }
                    current.copy(
                        intermediateStops = updatedStops,
                        droppedPin = null
                    )
                }
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

    private fun resolveEndpointPlace(
        endpoint: RouteEndpoint,
        anchor: LatLng,
        id: String
    ): Place {
        return when (endpoint) {
            is RouteEndpoint.SelectedPlace -> endpoint.place
            is RouteEndpoint.MyLocation -> Place(
                id = id,
                name = SearchUiState.MY_LOCATION_LABEL,
                subtitle = "",
                location = anchor
            )
        }
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
