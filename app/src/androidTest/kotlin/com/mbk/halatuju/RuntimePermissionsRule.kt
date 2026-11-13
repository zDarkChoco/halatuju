package com.mbk.halatuju

import android.Manifest
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Grants location and notification runtime permissions before [ActivityScenario] launches,
 * ordering `ACCESS_COARSE_LOCATION` before `ACCESS_FINE_LOCATION` for API 31+ reliability.
 */
class RuntimePermissionsRule : ExternalResource() {

    override fun before() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = instrumentation.targetContext.packageName
        val uiAutomation = instrumentation.uiAutomation

        val perms = buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        for (perm in perms) {
            val grantedViaUiAutomation = runCatching {
                uiAutomation.grantRuntimePermission(pkg, perm)
                true
            }.getOrDefault(false)
            if (!grantedViaUiAutomation) {
                runCatching {
                    val pfd = uiAutomation.executeShellCommand("pm grant $pkg $perm")
                    pfd.use {
                        it.fileDescriptor.let { fd ->
                            java.io.FileInputStream(fd).use { input -> input.readBytes() }
                        }
                    }
                }
            }
        }
    }
}
