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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import com.mbk.halatuju.core.map.OpenFreeMapTileSource
import com.mbk.halatuju.core.map.TileSource
import com.mbk.halatuju.core.map.theme.HalaTujuTheme
import com.mbk.halatuju.core.model.MapCameraMode
import com.mbk.halatuju.core.model.ThemeMode
import com.mbk.halatuju.feature.settings.AboutLicensesScreen
import com.mbk.halatuju.feature.settings.SettingsScreen
import com.mbk.halatuju.feature.settings.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint

private object HalaTujuRoutes {
    const val HOME = "home"
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
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    val isDark = when (settings.themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    LaunchedEffect(isDark) {
        onSystemBarThemeUpdate(isDark)
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

    HalaTujuTheme(themeMode = settings.themeMode) {
        val navController = rememberNavController()
        NavHost(
            navController = navController,
            startDestination = HalaTujuRoutes.HOME
        ) {
            composable(HalaTujuRoutes.HOME) {
                HalaTujuHomeMapShell(
                    mapState = mainViewModel.toMapRenderingState(uiState, isDarkTheme = isDark),
                    tileSource = mainViewModel.tileSource,
                    hasLocationPermission = uiState.permissionStatus.hasAnyLocation,
                    tiltPreviewEnabled = uiState.tiltPreviewEnabled,
                    onRequestLocationPermission = {
                        permissionLauncher.launch(LocationPermissionHelper.requiredRuntimePermissions())
                    },
                    onUserPanMap = mainViewModel::onUserPanMap,
                    onRecenterClick = mainViewModel::onRecenterClick,
                    onOpenSettings = { navController.navigate(HalaTujuRoutes.SETTINGS) },
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
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        HalaTujuMapSurface(
            state = mapState,
            tileSource = tileSource,
            attributionPadding = PaddingValues(start = 12.dp, bottom = 36.dp),
            onUserPanMap = onUserPanMap,
            onAttributionClick = onOpenAbout,
            modifier = Modifier
                .fillMaxSize()
                .testTag("home_map_surface")
        )

        // Calm floating utility buttons (Settings + Recenter / 2.5D Toggle) inside safe drawing insets
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 12.dp, end = 12.dp),
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
                        contentDescription = if (mapState.cameraState.mode == MapCameraMode.FREE_PAN) {
                            "Recenter map on current location"
                        } else {
                            "Toggle 2.5D perspective tilt"
                        }
                    },
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (mapState.cameraState.mode == MapCameraMode.FREE_PAN || tiltPreviewEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                    },
                    contentColor = if (mapState.cameraState.mode == MapCameraMode.FREE_PAN || tiltPreviewEnabled) {
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

        if (!hasLocationPermission) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 16.dp, end = 16.dp, bottom = 72.dp)
                    .widthIn(max = 460.dp)
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
