package com.mbk.halatuju

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.ManeuverModifier
import com.mbk.halatuju.core.model.ManeuverType
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.RouteLeg
import com.mbk.halatuju.core.model.RouteStep
import com.mbk.halatuju.core.navigation.NavigationSessionManager
import com.mbk.halatuju.core.navigation.NavigationStatus
import com.mbk.halatuju.feature.navigation.NavigationNotificationFactory
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NavigationNotificationInstrumentedTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val permissionsRule = RuntimePermissionsRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var sessionManager: NavigationSessionManager

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @After
    fun tearDown() {
        if (::sessionManager.isInitialized) {
            runCatching { sessionManager.endNavigation() }
        }
        runCatching {
            val manager = InstrumentationRegistry.getInstrumentation().targetContext
                .getSystemService(NotificationManager::class.java)
            manager.cancel(NavigationNotificationFactory.NOTIFICATION_ID)
        }
    }

    @Test
    fun homeMapRendersAttributionSettingsAndForegroundServicePostsPlatformNotification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        composeRule.waitForIdle()
        composeRule.onNodeWithText("© OpenStreetMap contributors").assertIsDisplayed()
        composeRule.onNodeWithTag("home_search_card").assertIsDisplayed()

        // Open Settings and verify M6 Permissions & M7 Simulation/Service Endpoints sections are reachable
        composeRule.onNodeWithTag("settings_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_simulation_card"))
        composeRule.onNodeWithTag("settings_simulation_card").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_endpoints_card"))
        composeRule.onNodeWithTag("settings_endpoints_card").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_list").performScrollToNode(hasText("Permissions & Background"))
        composeRule.onNodeWithText("Permissions & Background").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()

        // Start active navigation through real NavigationSessionManager -> NavigationForegroundService
        val sampleRoute = buildKlSampleRoute()
        instrumentation.runOnMainSync {
            sessionManager.startNavigation(sampleRoute)
        }

        // Wait up to 10s for ForegroundService to post active notification on OS
        val posted = waitForActiveNotification(notificationManager, timeoutMs = 10_000L)
        assertNotNull("Expected NavigationForegroundService notification on active notifications", posted)
        val notification = posted!!.notification

        assertEquals(Notification.CATEGORY_NAVIGATION, notification.category)
        assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        val endAction = notification.actions.orEmpty().firstOrNull { it.title?.toString() == "End" }
        assertNotNull("Expected End notification action", endAction)

        val channel = notificationManager.getNotificationChannel(NavigationNotificationFactory.CHANNEL_ID)
        assertNotNull(channel)
        assertTrue(channel.importance != NotificationManager.IMPORTANCE_MIN)

        if (Build.VERSION.SDK_INT >= 36) {
            val template = notification.extras.getString(Notification.EXTRA_TEMPLATE)
            val promotable = NotificationCompat.hasPromotableCharacteristics(notification)
            val canPostPromoted = notificationManager.canPostPromotedNotifications()
            Log.i(
                "HalaTujuNavTest",
                "API=${Build.VERSION.SDK_INT} SDK_FULL=${Build.VERSION.SDK_INT_FULL} " +
                    "template=$template promotable=$promotable " +
                    "canPostPromoted=$canPostPromoted " +
                    "shortCritical=${NotificationCompat.getShortCriticalText(notification)} " +
                    "flags=${notification.flags}"
            )
            assertEquals("android.app.Notification\$ProgressStyle", template)
            assertTrue(NotificationCompat.isRequestPromotedOngoing(notification))
            assertFalse(notification.extras.getBoolean(Notification.EXTRA_COLORIZED, false))
            assertFalse(NotificationCompat.getShortCriticalText(notification).isNullOrBlank())
            if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
                assertTrue(
                    "Expected Android 16 QPR1+ notification to satisfy hasPromotableCharacteristics()",
                    promotable
                )
            }
        } else {
            val progressMax = notification.extras.getInt(NotificationCompat.EXTRA_PROGRESS_MAX)
            Log.i(
                "HalaTujuNavTest",
                "API=${Build.VERSION.SDK_INT} fallback ongoing progressMax=$progressMax"
            )
            assertEquals(1_000, progressMax)
        }

        // Capture live app & notification shade screenshots before ending navigation
        val screenshotDir = context.getExternalFilesDir("ci_screenshots")?.apply { mkdirs() }
        if (screenshotDir != null) {
            device.takeScreenshot(File(screenshotDir, "ci_01_notif_active_nav.png"))
            runCatching {
                device.openNotification()
                Thread.sleep(700L)
                device.takeScreenshot(File(screenshotDir, "ci_02_notif_shade.png"))
                device.pressBack()
                Thread.sleep(300L)
            }
        }

        // Trigger the exact PendingIntent attached to the notification's "End" action button
        endAction!!.actionIntent.send()

        val cleared = waitUntil(timeoutMs = 8_000L) {
            sessionManager.state.value.status == NavigationStatus.IDLE &&
                notificationManager.activeNotifications.none { it.id == NavigationNotificationFactory.NOTIFICATION_ID }
        }
        assertTrue("Expected End notification action to stop navigation and cancel notification", cleared)
    }

    private fun waitForActiveNotification(
        manager: NotificationManager,
        timeoutMs: Long
    ) = run {
        val deadline = System.currentTimeMillis() + timeoutMs
        var found = manager.activeNotifications.firstOrNull {
            it.id == NavigationNotificationFactory.NOTIFICATION_ID
        }
        while (found == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(150L)
            found = manager.activeNotifications.firstOrNull {
                it.id == NavigationNotificationFactory.NOTIFICATION_ID
            }
        }
        found
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(150L)
        }
        return predicate()
    }

    private fun buildKlSampleRoute(): NavRoute {
        val pts = listOf(KlDefaults.KLCC, KlDefaults.KL_SENTRAL, KlDefaults.KLIA)
        return NavRoute(
            id = "e2e_notif_test",
            geometry = pts,
            cumulativeDistancesMeters = listOf(0.0, 6_200.0, 56_000.0),
            totalDistanceMeters = 56_000.0,
            baseDurationSeconds = 2_600.0,
            adjustedDurationSeconds = 2_880.0,
            trafficEstimated = true,
            legs = listOf(
                RouteLeg(
                    summary = "Lebuhraya KLIA",
                    distanceMeters = 56_000.0,
                    durationSeconds = 2_600.0,
                    steps = listOf(
                        RouteStep(
                            instructionText = "Turn right onto Lebuhraya KLIA",
                            roadName = "Lebuhraya KLIA",
                            maneuverType = ManeuverType.TURN,
                            maneuverModifier = ManeuverModifier.RIGHT,
                            maneuverLocation = KlDefaults.KLCC,
                            bearingBefore = 0.0,
                            bearingAfter = 180.0,
                            distanceMeters = 280.0,
                            durationSeconds = 30.0,
                            geometryStartIndex = 0,
                            geometryEndIndex = 1
                        )
                    )
                )
            ),
            waypoints = listOf(
                Place("klcc", "KLCC", "", KlDefaults.KLCC),
                Place("klia", "KLIA Terminal 1", "", KlDefaults.KLIA)
            )
        )
    }
}
