package com.mbk.halatuju.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mbk.halatuju.core.location.GpxRecordingState
import com.mbk.halatuju.core.location.GpxReplayState
import com.mbk.halatuju.core.model.AppInfo
import com.mbk.halatuju.core.model.AppSettings
import com.mbk.halatuju.core.model.RoutingAvoidances
import com.mbk.halatuju.core.model.ServiceEndpoints
import com.mbk.halatuju.core.model.ThemeMode
import com.mbk.halatuju.core.model.UnitSystem
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onUnitSystemChange: (UnitSystem) -> Unit,
    onVoiceEnabledChange: (Boolean) -> Unit,
    onAvoidancesChange: (RoutingAvoidances) -> Unit,
    onDeveloperDebugOverlayChange: (Boolean) -> Unit,
    onOpenAboutLicenses: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    permissionStatus: com.mbk.halatuju.core.location.PermissionStatusSnapshot? = null,
    onOpenNotificationSettings: () -> Unit = {},
    onOpenBatteryOptimizationSettings: () -> Unit = {},
    onVoiceVolumeChange: (Float) -> Unit = {},
    onOverspeedToleranceChange: (Int) -> Unit = {},
    onSimulationEnabledChange: (Boolean) -> Unit = {},
    onSimulationSpeedMultiplierChange: (Float) -> Unit = {},
    onSimulationNoiseEnabledChange: (Boolean) -> Unit = {},
    onEndpointsChange: (ServiceEndpoints) -> Unit = {},
    gpxRecordingState: GpxRecordingState = GpxRecordingState(),
    gpxReplayState: GpxReplayState = GpxReplayState(),
    onToggleGpxRecording: (Boolean) -> Unit = {},
    onExportGpx: () -> Unit = {},
    onClearGpxRecording: () -> Unit = {},
    onRequestImportGpx: () -> Unit = {},
    onStopGpxReplay: () -> Unit = {}
) {
    var showEndpointsDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .testTag("settings_screen"),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("settings_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSectionCard(title = "Map & Appearance") {
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val options = listOf(
                            ThemeMode.FOLLOW_SYSTEM to "System",
                            ThemeMode.LIGHT to "Light",
                            ThemeMode.DARK to "Dark"
                        )
                        options.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                                modifier = Modifier.testTag("theme_option_${mode.name.lowercase()}")
                            ) {
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Distance & Speed Units",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val unitOptions = listOf(
                            UnitSystem.METRIC to "km / km/h",
                            UnitSystem.IMPERIAL to "mi / mph"
                        )
                        unitOptions.forEachIndexed { index, (unit, label) ->
                            SegmentedButton(
                                selected = settings.unitSystem == unit,
                                onClick = { onUnitSystemChange(unit) },
                                shape = SegmentedButtonDefaults.itemShape(index, unitOptions.size)
                            ) {
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            item {
                SettingsSectionCard(title = "Routing, Voice & Speed") {
                    SettingsSwitchRow(
                        title = "Avoid tolls",
                        subtitle = "Prefer toll-free routes (AKLEH, MEX, LDP, PLUS)",
                        checked = settings.avoidances.avoidTolls,
                        onCheckedChange = {
                            onAvoidancesChange(settings.avoidances.copy(avoidTolls = it))
                        }
                    )
                    SettingsSwitchRow(
                        title = "Avoid highways",
                        subtitle = "Use trunk and urban arterial roads when possible",
                        checked = settings.avoidances.avoidHighways,
                        onCheckedChange = {
                            onAvoidancesChange(settings.avoidances.copy(avoidHighways = it))
                        }
                    )
                    SettingsSwitchRow(
                        title = "Avoid ferries",
                        subtitle = "Exclude car ferries and water crossings",
                        checked = settings.avoidances.avoidFerries,
                        onCheckedChange = {
                            onAvoidancesChange(settings.avoidances.copy(avoidFerries = it))
                        }
                    )
                    SettingsSwitchRow(
                        title = "Voice guidance",
                        subtitle = "Speak turn prompts with transient media ducking",
                        checked = settings.voiceEnabled,
                        onCheckedChange = onVoiceEnabledChange
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Voice & chime volume",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )
                        Text(
                            text = "${(settings.voiceVolume * 100f).roundToInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = settings.voiceVolume.coerceIn(0f, 1f),
                        onValueChange = onVoiceVolumeChange,
                        enabled = settings.voiceEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("voice_volume_slider")
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Overspeed chime tolerance",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(0, 3, 5, 10, 15).forEach { tol ->
                            FilterChip(
                                selected = settings.overspeedToleranceKmh == tol,
                                onClick = { onOverspeedToleranceChange(tol) },
                                label = { Text(if (tol == 0) "Exact (0)" else "+$tol km/h") },
                                modifier = Modifier.testTag("overspeed_tol_$tol")
                            )
                        }
                    }
                }
            }

            item {
                SettingsSectionCard(
                    title = "Simulation & GPX Testing",
                    modifier = Modifier.testTag("settings_simulation_card")
                ) {
                    SettingsSwitchRow(
                        title = "Simulate route",
                        subtitle = "Drive mock fixes through real Kalman, snapping, TTS, speed & notification logic",
                        checked = settings.simulationEnabled,
                        onCheckedChange = onSimulationEnabledChange,
                        switchTag = "simulation_enabled_switch"
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Simulation speed multiplier",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val speeds = listOf(1f to "1x", 2f to "2x", 4f to "4x")
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        speeds.forEachIndexed { index, (mult, label) ->
                            SegmentedButton(
                                selected = abs(settings.simulationSpeedMultiplier - mult) < 0.25f,
                                onClick = { onSimulationSpeedMultiplierChange(mult) },
                                shape = SegmentedButtonDefaults.itemShape(index, speeds.size),
                                modifier = Modifier.testTag("sim_speed_$label")
                            ) {
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    SettingsSwitchRow(
                        title = "Synthetic GPS noise & jitter",
                        subtitle = "Inject realistic ENU spread & bearing jitter along simulated routes",
                        checked = settings.simulationNoiseEnabled,
                        onCheckedChange = onSimulationNoiseEnabledChange,
                        switchTag = "simulation_noise_switch"
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
                    )

                    SettingsSwitchRow(
                        title = "Record raw GPS trace",
                        subtitle = if (gpxRecordingState.recordedFixCount == 0) {
                            "Capture raw fixes + timestamps for field bug reproduction"
                        } else {
                            "${gpxRecordingState.recordedFixCount} points recorded"
                        },
                        checked = gpxRecordingState.isRecording,
                        onCheckedChange = onToggleGpxRecording,
                        switchTag = "gpx_record_switch"
                    )

                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = onExportGpx,
                            enabled = gpxRecordingState.recordedFixCount > 0,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("gpx_export_button")
                        ) {
                            Text(
                                text = "Export GPX (${gpxRecordingState.recordedFixCount})",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(
                            onClick = onClearGpxRecording,
                            enabled = gpxRecordingState.recordedFixCount > 0,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("gpx_clear_button")
                        ) {
                            Text("Clear")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = onRequestImportGpx,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("gpx_replay_import_button")
                        ) {
                            Text(
                                text = "Replay .gpx file",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (gpxReplayState.isActive) {
                            OutlinedButton(
                                onClick = onStopGpxReplay,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("gpx_replay_stop_button")
                            ) {
                                Text(
                                    text = "Stop replay",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (gpxReplayState.isActive && gpxReplayState.trackName != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Replaying ${gpxReplayState.trackName} (${gpxReplayState.pointCount} points)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    gpxReplayState.errorMessage?.let { err ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            item {
                SettingsSectionCard(
                    title = "Service Endpoints (Open Data)",
                    modifier = Modifier.testTag("settings_endpoints_card")
                ) {
                    EndpointPreviewLine(label = "Light map style", url = settings.endpoints.lightStyleUrl)
                    EndpointPreviewLine(label = "Dark tile source", url = settings.endpoints.darkTileSourceUrl)
                    EndpointPreviewLine(label = "Valhalla routing", url = settings.endpoints.valhallaBaseUrl)
                    EndpointPreviewLine(label = "Photon search", url = settings.endpoints.photonBaseUrl)
                    EndpointPreviewLine(label = "Nominatim reverse", url = settings.endpoints.nominatimBaseUrl)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showEndpointsDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("configure_endpoints_button")
                    ) {
                        Text(
                            text = "Configure custom / self-hosted endpoints",
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            if (permissionStatus != null) {
                item {
                    SettingsSectionCard(title = "Permissions & Background") {
                        SettingsActionRow(
                            title = "Notifications & Live Updates",
                            subtitle = when {
                                !permissionStatus.hasNotificationPermission ->
                                    "Off — turn-by-turn progress notification blocked"
                                android.os.Build.VERSION.SDK_INT >= 36 && !permissionStatus.canPostPromotedNotifications ->
                                    "Allowed — enable Live Updates promotion in system settings"
                                android.os.Build.VERSION.SDK_INT >= 36 ->
                                    "Active — Android 16 Live Updates enabled"
                                else ->
                                    "Active — ongoing notification enabled"
                            },
                            onClick = onOpenNotificationSettings
                        )
                        SettingsActionRow(
                            title = "Battery optimization",
                            subtitle = if (permissionStatus.isIgnoringBatteryOptimizations) {
                                "Unrestricted — reliable when screen is off"
                            } else {
                                "Restricted — allow unrestricted background guidance if turns lag"
                            },
                            onClick = onOpenBatteryOptimizationSettings
                        )
                    }
                }
            }

            item {
                SettingsSectionCard(title = "Developer") {
                    SettingsSwitchRow(
                        title = "Navigation telemetry overlay",
                        subtitle = "Show raw GPS, Kalman filter output, snapped point, off-route state & simulation fault buttons",
                        checked = settings.developerDebugOverlayEnabled,
                        onCheckedChange = onDeveloperDebugOverlayChange,
                        switchTag = "developer_debug_switch"
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onOpenAboutLicenses)
                        .testTag("about_licenses_row"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "About & Open-Source Licenses",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "HalaTuju v${AppInfo.VERSION_NAME} • OSM ODbL 1.0 & stack notices",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null
                        )
                    }
                }
            }
        }
        }

        if (showEndpointsDialog) {
            ServiceEndpointsDialog(
                current = settings.endpoints,
                onDismiss = { showEndpointsDialog = false },
                onSave = { updated ->
                    onEndpointsChange(updated)
                    showEndpointsDialog = false
                }
            )
        }
    }
}

@Composable
private fun EndpointPreviewLine(label: String, url: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.55f)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceEndpointsDialog(
    current: ServiceEndpoints,
    onDismiss: () -> Unit,
    onSave: (ServiceEndpoints) -> Unit
) {
    var lightStyle by remember(current) { mutableStateOf(current.lightStyleUrl) }
    var darkTile by remember(current) { mutableStateOf(current.darkTileSourceUrl) }
    var valhalla by remember(current) { mutableStateOf(current.valhallaBaseUrl) }
    var photon by remember(current) { mutableStateOf(current.photonBaseUrl) }
    var nominatim by remember(current) { mutableStateOf(current.nominatimBaseUrl) }

    BackHandler(onBack = onDismiss)

    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = onDismiss
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 500.dp)
                .fillMaxWidth()
                .clickable(
                    interactionSource = cardInteraction,
                    indication = null,
                    onClick = {}
                )
                .semantics { paneTitle = "Service Endpoints dialog" }
                .testTag("endpoints_dialog"),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "Service Endpoints",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AlertDialogDefaults.titleContentColor,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 10.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = lightStyle,
                        onValueChange = { lightStyle = it },
                        label = { Text("Light style URL", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("endpoint_light_input")
                    )
                    OutlinedTextField(
                        value = darkTile,
                        onValueChange = { darkTile = it },
                        label = { Text("Dark TileJSON URL", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("endpoint_dark_input")
                    )
                    OutlinedTextField(
                        value = valhalla,
                        onValueChange = { valhalla = it },
                        label = { Text("Valhalla base URL", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("endpoint_valhalla_input")
                    )
                    OutlinedTextField(
                        value = photon,
                        onValueChange = { photon = it },
                        label = { Text("Photon URL", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("endpoint_photon_input")
                    )
                    OutlinedTextField(
                        value = nominatim,
                        onValueChange = { nominatim = it },
                        label = { Text("Nominatim URL", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("endpoint_nominatim_input")
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = {
                            val defaults = ServiceEndpoints()
                            lightStyle = defaults.lightStyleUrl
                            darkTile = defaults.darkTileSourceUrl
                            valhalla = defaults.valhallaBaseUrl
                            photon = defaults.photonBaseUrl
                            nominatim = defaults.nominatimBaseUrl
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("endpoints_reset_button")
                    ) {
                        Text("Reset defaults")
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val defaults = ServiceEndpoints()
                            onSave(
                                ServiceEndpoints(
                                    lightStyleUrl = lightStyle.trim().ifBlank { defaults.lightStyleUrl },
                                    darkTileSourceUrl = darkTile.trim().ifBlank { defaults.darkTileSourceUrl },
                                    valhallaBaseUrl = valhalla.trim().trimEnd('/').ifBlank { defaults.valhallaBaseUrl },
                                    photonBaseUrl = photon.trim().trimEnd('/').ifBlank { defaults.photonBaseUrl },
                                    nominatimBaseUrl = nominatim.trim().trimEnd('/').ifBlank { defaults.nominatimBaseUrl }
                                )
                            )
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("endpoints_save_button")
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTag: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .let { if (switchTag != null) it.testTag(switchTag) else it }
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null
        )
    }
}
