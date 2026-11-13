package com.mbk.halatuju

import android.util.Log
import androidx.test.uiautomator.By
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
        dismissSystemDialogs(device)
    }

    fun dismissSystemDialogs(device: UiDevice) {
        runCatching {
            if (device.findObject(By.textContains("isn't responding")) != null) {
                for (label in listOf("Wait", "Close app", "OK")) {
                    val btn = device.findObject(By.text(label))
                    if (btn != null) {
                        btn.click()
                        Thread.sleep(300L)
                        break
                    }
                }
            }
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
            val outPath = "$DEVICE_DIR/$filename"
            // UiAutomation.executeShellCommand splits tokens on whitespace; avoid spaces/quotes inside -c.
            device.executeShellCommand("sh -c IFS=:;c=dumpsys:notification:--noredact;\$c>$outPath")
            val fullDump = device.executeShellCommand("dumpsys notification --noredact")
            val relevantLines = fullDump.lineSequence()
                .filter { line ->
                    line.contains("halatuju", ignoreCase = true) ||
                        line.contains("ProgressStyle", ignoreCase = true) ||
                        line.contains("mPromoted", ignoreCase = true) ||
                        line.contains("shortCriticalText", ignoreCase = true)
                }
                .take(40)
                .joinToString("\n")
            Log.i("HalaTujuNavDump", "Notification dump ($filename, ${fullDump.length} chars):\n$relevantLines")
        }
    }

    fun collapseStatusBar(device: UiDevice) {
        runCatching {
            device.executeShellCommand("cmd statusbar collapse")
        }
    }
}
