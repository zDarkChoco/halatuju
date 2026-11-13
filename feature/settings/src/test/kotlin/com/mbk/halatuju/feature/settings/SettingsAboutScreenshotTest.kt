package com.mbk.halatuju.feature.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
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
}
