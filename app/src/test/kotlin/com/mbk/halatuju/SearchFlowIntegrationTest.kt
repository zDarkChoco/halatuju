package com.mbk.halatuju

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mbk.halatuju.core.geocoding.NominatimGeocoder
import com.mbk.halatuju.core.geocoding.OpenOsmGeocoder
import com.mbk.halatuju.core.geocoding.PhotonGeocoder
import com.mbk.halatuju.core.location.FusedLocationAdapter
import com.mbk.halatuju.core.location.LocationSourceCoordinator
import com.mbk.halatuju.core.location.PlatformLocationSource
import com.mbk.halatuju.core.map.MapRenderingState
import com.mbk.halatuju.core.map.OpenFreeMapTileSource
import com.mbk.halatuju.core.map.theme.HalaTujuTheme
import com.mbk.halatuju.core.model.FavoriteKind
import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.LocationProviderKind
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.ServiceEndpoints
import com.mbk.halatuju.core.model.ThemeMode
import com.mbk.halatuju.core.routing.RouteRequest
import com.mbk.halatuju.core.routing.Router
import com.mbk.halatuju.core.storage.DataStoreSettingsStore
import com.mbk.halatuju.core.storage.PlaceRepository
import com.mbk.halatuju.feature.search.SearchViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SearchFlowIntegrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer

    @After
    fun tearDown() {
        if (::server.isInitialized) {
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun mockWebServerSearchRendersSuggestionInHomeShell() {
        val app = RuntimeEnvironment.getApplication()
        val requests = mutableListOf<String>()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl?.toString().orEmpty()
                requests += url
                val q = request.requestUrl?.queryParameter("q").orEmpty().lowercase()
                val features = JSONArray()
                if (q.contains("klia")) {
                    val dest = GeoMath.fromLocalEnMeters(KlDefaults.KLCC, 1400.0, 0.0)
                    features.put(
                        JSONObject()
                            .put("type", "Feature")
                            .put(
                                "geometry",
                                JSONObject()
                                    .put("type", "Point")
                                    .put("coordinates", JSONArray().put(dest.longitude).put(dest.latitude))
                            )
                            .put(
                                "properties",
                                JSONObject()
                                    .put("osm_type", "W")
                                    .put("osm_id", 7001L)
                                    .put("name", "KLIA Terminal 1")
                                    .put("street", "Lebuhraya KLIA")
                                    .put("city", "Sepang")
                                    .put("country", "Malaysia")
                            )
                    )
                }
                return MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .setBody(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val rawUrl = server.url("/").toString().trimEnd('/')
        val explicitUrl = "http://127.0.0.1:${server.port}"
        println("mockWebServer.url('/')=$rawUrl explicit=$explicitUrl")

        val client = OkHttpClient.Builder().build()
        val geocoder = OpenOsmGeocoder(PhotonGeocoder(client), NominatimGeocoder(client))
        val settingsStore = DataStoreSettingsStore(app)
        val coordinator = LocationSourceCoordinator(
            platformLocationSource = PlatformLocationSource(app),
            fusedLocationAdapter = object : FusedLocationAdapter {
                override val isAvailableOnDevice: Boolean = false
                override val providerKind: LocationProviderKind = LocationProviderKind.FUSED
                override fun observeLocations(minIntervalMs: Long): Flow<LocationFix> = emptyFlow()
            }
        )
        val placeRepo = object : PlaceRepository {
            private val rec = MutableStateFlow<List<Place>>(emptyList())
            private val fav = MutableStateFlow<List<Place>>(emptyList())
            override fun observeRecents(limit: Int): Flow<List<Place>> = rec
            override fun observeFavorites(): Flow<List<Place>> = fav
            override suspend fun recordRecent(place: Place, usedAtEpochMs: Long) {}
            override suspend fun saveFavorite(place: Place, kind: FavoriteKind, updatedAtEpochMs: Long) {}
            override suspend fun removeFavorite(kindOrId: String) {}
            override suspend fun clearRecents() {}
        }
        val vm = SearchViewModel(
            geocoder = geocoder,
            router = object : Router {
                override suspend fun calculateRoutes(request: RouteRequest) = Result.success(emptyList<com.mbk.halatuju.core.model.NavRoute>())
            },
            placeRepository = placeRepo,
            settingsStore = settingsStore,
            locationSourceCoordinator = coordinator
        )
        val tileSource = OpenFreeMapTileSource(app)

        // Launch UI BEFORE updating settingsStore (mirrors createAndroidComposeRule<MainActivity>!)
        composeRule.setContent {
            val s = vm.uiState.collectAsStateWithLifecycle().value
            HalaTujuTheme(themeMode = ThemeMode.LIGHT) {
                HalaTujuHomeMapShell(
                    mapState = MapRenderingState(),
                    tileSource = tileSource,
                    hasLocationPermission = true,
                    tiltPreviewEnabled = false,
                    searchState = s,
                    onRequestLocationPermission = {},
                    onUserPanMap = {},
                    onRecenterClick = {},
                    onOpenSettings = {},
                    onOpenAbout = {},
                    onSearchFieldFocused = vm::onFieldFocused,
                    onSearchQueryChanged = vm::onQueryChanged,
                    onClearSearchField = vm::onClearFieldQuery,
                    onSwapEndpoints = vm::onSwapEndpoints,
                    onSearchPlaceClick = vm::onPlaceSelected,
                    onUseMyLocationClick = vm::onUseMyLocationSelected,
                    onFavoriteShortcutClick = vm::onFavoriteShortcutClick,
                    onToggleSavePlace = vm::onToggleSavePlace,
                    onClearRecents = vm::onClearRecents,
                    onRetrySearch = vm::onRetrySearch,
                    onDismissSearchFocus = vm::dismissSearchFocus
                )
            }
        }

        runBlocking {
            settingsStore.updateEndpoints(
                ServiceEndpoints(
                    lightStyleUrl = "$explicitUrl/style.json",
                    darkTileSourceUrl = "$explicitUrl/tiles.json",
                    valhallaBaseUrl = explicitUrl,
                    photonBaseUrl = explicitUrl,
                    nominatimBaseUrl = explicitUrl
                )
            )
            settingsStore.settings.first { it.endpoints.photonBaseUrl == explicitUrl }
        }

        composeRule.onNodeWithTag("search_to_input").performClick()
        composeRule.onNodeWithTag("search_to_input").performTextInput("KLIA")

        try {
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("KLIA Terminal 1").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            println("Requests received: $requests")
            println("VM uiState: ${vm.uiState.value}")
            println("Tree:\n${composeRule.onRoot().printToString()}")
        }
    }
}
