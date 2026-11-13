package com.mbk.halatuju.feature.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.UnitSystem
import java.time.ZoneId

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutePreviewSheet(
    previewState: RoutePreviewUiState,
    intermediateStops: List<Place>,
    unitSystem: UnitSystem,
    onSelectRoute: (Int) -> Unit,
    onAddStopClick: () -> Unit,
    onMoveStopUp: (Int) -> Unit,
    onMoveStopDown: (Int) -> Unit,
    onRemoveStop: (Int) -> Unit,
    onRetryRoute: () -> Unit,
    onStartNavigation: (NavRoute) -> Unit,
    onDismissRoute: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    clockZoneId: ZoneId = ZoneId.systemDefault()
) {
    val selectedRoute = previewState.selectedRoute
    val avoidanceTags = RoutePreviewFormatter.formatAvoidancesSummary(previewState.activeAvoidances)
    val canAddMoreStops = intermediateStops.size < SearchUiState.MAX_INTERMEDIATE_STOPS
    val isLargeFontScale = LocalDensity.current.fontScale >= 1.55f

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("route_preview_sheet"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        val contentModifier = Modifier
            .fillMaxWidth()
            .let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp)
        Column(modifier = contentModifier) {
            if (previewState.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .testTag("route_loading_indicator")
                )
            }

            // Reorderable intermediate stops when present
            if (intermediateStops.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .testTag("intermediate_stops_section"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (intermediateStops.size == 1) "1 stop" else "${intermediateStops.size} stops",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    intermediateStops.forEachIndexed { idx, stop ->
                        StopRow(
                            index = idx,
                            total = intermediateStops.size,
                            place = stop,
                            onMoveUp = { onMoveStopUp(idx) },
                            onMoveDown = { onMoveStopDown(idx) },
                            onRemove = { onRemoveStop(idx) }
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                    )
                }
            }

            // Main summary or error
            when {
                previewState.errorMessage != null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = previewState.errorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            TextButton(
                                onClick = onRetryRoute,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("retry_route_button")
                            ) {
                                Text("Retry route")
                            }
                        }
                        IconButton(
                            onClick = onDismissRoute,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("close_route_preview_button")
                                .semantics { contentDescription = "Close route preview" }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                }

                selectedRoute != null -> {
                    val etaText = RoutePreviewFormatter.formatDurationSeconds(selectedRoute.adjustedDurationSeconds)
                    val distanceText = RoutePreviewFormatter.formatDistanceMeters(selectedRoute.totalDistanceMeters, unitSystem)
                    val arrivalText = RoutePreviewFormatter.formatArrivalClock(
                        departureEpochMs = previewState.referenceEpochMs,
                        durationSeconds = selectedRoute.adjustedDurationSeconds,
                        zoneId = clockZoneId
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                itemVerticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = etaText,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ) {
                                    Text(
                                        text = selectedRoute.trafficLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                            .testTag("route_traffic_label")
                                    )
                                }
                                avoidanceTags.forEach { tag ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    ) {
                                        Text(
                                            text = tag,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "$distanceText · Arrive $arrivalText",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (selectedRoute.summaryLabel.isNotBlank()) {
                                Text(
                                    text = "via ${selectedRoute.summaryLabel}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismissRoute,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("close_route_preview_button")
                                .semantics { contentDescription = "Close route preview" }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }

                    // Up to 3 selectable alternative route cards sized to remain legible at standard & 200% font scale
                    if (previewState.candidateRoutes.size > 1) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val stripModifier = if (isLargeFontScale) {
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .testTag("alternative_routes_strip")
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .testTag("alternative_routes_strip")
                        }
                        Row(
                            modifier = stripModifier,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            previewState.candidateRoutes.take(3).forEachIndexed { idx, candidate ->
                                val isSelected = idx == previewState.selectedRouteIndex
                                val altEta = RoutePreviewFormatter.formatDurationSeconds(candidate.adjustedDurationSeconds)
                                val altDist = RoutePreviewFormatter.formatDistanceMeters(candidate.totalDistanceMeters, unitSystem)
                                val cardWidthMod = if (isLargeFontScale) {
                                    Modifier.widthIn(min = 136.dp, max = 200.dp)
                                } else {
                                    Modifier.weight(1f)
                                }
                                Surface(
                                    modifier = cardWidthMod
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable(role = Role.Tab) { onSelectRoute(idx) }
                                        .testTag("route_alternative_$idx")
                                        .semantics {
                                            selected = isSelected
                                            contentDescription = "Route ${idx + 1}: $altEta, $altDist"
                                        },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                    },
                                    contentColor = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    border = if (isSelected) {
                                        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                                    } else {
                                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                                    }
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                        Text(
                                            text = altEta,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = altDist,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (candidate.summaryLabel.isNotBlank()) {
                                            Text(
                                                text = candidate.summaryLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                previewState.isLoading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Calculating route...",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismissRoute,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("close_route_preview_button")
                                .semantics { contentDescription = "Close route preview" }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Primary actions: calm secondary "Add stop" + prominent primary "Start"
            if (isLargeFontScale) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { selectedRoute?.let(onStartNavigation) },
                        enabled = selectedRoute != null && !previewState.isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("start_navigation_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Start",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = onAddStopClick,
                        enabled = canAddMoreStops,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("add_stop_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddLocationAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add stop", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onAddStopClick,
                        enabled = canAddMoreStops,
                        modifier = Modifier
                            .heightIn(min = 52.dp)
                            .testTag("add_stop_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddLocationAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add stop", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }

                    Button(
                        onClick = { selectedRoute?.let(onStartNavigation) },
                        enabled = selectedRoute != null && !previewState.isLoading,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .testTag("start_navigation_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Start",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StopRow(
    index: Int,
    total: Int,
    place: Place,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("stop_row_$index"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = place.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onMoveUp,
            enabled = index > 0,
            modifier = Modifier
                .size(48.dp)
                .testTag("move_stop_up_$index")
                .semantics { contentDescription = "Move ${place.name} earlier" }
        ) {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(
            onClick = onMoveDown,
            enabled = index < total - 1,
            modifier = Modifier
                .size(48.dp)
                .testTag("move_stop_down_$index")
                .semantics { contentDescription = "Move ${place.name} later" }
        ) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .size(48.dp)
                .testTag("remove_stop_$index")
                .semantics { contentDescription = "Remove stop ${place.name}" }
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
