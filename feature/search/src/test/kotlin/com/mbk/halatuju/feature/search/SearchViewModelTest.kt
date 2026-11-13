package com.mbk.halatuju.feature.search

import android.app.Application
import com.mbk.halatuju.core.geocoding.Geocoder
import com.mbk.halatuju.core.location.FusedLocationAdapter
import com.mbk.halatuju.core.location.LocationSourceCoordinator
import com.mbk.halatuju.core.location.PlatformLocationSource
import com.mbk.halatuju.core.model.AppSettings
import com.mbk.halatuju.core.model.FavoriteKind
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.LocationProviderKind
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.RoutingAvoidances
import com.mbk.halatuju.core.model.ServiceEndpoints
import com.mbk.halatuju.core.model.ThemeMode
import com.mbk.halatuju.core.model.UnitSystem
import com.mbk.halatuju.core.routing.RouteRequest
import com.mbk.halatuju.core.routing.Router
import com.mbk.halatuju.core.storage.PlaceRepository
import com.mbk.halatuju.core.storage.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var fakeGeocoder: FakeGeocoder
    private lateinit var fakeRouter: FakeRouter
    private lateinit var fakePlaces: InMemoryPlaceRepository
    private lateinit var fakeSettings: InMemorySettingsStore
    private lateinit var coordinator: LocationSourceCoordinator
    private lateinit var viewModel: SearchViewModel

    private val klcc = Place(
        id = "photon:1",
        name = "KLCC",
        subtitle = "Jalan Ampang, Kuala Lumpur",
        location = KlDefaults.KLCC
    )

    private val klSentral = Place(
        id = "photon:3",
        name = "KL Sentral",
        subtitle = "Brickfields, Kuala Lumpur",
        location = KlDefaults.KL_SENTRAL
    )

    private val klia = Place(
        id = "photon:2",
        name = "KLIA Terminal 1",
        subtitle = "Sepang, Selangor",
        location = KlDefaults.KLIA
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeGeocoder = FakeGeocoder(
            searchResults = listOf(klcc, klia, klSentral),
            reversePlace = Place("nominatim:42", "Lorong Kuda", "KLCC, Kuala Lumpur", KlDefaults.KLCC)
        )
        fakeRouter = FakeRouter()
        fakePlaces = InMemoryPlaceRepository()
        fakeSettings = InMemorySettingsStore()
        val app = RuntimeEnvironment.getApplication()
        coordinator = LocationSourceCoordinator(
            platformLocationSource = PlatformLocationSource(app),
            fusedLocationAdapter = object : FusedLocationAdapter {
                override val isAvailableOnDevice: Boolean = false
                override val providerKind: LocationProviderKind = LocationProviderKind.FUSED
                override fun observeLocations(minIntervalMs: Long): Flow<LocationFix> = emptyFlow()
            }
        )
        viewModel = SearchViewModel(
            geocoder = fakeGeocoder,
            router = fakeRouter,
            placeRepository = fakePlaces,
            settingsStore = fakeSettings,
            locationSourceCoordinator = coordinator,
            clockMs = { 1_778_500_000_000L }
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `debounced search runs after 300ms, stores recent on selection, routes automatically, and swap reverses endpoints`() = runTest(testDispatcher) {
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        assertEquals(RouteEndpoint.MyLocation, viewModel.uiState.value.fromEndpoint)
        assertNull(viewModel.uiState.value.toEndpoint)
        assertFalse(viewModel.uiState.value.isFocused)

        viewModel.onFieldFocused(SearchField.TO)
        runCurrent()
        assertTrue(viewModel.uiState.value.isFocused)

        viewModel.onQueryChanged(SearchField.TO, "KL")
        advanceTimeBy(150L)
        runCurrent()
        assertEquals(0, fakeGeocoder.searchCalls.size)

        advanceTimeBy(200L)
        runCurrent()
        assertEquals(listOf("KL"), fakeGeocoder.searchCalls)
        assertEquals(3, viewModel.uiState.value.suggestions.size)

        viewModel.onPlaceSelected(klia)
        runCurrent()

        assertFalse(viewModel.uiState.value.isFocused)
        assertEquals(RouteEndpoint.SelectedPlace(klia), viewModel.uiState.value.toEndpoint)
        assertEquals("KLIA Terminal 1", viewModel.uiState.value.toQueryText)
        assertEquals(listOf(klia.id), fakePlaces.recordedRecents.map { it.id })
        assertEquals(1, fakeRouter.requests.size)
        assertEquals(3, viewModel.uiState.value.routePreview.candidateRoutes.size)

        // Alternative selection switches selected route
        viewModel.onSelectAlternativeRoute(1)
        runCurrent()
        assertEquals(1, viewModel.uiState.value.routePreview.selectedRouteIndex)
        assertEquals("alt_1", viewModel.uiState.value.routePreview.selectedRoute?.id)

        // Swap reverses endpoints & triggers automatic reroute
        viewModel.onSwapEndpoints()
        runCurrent()

        assertEquals(RouteEndpoint.SelectedPlace(klia), viewModel.uiState.value.fromEndpoint)
        assertEquals(RouteEndpoint.MyLocation, viewModel.uiState.value.toEndpoint)
        assertEquals("KLIA Terminal 1", viewModel.uiState.value.fromQueryText)
        assertEquals("My location", viewModel.uiState.value.toQueryText)
        assertEquals(2, fakeRouter.requests.size)

        collector.cancel()
    }

    @Test
    fun `adding, reordering, and removing intermediate stops plus changing avoidances auto-reroutes cleanly`() = runTest(testDispatcher) {
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        // Pick origin & destination
        viewModel.onFieldFocused(SearchField.FROM)
        runCurrent()
        viewModel.onPlaceSelected(klcc)
        runCurrent()

        viewModel.onFieldFocused(SearchField.TO)
        runCurrent()
        viewModel.onPlaceSelected(klia)
        runCurrent()
        assertEquals(1, fakeRouter.requests.size)
        assertEquals(listOf(klcc.id, klia.id), fakeRouter.requests.last().waypoints.map { it.id })

        // Add stop 1: KL Sentral
        viewModel.onAddStopClick()
        runCurrent()
        assertEquals(SearchField.STOP, viewModel.uiState.value.focusedField)
        viewModel.onPlaceSelected(klSentral)
        runCurrent()

        assertEquals(listOf(klSentral), viewModel.uiState.value.intermediateStops)
        assertEquals(2, fakeRouter.requests.size)
        assertEquals(listOf(klcc.id, klSentral.id, klia.id), fakeRouter.requests.last().waypoints.map { it.id })
        assertFalse(fakeRouter.requests.last().preferAlternates)

        // Add stop 2: Mid Valley
        val midValley = Place("photon:4", "Mid Valley", "Kuala Lumpur", LatLng(3.1177, 101.6774))
        viewModel.onAddStopClick()
        runCurrent()
        viewModel.onPlaceSelected(midValley)
        runCurrent()
        assertEquals(listOf(klSentral, midValley), viewModel.uiState.value.intermediateStops)
        assertEquals(listOf(klcc.id, klSentral.id, midValley.id, klia.id), fakeRouter.requests.last().waypoints.map { it.id })

        // Move stop 1 up -> [Mid Valley, KL Sentral]
        viewModel.onMoveStopUp(1)
        runCurrent()
        assertEquals(listOf(midValley, klSentral), viewModel.uiState.value.intermediateStops)
        assertEquals(listOf(klcc.id, midValley.id, klSentral.id, klia.id), fakeRouter.requests.last().waypoints.map { it.id })

        // Remove stop 0 -> [KL Sentral]
        viewModel.onRemoveStop(0)
        runCurrent()
        assertEquals(listOf(klSentral), viewModel.uiState.value.intermediateStops)
        assertEquals(listOf(klcc.id, klSentral.id, klia.id), fakeRouter.requests.last().waypoints.map { it.id })

        // Toggling Avoid Tolls triggers automatic reroute with exclude_tolls
        fakeSettings.updateAvoidances(RoutingAvoidances(avoidTolls = true))
        runCurrent()
        assertTrue(fakeRouter.requests.last().avoidances.avoidTolls)
        assertTrue(viewModel.uiState.value.routePreview.activeAvoidances.avoidTolls)

        collector.cancel()
    }

    @Test
    fun `long press map reverse geocodes dropped pin and assigns HOME favorite shortcut cleanly`() = runTest(testDispatcher) {
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        viewModel.onMapLongClick(KlDefaults.KLCC)
        runCurrent()

        val pin = viewModel.uiState.value.droppedPin
        assertEquals("Lorong Kuda", pin?.resolvedPlace?.name)

        viewModel.onApplyDroppedPinAs(SearchField.TO)
        runCurrent()
        assertNull(viewModel.uiState.value.droppedPin)
        assertEquals("Lorong Kuda", viewModel.uiState.value.toQueryText)

        // Assigning HOME on first click puts Search into assignment mode, then saves chosen place as HOME
        viewModel.onFavoriteShortcutClick(FavoriteKind.HOME)
        runCurrent()
        assertEquals(FavoriteKind.HOME, viewModel.uiState.value.pendingFavoriteAssignment)

        viewModel.onPlaceSelected(klcc)
        runCurrent()
        assertEquals("KLCC", viewModel.uiState.value.homeFavorite()?.name)
        assertNull(viewModel.uiState.value.pendingFavoriteAssignment)

        collector.cancel()
    }

    private class FakeGeocoder(
        private val searchResults: List<Place>,
        private val reversePlace: Place?
    ) : Geocoder {
        val searchCalls = ArrayList<String>()

        override suspend fun search(
            query: String,
            bias: LatLng?,
            limit: Int,
            baseUrlOverride: String?
        ): Result<List<Place>> {
            searchCalls += query
            return Result.success(searchResults.take(limit))
        }

        override suspend fun reverse(
            location: LatLng,
            baseUrlOverride: String?
        ): Result<Place?> = Result.success(reversePlace?.copy(location = location))
    }

    private class FakeRouter : Router {
        val requests = ArrayList<RouteRequest>()

        override suspend fun calculateRoutes(request: RouteRequest): Result<List<NavRoute>> {
            requests += request
            val pts = request.waypoints.map { it.location }
            val count = if (request.preferAlternates && request.waypoints.size <= 2) 3 else 1
            val routes = (0 until count).map { idx ->
                NavRoute(
                    id = if (idx == 0) "primary_0" else "alt_$idx",
                    geometry = pts,
                    cumulativeDistancesMeters = List(pts.size) { it * 15_000.0 },
                    totalDistanceMeters = (pts.size - 1) * 50_000.0 + idx * 3_500.0,
                    baseDurationSeconds = 2_400.0 + idx * 300.0,
                    adjustedDurationSeconds = 2_880.0 + idx * 360.0,
                    trafficEstimated = true,
                    trafficDelayFactor = 1.2,
                    trafficLabel = "estimated · evening peak traffic",
                    legs = emptyList(),
                    waypoints = request.waypoints,
                    summaryLabel = if (idx == 0) "E20, E6" else "B48"
                )
            }
            return Result.success(routes)
        }
    }

    private class InMemoryPlaceRepository : PlaceRepository {
        private val recents = MutableStateFlow<List<Place>>(emptyList())
        private val favorites = MutableStateFlow<List<Place>>(emptyList())
        val recordedRecents = ArrayList<Place>()

        override fun observeRecents(limit: Int): Flow<List<Place>> = recents.map { it.take(limit) }
        override fun observeFavorites(): Flow<List<Place>> = favorites

        override suspend fun recordRecent(place: Place, usedAtEpochMs: Long) {
            recordedRecents += place
            recents.value = (listOf(place.copy(lastUsedEpochMs = usedAtEpochMs)) + recents.value)
                .distinctBy { it.id }
        }

        override suspend fun saveFavorite(place: Place, kind: FavoriteKind, updatedAtEpochMs: Long) {
            val saved = place.copy(favoriteKind = kind, lastUsedEpochMs = updatedAtEpochMs)
            favorites.value = (favorites.value.filterNot {
                (kind != FavoriteKind.NONE && it.favoriteKind == kind) || it.id == place.id
            } + saved)
        }

        override suspend fun removeFavorite(kindOrId: String) {
            favorites.value = favorites.value.filterNot {
                it.id == kindOrId || it.favoriteKind.name == kindOrId
            }
        }

        override suspend fun clearRecents() {
            recents.value = emptyList()
        }
    }

    private class InMemorySettingsStore : SettingsStore {
        private val state = MutableStateFlow(AppSettings())
        override val settings: Flow<AppSettings> = state
        override suspend fun updateThemeMode(mode: ThemeMode) {}
        override suspend fun updateUnitSystem(units: UnitSystem) {}
        override suspend fun updateVoiceEnabled(enabled: Boolean) {}
        override suspend fun updateVoiceVolume(volume: Float) {}
        override suspend fun updateOverspeedToleranceKmh(toleranceKmh: Int) {}
        override suspend fun updateAvoidances(avoidances: RoutingAvoidances) {
            state.update { it.copy(avoidances = avoidances) }
        }
        override suspend fun updateEndpoints(endpoints: ServiceEndpoints) {}
        override suspend fun updateSimulationEnabled(enabled: Boolean) {}
        override suspend fun updateSimulationSpeedMultiplier(multiplier: Float) {}
        override suspend fun updateSimulationNoiseEnabled(enabled: Boolean) {}
        override suspend fun updateDeveloperDebugOverlayEnabled(enabled: Boolean) {}
    }
}
