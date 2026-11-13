package com.mbk.halatuju

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mbk.halatuju.core.location.LocationPermissionHelper
import com.mbk.halatuju.core.map.HalaTujuMapSurface
import com.mbk.halatuju.core.map.MapRenderingState
import com.mbk.halatuju.core.map.TileSource
import com.mbk.halatuju.core.map.theme.HalaTujuTheme
import com.mbk.halatuju.core.model.FavoriteKind
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.MapCameraMode
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.ThemeMode
import com.mbk.halatuju.feature.navigation.ActiveNavigationScreen
import com.mbk.halatuju.feature.navigation.ActiveNavigationViewModel
import com.mbk.halatuju.feature.search.DroppedPinCard
import com.mbk.halatuju.feature.search.HomeSearchCard
import com.mbk.halatuju.feature.search.RouteEndpoint
import com.mbk.halatuju.feature.search.RoutePreviewSheet
import com.mbk.halatuju.feature.search.SearchField
import com.mbk.halatuju.feature.search.SearchUiState
import com.mbk.halatuju.feature.search.SearchViewModel
import com.mbk.halatuju.feature.settings.AboutLicensesScreen
import com.mbk.halatuju.feature.settings.SettingsScreen
import com.mbk.halatuju.feature.settings.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint

private object HalaTujuRoutes {
    const val HOME = "home"
    const val NAVIGATION = "navigation"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HalaTujuAppRoot(
                onSystemBarThemeUpdate = { isDark ->
                    val style = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
            )
        }
    }
}

