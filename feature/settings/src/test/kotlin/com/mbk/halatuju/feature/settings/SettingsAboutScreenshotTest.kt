package com.mbk.halatuju.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.mbk.halatuju.core.location.GpxRecordingState
import com.mbk.halatuju.core.location.GpxReplayState
import com.mbk.halatuju.core.map.theme.HalaTujuTheme
import com.mbk.halatuju.core.model.AppSettings
import com.mbk.halatuju.core.model.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class SettingsAboutScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun m1SettingsLight() {
        composeRule.setContent {
            HalaTujuTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreen(
                    settings = AppSettings(themeMode = ThemeMode.LIGHT),
                    onThemeModeChange = {},
                    onUnitSystemChange = {},
                    onVoiceEnabledChange = {},
                    onAvoidancesChange = {},
                    onDeveloperDebugOverlayChange = {},
                    onOpenAboutLicenses = {},
                    onBack = {}
                )
            }
        }
        composeRule.onRoot().captureRoboImage("../../docs/screenshots/m1_settings_light.png")
    }

    @Test
    fun m1AboutLicensesDark() {
        composeRule.setContent {
            HalaTujuTheme(themeMode = ThemeMode.DARK) {
                AboutLicensesScreen(onBack = {})
            }
        }
        composeRule.onRoot().captureRoboImage("../../docs/screenshots/m1_about_dark.png")
    }

    @Test
    fun m7SettingsSimulationGpxAndEndpointsDark() {
        composeRule.setContent {
            HalaTujuTheme(themeMode = ThemeMode.DARK) {
                SettingsScreen(
                    settings = AppSettings(
                        themeMode = ThemeMode.DARK,
                        simulationEnabled = true,
                        simulationSpeedMultiplier = 2f,
                        simulationNoiseEnabled = true,
                        overspeedToleranceKmh = 5
                    ),
                    gpxRecordingState = GpxRecordingState(
                        isRecording = true,
                        recordedFixCount = 47
                    ),
                    gpxReplayState = GpxReplayState(
                        isActive = true,
                        trackName = "klcc_klia_commute.gpx",
                        pointCount = 128
                    ),
                    onThemeModeChange = {},
                    onUnitSystemChange = {},
                    onVoiceEnabledChange = {},
                    onAvoidancesChange = {},
                    onDeveloperDebugOverlayChange = {},
                    onOpenAboutLicenses = {},
                    onBack = {}
                )
            }
        }
        composeRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_simulation_card"))
        composeRule.onNodeWithTag("settings_simulation_card").assertIsDisplayed()
        composeRule.onNodeWithTag("sim_speed_2x").assertIsDisplayed()
        composeRule.onNodeWithTag("gpx_export_button").assertIsDisplayed()
        composeRule.onNodeWithTag("gpx_replay_stop_button").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("../../docs/screenshots/m7_settings_sim_gpx_dark.png")
    }

    @Test
    fun m7ServiceEndpointsDialogLight() {
        composeRule.setContent {
            HalaTujuTheme(themeMode = ThemeMode.LIGHT) {
                SettingsScreen(
                    settings = AppSettings(themeMode = ThemeMode.LIGHT),
                    onThemeModeChange = {},
                    onUnitSystemChange = {},
                    onVoiceEnabledChange = {},
                    onAvoidancesChange = {},
                    onDeveloperDebugOverlayChange = {},
                    onOpenAboutLicenses = {},
                    onBack = {}
                )
            }
        }
        composeRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_endpoints_card"))
        composeRule.onNodeWithTag("configure_endpoints_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("endpoints_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("endpoint_valhalla_input").assertIsDisplayed()
        composeRule.onNodeWithTag("endpoints_reset_button").assertIsDisplayed()
        composeRule.onNodeWithTag("endpoints_save_button").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("../../docs/screenshots/m7_endpoints_dialog_light.png")
    }
}
