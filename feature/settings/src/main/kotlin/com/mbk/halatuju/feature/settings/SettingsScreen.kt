package com.mbk.halatuju.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mbk.halatuju.core.model.AppInfo
import com.mbk.halatuju.core.model.AppSettings
import com.mbk.halatuju.core.model.RoutingAvoidances
import com.mbk.halatuju.core.model.ThemeMode
import com.mbk.halatuju.core.model.UnitSystem

@OptIn(ExperimentalMaterial3Api::class)
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
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier
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
                .padding(innerPadding),
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
                                Text(label, maxLines = 1)
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
                                Text(label, maxLines = 1)
                            }
                        }
                    }
                }
            }

            item {
                SettingsSectionCard(title = "Routing & Voice") {
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
                }
            }

            item {
                SettingsSectionCard(title = "Developer") {
                    SettingsSwitchRow(
                        title = "Navigation telemetry overlay",
                        subtitle = "Show raw GPS, Kalman filter output, snapped point & off-route state",
                        checked = settings.developerDebugOverlayEnabled,
                        onCheckedChange = onDeveloperDebugOverlayChange
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
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
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
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
