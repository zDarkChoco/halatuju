package com.mbk.halatuju.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mbk.halatuju.core.model.AppInfo

private data class OpenSourceNotice(
    val name: String,
    val license: String,
    val details: String
)

private val OpenSourceNotices = listOf(
    OpenSourceNotice(
        name = "OpenStreetMap Data",
        license = "ODbL 1.0 (© OpenStreetMap contributors)",
        details = "Map tiles, street network graph, speed limits (maxspeed), lane geometry, and place addresses derive from OpenStreetMap contributors. You are free to copy, distribute, transmit and adapt this data with attribution to OpenStreetMap contributors (openstreetmap.org/copyright)."
    ),
    OpenSourceNotice(
        name = "MapLibre Native Android",
        license = "BSD-2-Clause",
        details = "Open-source OpenGL ES vector tile renderer providing 2.5D tilt, course-up camera transformations, and 3D extruded buildings."
    ),
    OpenSourceNotice(
        name = "OpenFreeMap & OpenMapTiles Schema",
        license = "Open data / BSD / CC-BY 4.0",
        details = "Default keyless vector tile distribution and styling schema (Liberty + HalaTuju dark 3D building style). Tile URLs are configurable for self-hosted PMTiles or Martin servers."
    ),
    OpenSourceNotice(
        name = "Valhalla Routing Engine",
        license = "MIT License",
        details = "Open-source turn-by-turn routing engine (default FOSSGIS valhalla1.openstreetmap.de) providing OSRM-compatible maneuvers, lane guidance, and per-segment speed limits."
    ),
    OpenSourceNotice(
        name = "Photon & Nominatim",
        license = "Apache-2.0 / GPLv2+ (ODbL data)",
        details = "Photon handles biased search-as-you-type autocomplete; Nominatim handles strictly rate-limited (<= 1 req/s) reverse geocoding."
    ),
    OpenSourceNotice(
        name = "AndroidX, Kotlin, Compose, Hilt, Room, OkHttp",
        license = "Apache-2.0",
        details = "Core Android Jetpack UI, dependency injection, persistence, networking, and serialization libraries."
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutLicensesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("about_licenses_screen"),
        topBar = {
            TopAppBar(
                title = { Text("About & Open-Source Licenses") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "HalaTuju v${AppInfo.VERSION_NAME}",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.semantics { heading() }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "100% open-source, keyless turn-by-turn navigation built on OpenStreetMap.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "© OpenStreetMap contributors — Open Database License (ODbL 1.0)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item {
                Text(
                    text = "Open-source stack & data notices",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .semantics { heading() }
                )
            }

            items(OpenSourceNotices, key = { it.name }) { notice ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = notice.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = notice.license,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                        )
                        Text(
                            text = notice.details,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
