package com.mbk.halatuju

import androidx.test.uiautomator.UiDevice

/**
 * Saves emulator screenshots and live notification dumps into `/data/local/tmp` via the ADB shell
 * user so artifacts survive `:app:connectedDebugAndroidTest`'s post-test package uninstall cleanly.
 */
object CiArtifactCapture {
    const val DEVICE_DIR = "/data/local/tmp/halatuju_ci_screenshots"

    fun prepareDir(device: UiDevice) {
        runCatching {
            device.executeShellCommand("mkdir -p $DEVICE_DIR")
            device.executeShellCommand("chmod 777 $DEVICE_DIR")
        }
    }

    fun captureScreenshot(device: UiDevice, filename: String) {
        prepareDir(device)
        runCatching {
            device.executeShellCommand("screencap -p $DEVICE_DIR/$filename")
        }
    }

    fun captureNotificationDump(device: UiDevice, filename: String = "dumpsys_active_nav.txt") {
        prepareDir(device)
        runCatching {
            device.executeShellCommand("sh -c 'dumpsys notification --noredact > $DEVICE_DIR/$filename'")
        }
    }

    fun collapseStatusBar(device: UiDevice) {
        runCatching {
            device.executeShellCommand("cmd statusbar collapse")
        }
    }
}
