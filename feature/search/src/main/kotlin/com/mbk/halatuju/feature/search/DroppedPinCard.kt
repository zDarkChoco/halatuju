package com.mbk.halatuju.feature.search

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mbk.halatuju.core.model.FavoriteKind
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DroppedPinCard(
    pinState: DroppedPinState,
    onSetDestination: () -> Unit,
    onSetOrigin: () -> Unit,
    onSaveFavorite: (FavoriteKind) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    canAddStop: Boolean = false,
    onAddStop: () -> Unit = {}
) {
    val place = pinState.resolvedPlace
    val title = place?.name ?: if (pinState.isLoading) "Looking up address..." else "Dropped pin"
    val subtitle = place?.subtitle?.takeIf { it.isNotBlank() }
        ?: String.format(Locale.US, "%.5f, %.5f", pinState.location.latitude, pinState.location.longitude)
    val isLargeFontScale = LocalDensity.current.fontScale >= 1.55f

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("dropped_pin_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (pinState.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("dismiss_pin_button")
                        .semantics { contentDescription = "Dismiss dropped pin" }
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = null)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AssistChip(
                    onClick = { onSaveFavorite(FavoriteKind.HOME) },
                    label = { Text("Home", maxLines = 1) },
                    leadingIcon = {
                        Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                AssistChip(
                    onClick = { onSaveFavorite(FavoriteKind.WORK) },
                    label = { Text("Work", maxLines = 1) },
                    leadingIcon = {
                        Icon(Icons.Default.Work, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                AssistChip(
                    onClick = { onSaveFavorite(FavoriteKind.NONE) },
                    label = { Text("Save", maxLines = 1) },
                    leadingIcon = {
                        Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                if (canAddStop) {
                    AssistChip(
                        onClick = onAddStop,
                        label = { Text("Add stop", maxLines = 1) },
                        leadingIcon = {
                            Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("pin_add_stop_chip")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isLargeFontScale) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onSetDestination,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("pin_set_to_button")
                    ) {
                        Text("Set Destination", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = onSetOrigin,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("pin_set_from_button")
                    ) {
                        Text("Set From", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onSetOrigin,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("pin_set_from_button")
                    ) {
                        Text("Set From", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick = onSetDestination,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("pin_set_to_button")
                    ) {
                        Text("Set Destination", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
