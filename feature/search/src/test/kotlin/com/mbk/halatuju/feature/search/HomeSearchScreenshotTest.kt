package com.mbk.halatuju.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.mbk.halatuju.core.map.theme.HalaTujuTheme
import com.mbk.halatuju.core.model.FavoriteKind
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class HomeSearchScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val klccPlace = Place(
        id = "photon:N:5391966854",
        name = "KLCC",
        subtitle = "Jalan Ampang, Kuala Lumpur, Malaysia",
        location = LatLng(3.1592, 101.7134)
    )

    private val kliaPlace = Place(
        id = "photon:W:1001",
        name = "KLIA Terminal 1",
        subtitle = "Sepang, Selangor, Malaysia",
        location = KlDefaults.KLIA
    )

    private val homePlace = Place(
        id = "home:1",
        name = "Bangsar South",
        subtitle = "Kerinchi, Kuala Lumpur",
        location = LatLng(3.1112, 101.6660),
        favoriteKind = FavoriteKind.HOME
    )

    private val workPlace = Place(
        id = "work:1",
        name = "Menara Petronas",
        subtitle = "KLCC, Kuala Lumpur",
        location = KlDefaults.KLCC,
        favoriteKind = FavoriteKind.WORK
    )

    @Test
    fun collapsedSearchCardHidesRecentsAndFavoritesUntilFocused() {
        var focusedField: SearchField? = null
        composeRule.setContent {
            HalaTujuTheme(themeMode = ThemeMode.LIGHT) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFEFF3F3))
                        .padding(16.dp)
                ) {
                    HomeSearchCard(
                        state = SearchUiState(
                            fromEndpoint = RouteEndpoint.MyLocation,
                            toEndpoint = null,
                            focusedField = null,
                            recents = listOf(klccPlace, kliaPlace),
                            favorites = listOf(homePlace, workPlace)
                        ),
                        onFieldFocused = { focusedField = it },
                        onQueryChanged = { _, _ -> },
                        onClearField = {},
                        onSwapClick = {},
                        onPlaceClick = {},
                        onUseMyLocationClick = {},
                        onFavoriteShortcutClick = {},
                        onToggleSavePlace = {},
                        onClearRecents = {},
                        onRetrySearch = {},
                        onDismissFocus = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("home_search_card").assertIsDisplayed()
        composeRule.onNodeWithText("My location").assertIsDisplayed()
        composeRule.onNodeWithText("Where to?").assertIsDisplayed()
        composeRule.onNodeWithTag("swap_endpoints_button").assertIsDisplayed()
        composeRule.onNodeWithTag("search_focused_panel").assertDoesNotExist()
        composeRule.onNodeWithText("Recents").assertDoesNotExist()
        composeRule.onNodeWithText("Bangsar South").assertDoesNotExist()

        composeRule.onNodeWithTag("search_to_input").performClick()
        assertEquals(SearchField.TO, focusedField)
    }

    @Test
    fun focusedSearchShowsPhotonSuggestionsAndCapturesLight() {
        composeRule.setContent {
            HalaTujuTheme(themeMode = ThemeMode.LIGHT) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFEFF3F3))
                        .padding(16.dp)
                ) {
                    HomeSearchCard(
                        state = SearchUiState(
                            fromEndpoint = RouteEndpoint.MyLocation,
                            toEndpoint = null,
                            focusedField = SearchField.TO,
                            fromQueryText = "My location",
                            toQueryText = "KLCC",
                            suggestions = listOf(
                                klccPlace,
                                Place(
                                    id = "photon:W:23034402",
                                    name = "KLCC Convention Center",
                                    subtitle = "Bukit Bintang, Kuala Lumpur, Malaysia",
                                    location = LatLng(3.1543, 101.7126)
                                )
                            ),
                            favorites = listOf(homePlace, workPlace)
                        ),
                        onFieldFocused = {},
                        onQueryChanged = { _, _ -> },
                        onClearField = {},
                        onSwapClick = {},
                        onPlaceClick = {},
                        onUseMyLocationClick = {},
                        onFavoriteShortcutClick = {},
                        onToggleSavePlace = {},
                        onClearRecents = {},
                        onRetrySearch = {},
                        onDismissFocus = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("search_focused_panel").assertIsDisplayed()
        composeRule.onNodeWithText("Bangsar South").assertIsDisplayed()
        composeRule.onNodeWithText("KLCC Convention Center").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("../../docs/screenshots/m2_search_focused_light.png")
    }

    @Test
    fun focusedRecentsAndDroppedPinDarkModeCapturesCleanly() {
        var swapped = false
        composeRule.setContent {
            HalaTujuTheme(themeMode = ThemeMode.DARK) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0E1318))
                        .padding(16.dp)
                ) {
                    HomeSearchCard(
                        state = SearchUiState(
                            fromEndpoint = RouteEndpoint.SelectedPlace(klccPlace),
                            toEndpoint = RouteEndpoint.SelectedPlace(kliaPlace),
                            focusedField = SearchField.TO,
                            fromQueryText = klccPlace.name,
                            toQueryText = "",
                            recents = listOf(klccPlace, kliaPlace),
                            favorites = listOf(homePlace, workPlace)
                        ),
                        onFieldFocused = {},
                        onQueryChanged = { _, _ -> },
                        onClearField = {},
                        onSwapClick = { swapped = true },
                        onPlaceClick = {},
                        onUseMyLocationClick = {},
                        onFavoriteShortcutClick = {},
                        onToggleSavePlace = {},
                        onClearRecents = {},
                        onRetrySearch = {},
                        onDismissFocus = {},
                        modifier = Modifier.align(Alignment.TopCenter)
                    )

                    DroppedPinCard(
                        pinState = DroppedPinState(
                            location = KlDefaults.KL_SENTRAL,
                            resolvedPlace = Place(
                                id = "nominatim:1",
                                name = "KL Sentral",
                                subtitle = "Jalan Stesen Sentral, Brickfields, Kuala Lumpur",
                                location = KlDefaults.KL_SENTRAL
                            )
                        ),
                        onSetDestination = {},
                        onSetOrigin = {},
                        onSaveFavorite = {},
                        onDismiss = {},
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }

        composeRule.onNodeWithText("Recents").assertIsDisplayed()
        composeRule.onNodeWithTag("dropped_pin_card").assertIsDisplayed()
        composeRule.onNodeWithTag("swap_endpoints_button").performClick()
        assertTrue(swapped)

        composeRule.onRoot().captureRoboImage("../../docs/screenshots/m2_search_focused_dark.png")
    }
}
