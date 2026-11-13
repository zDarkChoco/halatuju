package com.mbk.halatuju

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
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
class MainShellScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun m0ShellLight() {
        composeRule.setContent {
            HalaTujuAppShell(themeMode = ThemeMode.LIGHT)
        }
        composeRule.onRoot().captureRoboImage("../docs/screenshots/m0_shell_light.png")
    }

    @Test
    fun m0ShellDark() {
        composeRule.setContent {
            HalaTujuAppShell(themeMode = ThemeMode.DARK)
        }
        composeRule.onRoot().captureRoboImage("../docs/screenshots/m0_shell_dark.png")
    }
}