@Composable
fun HalaTujuAppRoot(
    onSystemBarThemeUpdate: (Boolean) -> Unit = {},
    mainViewModel: MainViewModel = hiltViewModel()
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val searchViewModel: SearchViewModel = hiltViewModel()
    val navigationViewModel: ActiveNavigationViewModel = hiltViewModel()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val searchState by searchViewModel.uiState.collectAsStateWithLifecycle()
    val navState by navigationViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val isDark = when (settings.themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    LaunchedEffect(isDark) {
        onSystemBarThemeUpdate(isDark)
    }

    val previewSelectedRoute = searchState.routePreview.selectedRoute
    LaunchedEffect(previewSelectedRoute?.id, previewSelectedRoute?.totalDistanceMeters, searchState.toEndpoint) {
        if (searchState.toEndpoint == null) {
            mainViewModel.returnToFollowPuck()
        } else if (previewSelectedRoute != null) {
            mainViewModel.enterRouteOverview()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mainViewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        mainViewModel.refreshPermissions()
    }

    val selectedWaypoints = remember(
        searchState.fromEndpoint,
        searchState.intermediateStops,
        searchState.toEndpoint,
        uiState.latestFix
    ) {
        buildList {
            when (val from = searchState.fromEndpoint) {
                is RouteEndpoint.SelectedPlace -> add(from.place)
                is RouteEndpoint.MyLocation -> {
                    uiState.latestFix?.let { fix ->
                        add(Place(id = "origin:me", name = "My location", subtitle = "", location = fix.position))
                    }
                }
            }
            addAll(searchState.intermediateStops)
            when (val to = searchState.toEndpoint) {
                is RouteEndpoint.SelectedPlace -> add(to.place)
                is RouteEndpoint.MyLocation -> {
                    uiState.latestFix?.let { fix ->
                        add(Place(id = "dest:me", name = "My location", subtitle = "", location = fix.position))
                    }
                }
                null -> Unit
            }
        }
    }

    val mapDroppedPin = remember(searchState.droppedPin) {
        searchState.droppedPin?.let { pin ->
            pin.resolvedPlace ?: Place(
                id = "pin:pending",
                name = "Pinned location",
                subtitle = "",
                location = pin.location
            )
        }
    }

    HalaTujuTheme(themeMode = settings.themeMode) {
        val navController = rememberNavController()
        NavHost(
            navController = navController,
            startDestination = HalaTujuRoutes.HOME
        ) {
            composable(HalaTujuRoutes.HOME) {
                HalaTujuHomeMapShell(
                    mapState = mainViewModel.toMapRenderingState(
                        state = uiState,
                        isDarkTheme = isDark,
                        waypoints = selectedWaypoints,
                        droppedPin = mapDroppedPin,
                        activeRoute = previewSelectedRoute,
                        alternativeRoutes = searchState.routePreview.candidateRoutes,
                        selectedRouteIndex = searchState.routePreview.selectedRouteIndex
                    ),
                    tileSource = mainViewModel.tileSource,
                    hasLocationPermission = uiState.permissionStatus.hasAnyLocation,
                    tiltPreviewEnabled = uiState.tiltPreviewEnabled,
                    searchState = searchState,
                    snackbarHostState = snackbarHostState,
                    onRequestLocationPermission = {
                        permissionLauncher.launch(LocationPermissionHelper.requiredRuntimePermissions())
                    },
                    onUserPanMap = mainViewModel::onUserPanMap,
                    onMapLongClick = searchViewModel::onMapLongClick,
                    onRecenterClick = mainViewModel::onRecenterClick,
                    onOpenSettings = { navController.navigate(HalaTujuRoutes.SETTINGS) },
                    onOpenAbout = { navController.navigate(HalaTujuRoutes.ABOUT) },
                    onSearchFieldFocused = searchViewModel::onFieldFocused,
                    onSearchQueryChanged = searchViewModel::onQueryChanged,
                    onClearSearchField = searchViewModel::onClearFieldQuery,
                    onSwapEndpoints = searchViewModel::onSwapEndpoints,
                    onSearchPlaceClick = searchViewModel::onPlaceSelected,
                    onUseMyLocationClick = searchViewModel::onUseMyLocationSelected,
                    onFavoriteShortcutClick = searchViewModel::onFavoriteShortcutClick,
                    onToggleSavePlace = searchViewModel::onToggleSavePlace,
                    onClearRecents = searchViewModel::onClearRecents,
                    onRetrySearch = searchViewModel::onRetrySearch,
                    onDismissSearchFocus = searchViewModel::dismissSearchFocus,
                    onApplyDroppedPinAs = searchViewModel::onApplyDroppedPinAs,
                    onSaveDroppedPinFavorite = searchViewModel::onSaveDroppedPinFavorite,
                    onDismissDroppedPin = searchViewModel::onDismissDroppedPin,
                    onSelectAlternativeRoute = searchViewModel::onSelectAlternativeRoute,
                    onAddStopClick = searchViewModel::onAddStopClick,
                    onMoveStopUp = searchViewModel::onMoveStopUp,
                    onMoveStopDown = searchViewModel::onMoveStopDown,
                    onRemoveStop = searchViewModel::onRemoveStop,
                    onRetryRoute = searchViewModel::onRetryRoute,
                    onDismissRoute = searchViewModel::onClearRoute,
                    onStartNavigation = { route ->
                        navigationViewModel.startNavigation(route)
                        navController.navigate(HalaTujuRoutes.NAVIGATION) {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable(HalaTujuRoutes.NAVIGATION) {
                ActiveNavigationScreen(
                    uiState = navState,
                    tileSource = navigationViewModel.tileSource,
                    isDarkTheme = isDark,
                    onUserPanMap = navigationViewModel::onUserPanMap,
                    onToggleOverview = navigationViewModel::onToggleOverview,
                    onRecenterClick = navigationViewModel::onRecenterClick,
                    onToggleMute = navigationViewModel::onToggleMute,
                    onContinueToNextStop = navigationViewModel::onContinueToNextStop,
                    onEndNavigation = {
                        val wasFinalArrival =
                            navState.session.status == com.mbk.halatuju.core.navigation.NavigationStatus.ARRIVED_DESTINATION
                        navigationViewModel.endNavigation()
                        if (wasFinalArrival) {
                            searchViewModel.onClearRoute()
                        }
                        navController.popBackStack()
                    },
                    onOpenAbout = { navController.navigate(HalaTujuRoutes.ABOUT) }
                )
            }

            composable(HalaTujuRoutes.SETTINGS) {
                SettingsScreen(
                    settings = settings,
                    onThemeModeChange = settingsViewModel::setThemeMode,
                    onUnitSystemChange = settingsViewModel::setUnitSystem,
                    onVoiceEnabledChange = settingsViewModel::setVoiceEnabled,
                    onAvoidancesChange = settingsViewModel::setAvoidances,
                    onDeveloperDebugOverlayChange = settingsViewModel::setDeveloperDebugOverlayEnabled,
                    onOpenAboutLicenses = { navController.navigate(HalaTujuRoutes.ABOUT) },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(HalaTujuRoutes.ABOUT) {
                AboutLicensesScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

@Composable
fun HalaTujuHomeMapShell(
    mapState: MapRenderingState,
    tileSource: TileSource,
    hasLocationPermission: Boolean,
    tiltPreviewEnabled: Boolean,
    onRequestLocationPermission: () -> Unit,
    onUserPanMap: () -> Unit,
    onRecenterClick: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    searchState: SearchUiState = SearchUiState(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onMapLongClick: (LatLng) -> Unit = {},
    onSearchFieldFocused: (SearchField) -> Unit = {},
    onSearchQueryChanged: (SearchField, String) -> Unit = { _, _ -> },
    onClearSearchField: (SearchField) -> Unit = {},
    onSwapEndpoints: () -> Unit = {},
    onSearchPlaceClick: (Place) -> Unit = {},
    onUseMyLocationClick: () -> Unit = {},
    onFavoriteShortcutClick: (FavoriteKind) -> Unit = {},
    onToggleSavePlace: (Place) -> Unit = {},
    onClearRecents: () -> Unit = {},
    onRetrySearch: () -> Unit = {},
    onDismissSearchFocus: () -> Unit = {},
    onApplyDroppedPinAs: (SearchField) -> Unit = {},
    onSaveDroppedPinFavorite: (FavoriteKind) -> Unit = {},
    onDismissDroppedPin: () -> Unit = {},
    onSelectAlternativeRoute: (Int) -> Unit = {},
    onAddStopClick: () -> Unit = {},
    onMoveStopUp: (Int) -> Unit = {},
    onMoveStopDown: (Int) -> Unit = {},
    onRemoveStop: (Int) -> Unit = {},
    onRetryRoute: () -> Unit = {},
    onDismissRoute: () -> Unit = {},
    onStartNavigation: (NavRoute) -> Unit = {}
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp
        val maxListHeight = if (maxHeight < 520.dp) 180.dp else 320.dp
        val showRoutePreview = searchState.isRoutePreviewVisible && searchState.routePreview.hasContent
        val density = LocalDensity.current
        val wideLeftInsetPx = with(density) {
            if (isWide) 410.dp.roundToPx() else 0
        }

        HalaTujuMapSurface(
            state = mapState,
            tileSource = tileSource,
            attributionPadding = PaddingValues(start = 12.dp, bottom = 14.dp),
            leftPanelInsetPx = wideLeftInsetPx,
            onUserPanMap = onUserPanMap,
            onMapLongClick = onMapLongClick,
            onAttributionClick = onOpenAbout,
            modifier = Modifier
                .fillMaxSize()
                .testTag("home_map_surface")
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            if (isWide) {
                // Tablet / foldable / landscape left rail holds search + route preview without blocking map right pane
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .widthIn(max = 396.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HomeSearchCard(
                        state = searchState,
                        onFieldFocused = onSearchFieldFocused,
                        onQueryChanged = onSearchQueryChanged,
                        onClearField = onClearSearchField,
                        onSwapClick = onSwapEndpoints,
                        onPlaceClick = onSearchPlaceClick,
                        onUseMyLocationClick = onUseMyLocationClick,
                        onFavoriteShortcutClick = onFavoriteShortcutClick,
                        onToggleSavePlace = onToggleSavePlace,
                        onClearRecents = onClearRecents,
                        onRetrySearch = onRetrySearch,
                        onDismissFocus = onDismissSearchFocus,
                        maxListHeight = maxListHeight
                    )

                    if (showRoutePreview) {
                        RoutePreviewSheet(
                            previewState = searchState.routePreview,
                            intermediateStops = searchState.intermediateStops,
                            unitSystem = searchState.unitSystem,
                            onSelectRoute = onSelectAlternativeRoute,
                            onAddStopClick = onAddStopClick,
                            onMoveStopUp = onMoveStopUp,
                            onMoveStopDown = onMoveStopDown,
                            onRemoveStop = onRemoveStop,
                            onRetryRoute = onRetryRoute,
                            onStartNavigation = onStartNavigation,
                            onDismissRoute = onDismissRoute
                        )
                    }
                }
            } else {
                HomeSearchCard(
                    state = searchState,
                    onFieldFocused = onSearchFieldFocused,
                    onQueryChanged = onSearchQueryChanged,
                    onClearField = onClearSearchField,
                    onSwapClick = onSwapEndpoints,
                    onPlaceClick = onSearchPlaceClick,
                    onUseMyLocationClick = onUseMyLocationClick,
                    onFavoriteShortcutClick = onFavoriteShortcutClick,
                    onToggleSavePlace = onToggleSavePlace,
                    onClearRecents = onClearRecents,
                    onRetrySearch = onRetrySearch,
                    onDismissFocus = onDismissSearchFocus,
                    maxListHeight = maxListHeight,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .widthIn(max = 420.dp)
                )
            }

            // Calm floating utility buttons (Settings + Recenter / 2.5D Toggle)
            if (!searchState.isFocused) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            top = if (isWide) 12.dp else 132.dp,
                            end = 14.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    FilledTonalIconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("settings_button")
                            .semantics { contentDescription = "Open settings" },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = null)
                    }

                    FilledTonalIconButton(
                        onClick = onRecenterClick,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("recenter_button")
                            .semantics {
                                contentDescription = when (mapState.cameraState.mode) {
                                    MapCameraMode.FREE_PAN, MapCameraMode.OVERVIEW -> "Recenter map on current location"
                                    else -> "Toggle 2.5D perspective tilt"
                                }
                            },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (
                                mapState.cameraState.mode == MapCameraMode.FREE_PAN ||
                                mapState.cameraState.mode == MapCameraMode.OVERVIEW ||
                                tiltPreviewEnabled
                            ) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                            },
                            contentColor = if (
                                mapState.cameraState.mode == MapCameraMode.FREE_PAN ||
                                mapState.cameraState.mode == MapCameraMode.OVERVIEW ||
                                tiltPreviewEnabled
                            ) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    ) {
                        Icon(
                            imageVector = if (tiltPreviewEnabled) Icons.Default.Explore else Icons.Default.MyLocation,
                            contentDescription = null
                        )
                    }
                }
            }

            if (!searchState.isFocused) {
                val pinState = searchState.droppedPin
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 52.dp)
                        .widthIn(max = 460.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (pinState != null) {
                        DroppedPinCard(
                            pinState = pinState,
                            onSetDestination = { onApplyDroppedPinAs(SearchField.TO) },
                            onSetOrigin = { onApplyDroppedPinAs(SearchField.FROM) },
                            onSaveFavorite = onSaveDroppedPinFavorite,
                            onDismiss = onDismissDroppedPin,
                            canAddStop = searchState.toEndpoint != null &&
                                searchState.intermediateStops.size < SearchUiState.MAX_INTERMEDIATE_STOPS,
                            onAddStop = { onApplyDroppedPinAs(SearchField.STOP) }
                        )
                    } else if (showRoutePreview && !isWide) {
                        RoutePreviewSheet(
                            previewState = searchState.routePreview,
                            intermediateStops = searchState.intermediateStops,
                            unitSystem = searchState.unitSystem,
                            onSelectRoute = onSelectAlternativeRoute,
                            onAddStopClick = onAddStopClick,
                            onMoveStopUp = onMoveStopUp,
                            onMoveStopDown = onMoveStopDown,
                            onRemoveStop = onRemoveStop,
                            onRetryRoute = onRetryRoute,
                            onStartNavigation = onStartNavigation,
                            onDismissRoute = onDismissRoute
                        )
                    } else if (!hasLocationPermission) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("location_permission_card"),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp)
                                ) {
                                    Text(
                                        text = "Location access needed",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Uses device GPS/Network without Google services.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = onRequestLocationPermission,
                                    modifier = Modifier.testTag("grant_location_button")
                                ) {
                                    Text("Allow")
                                }
                            }
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            )
        }
    }
}
