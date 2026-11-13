package com.mbk.halatuju

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.mbk.halatuju.core.map.MapRenderingState
import com.mbk.halatuju.core.map.OpenFreeMapTileSource
import com.mbk.halatuju.core.map.theme.HalaTujuTheme
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.MapCameraMode
import com.mbk.halatuju.core.model.MapCameraState
import com.mbk.halatuju.core.model.PuckRenderState
import com.mbk.halatuju.core.model.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = RobolectricDeviceQualifiers.Pixel5)
class MainShellScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun m1MapShellLight() {
        val tileSource = OpenFreeMapTileSource(RuntimeEnvironment.getApplication())
        composeRule.setContent {
            HalaTujuTheme(themeMode = ThemeMode.LIGHT) {
                HalaTujuHomeMapShell(
                    mapState = MapRenderingState(
                        isDarkTheme = false,
                        cameraState = MapCameraState(mode = MapCameraMode.FOLLOW_PUCK, center = KlDefaults.KLCC),
                        puckState = PuckRenderState(position = KlDefaults.KLCC, bearingDegrees = 42f, accuracyMeters = 8f)
                    ),
                    tileSource = tileSource,
                    hasLocationPermission = true,
                    tiltPreviewEnabled = false,
                    onRequestLocationPermission = {},
                    onUserPanMap = {},
                    onRecenterClick = {},
                    onOpenSettings = {},
                    onOpenAbout = {}
                )
            }
        }
        composeRule.onNodeWithText("© OpenStreetMap contributors").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_button").assertIsDisplayed()
        composeRule.onNodeWithTag("home_search_card").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("../docs/screenshots/m1_map_light.png")
        composeRule.onRoot().captureRoboImage("../docs/screenshots/m2_search_collapsed_light.png")
    }

    @Test
    fun m1MapShellDarkWithPermissionCard() {
        val tileSource = OpenFreeMapTileSource(RuntimeEnvironment.getApplication())
        composeRule.setContent {
            HalaTujuTheme(themeMode = ThemeMode.DARK) {
                HalaTujuHomeMapShell(
                    mapState = MapRenderingState(
                        isDarkTheme = true,
                        cameraState = MapCameraState(mode = MapCameraMode.NAVIGATION_25D, center = KlDefaults.KLCC, tiltDegrees = 52.0),
                        puckState = PuckRenderState(position = KlDefaults.KLCC, bearingDegrees = 315f, accuracyMeters = 10f)
                    ),
                    tileSource = tileSource,
                    hasLocationPermission = false,
                    tiltPreviewEnabled = true,
                    onRequestLocationPermission = {},
                    onUserPanMap = {},
                    onRecenterClick = {},
                    onOpenSettings = {},
                    onOpenAbout = {}
                )
            }
        }
        composeRule.onNodeWithText("© OpenStreetMap contributors").assertIsDisplayed()
        composeRule.onNodeWithTag("location_permission_card").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("../docs/screenshots/m1_map_dark.png")
    }
}
