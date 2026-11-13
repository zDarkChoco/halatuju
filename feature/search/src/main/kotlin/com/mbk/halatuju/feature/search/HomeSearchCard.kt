package com.mbk.halatuju.feature.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mbk.halatuju.core.model.FavoriteKind
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.UnitSystem

@Composable
fun HomeSearchCard(
    state: SearchUiState,
    onFieldFocused: (SearchField) -> Unit,
    onQueryChanged: (SearchField, String) -> Unit,
    onClearField: (SearchField) -> Unit,
    onSwapClick: () -> Unit,
    onPlaceClick: (Place) -> Unit,
    onUseMyLocationClick: () -> Unit,
    onFavoriteShortcutClick: (FavoriteKind) -> Unit,
    onToggleSavePlace: (Place) -> Unit,
    onClearRecents: () -> Unit,
    onRetrySearch: () -> Unit,
    onDismissFocus: () -> Unit,
    modifier: Modifier = Modifier,
    maxListHeight: androidx.compose.ui.unit.Dp = 320.dp
) {
    val focusManager = LocalFocusManager.current
    val fromFocusRequester = remember { FocusRequester() }
    val toFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = state.isFocused) {
        focusManager.clearFocus(force = true)
        onDismissFocus()
    }

    val closeFocus = {
        focusManager.clearFocus(force = true)
        onDismissFocus()
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("home_search_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top two-field header (always visible: From + Where to? + Swap button)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SearchEndpointRow(
                        label = "From",
                        value = state.fromQueryText,
                        placeholder = SearchUiState.MY_LOCATION_LABEL,
                        isMyLocationStyled = state.fromEndpoint is RouteEndpoint.MyLocation &&
                            state.focusedField != SearchField.FROM,
                        isFocused = state.focusedField == SearchField.FROM,
                        showClear = (state.focusedField == SearchField.FROM && state.fromQueryText.isNotEmpty()) ||
                            (state.fromEndpoint is RouteEndpoint.SelectedPlace),
                        focusRequester = fromFocusRequester,
                        testTag = "search_from_input",
                        clearTestTag = "clear_from_button",
                        leadingDot = {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            )
                        },
                        onFocused = { onFieldFocused(SearchField.FROM) },
                        onValueChange = { onQueryChanged(SearchField.FROM, it) },
                        onClearClick = { onClearField(SearchField.FROM) }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 28.dp, end = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
                    )

                    SearchEndpointRow(
                        label = "Destination",
                        value = state.toQueryText,
                        placeholder = "Where to?",
                        isMyLocationStyled = state.toEndpoint is RouteEndpoint.MyLocation &&
                            state.focusedField != SearchField.TO,
                        isFocused = state.focusedField == SearchField.TO,
                        showClear = state.toQueryText.isNotEmpty() || state.toEndpoint != null,
                        focusRequester = toFocusRequester,
                        testTag = "search_to_input",
                        clearTestTag = "clear_to_button",
                        leadingDot = {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondary)
                            )
                        },
                        onFocused = { onFieldFocused(SearchField.TO) },
                        onValueChange = { onQueryChanged(SearchField.TO, it) },
                        onClearClick = { onClearField(SearchField.TO) }
                    )
                }

                IconButton(
                    onClick = onSwapClick,
                    enabled = state.canSwap,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("swap_endpoints_button")
                        .semantics { contentDescription = "Swap From and Destination" }
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapVert,
                        contentDescription = null,
                        tint = if (state.canSwap) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        }
                    )
                }
            }

            // Recents, Favorites & Photon suggestions appear strictly when a field is focused
            AnimatedVisibility(
                visible = state.isFocused,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                SearchFocusedPanel(
                    state = state,
                    maxListHeight = maxListHeight,
                    onUseMyLocationClick = {
                        focusManager.clearFocus(force = true)
                        onUseMyLocationClick()
                    },
                    onFavoriteShortcutClick = onFavoriteShortcutClick,
                    onPlaceClick = { place ->
                        focusManager.clearFocus(force = true)
                        onPlaceClick(place)
                    },
                    onToggleSavePlace = onToggleSavePlace,
                    onClearRecents = onClearRecents,
                    onRetrySearch = onRetrySearch,
                    onCollapseClick = closeFocus
                )
            }
        }
    }
}

@Composable
private fun SearchEndpointRow(
    label: String,
    value: String,
    placeholder: String,
    isMyLocationStyled: Boolean,
    isFocused: Boolean,
    showClear: Boolean,
    focusRequester: FocusRequester,
    testTag: String,
    clearTestTag: String,
    leadingDot: @Composable () -> Unit,
    onFocused: () -> Unit,
    onValueChange: (String) -> Unit,
    onClearClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) {
                focusRequester.requestFocus()
                onFocused()
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(28.dp),
            contentAlignment = Alignment.Center
        ) {
            leadingDot()
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (isMyLocationStyled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (isMyLocationStyled || isFocused) FontWeight.Medium else FontWeight.Normal
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { /* debouncer runs automatically */ }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { fs -> if (fs.isFocused) onFocused() }
                    .testTag(testTag)
                    .semantics { contentDescription = label }
            )
        }

        if (showClear) {
            IconButton(
                onClick = onClearClick,
                modifier = Modifier
                    .size(40.dp)
                    .testTag(clearTestTag)
                    .semantics { contentDescription = "Clear $label" }
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SearchFocusedPanel(
    state: SearchUiState,
    maxListHeight: androidx.compose.ui.unit.Dp,
    onUseMyLocationClick: () -> Unit,
    onFavoriteShortcutClick: (FavoriteKind) -> Unit,
    onPlaceClick: (Place) -> Unit,
    onToggleSavePlace: (Place) -> Unit,
    onClearRecents: () -> Unit,
    onRetrySearch: () -> Unit,
    onCollapseClick: () -> Unit
) {
    val homeFav = state.homeFavorite()
    val workFav = state.workFavorite()
    val savedIds = remember(state.favorites) { state.favorites.map { it.id }.toSet() }
    val activeQuery = state.activeQuery.trim()
    val showLiveResults = activeQuery.length >= 2 &&
        !activeQuery.equals(SearchUiState.MY_LOCATION_LABEL, ignoreCase = true)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search_focused_panel")
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

        if (state.isSearching) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
            )
        }

        state.pendingFavoriteAssignment?.let { kind ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (kind == FavoriteKind.HOME) {
                        "Pick a place to save as Home"
                    } else {
                        "Pick a place to save as Work"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // Quick shortcuts strip: Use My location, Home, Work, Collapse
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChip(
                onClick = onUseMyLocationClick,
                label = { Text("My location", maxLines = 1) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier.testTag("shortcut_my_location")
            )

            AssistChip(
                onClick = { onFavoriteShortcutClick(FavoriteKind.HOME) },
                label = { Text(homeFav?.name ?: "Set Home", maxLines = 1) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = if (homeFav != null) {
                    AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    AssistChipDefaults.assistChipColors()
                },
                modifier = Modifier.testTag("shortcut_home")
            )

            AssistChip(
                onClick = { onFavoriteShortcutClick(FavoriteKind.WORK) },
                label = { Text(workFav?.name ?: "Set Work", maxLines = 1) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Work,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = if (workFav != null) {
                    AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    AssistChipDefaults.assistChipColors()
                },
                modifier = Modifier.testTag("shortcut_work")
            )

            AssistChip(
                onClick = onCollapseClick,
                label = { Text("Hide", maxLines = 1) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                modifier = Modifier.testTag("collapse_search_button")
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxListHeight)
        ) {
            when {
                showLiveResults -> {
                    when {
                        state.searchError != null -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = state.searchError,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                TextButton(onClick = onRetrySearch) {
                                    Text("Retry")
                                }
                            }
                        }

                        !state.isSearching && state.suggestions.isEmpty() -> {
                            Text(
                                text = "No matching places found for \"$activeQuery\".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }

                        else -> {
                            LazyColumn(modifier = Modifier.testTag("search_suggestions_list")) {
                                items(state.suggestions, key = { it.id }) { place ->
                                    PlaceRow(
                                        place = place,
                                        isRecent = false,
                                        isSaved = place.id in savedIds,
                                        anchor = state.distanceAnchor,
                                        unitSystem = state.unitSystem,
                                        onClick = { onPlaceClick(place) },
                                        onToggleSave = { onToggleSavePlace(place) }
                                    )
                                }
                            }
                        }
                    }
                }

                else -> {
                    val savedExtras = state.savedFavorites()
                    LazyColumn(modifier = Modifier.testTag("search_recents_list")) {
                        if (savedExtras.isNotEmpty()) {
                            item(key = "header_saved") {
                                SectionHeaderRow(title = "Saved places")
                            }
                            items(savedExtras, key = { "fav_${it.id}" }) { place ->
                                PlaceRow(
                                    place = place,
                                    isRecent = false,
                                    isSaved = true,
                                    anchor = state.distanceAnchor,
                                    unitSystem = state.unitSystem,
                                    onClick = { onPlaceClick(place) },
                                    onToggleSave = { onToggleSavePlace(place) }
                                )
                            }
                        }

                        if (state.recents.isNotEmpty()) {
                            item(key = "header_recents") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Recents",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    TextButton(onClick = onClearRecents) {
                                        Text("Clear")
                                    }
                                }
                            }
                            items(state.recents, key = { "rec_${it.id}" }) { place ->
                                PlaceRow(
                                    place = place,
                                    isRecent = true,
                                    isSaved = place.id in savedIds,
                                    anchor = state.distanceAnchor,
                                    unitSystem = state.unitSystem,
                                    onClick = { onPlaceClick(place) },
                                    onToggleSave = { onToggleSavePlace(place) }
                                )
                            }
                        } else if (savedExtras.isEmpty()) {
                            item(key = "empty_hint") {
                                Text(
                                    text = "Type a place name or long-press anywhere on the map to drop a pin.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeaderRow(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)
    )
}

@Composable
private fun PlaceRow(
    place: Place,
    isRecent: Boolean,
    isSaved: Boolean,
    anchor: LatLng,
    unitSystem: UnitSystem,
    onClick: () -> Unit,
    onToggleSave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isRecent) Icons.Default.History else Icons.Default.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = place.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (place.subtitle.isNotBlank()) {
                Text(
                    text = place.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = SearchDistanceFormatter.formatDistance(anchor, place.location, unitSystem),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        IconButton(
            onClick = onToggleSave,
            modifier = Modifier
                .size(44.dp)
                .semantics {
                    contentDescription = if (isSaved) "Remove ${place.name} from favorites" else "Save ${place.name} to favorites"
                }
        ) {
            Icon(
                imageVector = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = null,
                tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
