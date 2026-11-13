package com.mbk.halatuju.feature.navigation

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.ManeuverModifier
import com.mbk.halatuju.core.model.ManeuverType
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.RouteLeg
import com.mbk.halatuju.core.model.RouteStep
import com.mbk.halatuju.core.model.UnitSystem
import com.mbk.halatuju.core.navigation.GuidanceBannerState
import com.mbk.halatuju.core.navigation.NavigationSessionState
import com.mbk.halatuju.core.navigation.NavigationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NavigationNotificationFactoryTest {

    @Test
    fun `api 36 notification uses ProgressStyle Live Update and satisfies promotable rules`() {
        val context = RuntimeEnvironment.getApplication()
        val payload = NavigationNotificationContentMapper.map(
            session = sampleActiveSession(progressFraction = 0.35f),
            unitSystem = UnitSystem.METRIC,
            zoneId = ZoneId.of("Asia/Kuala_Lumpur")
        )

        val notification = NavigationNotificationFactory.buildNotification(
            context = context,
            payload = payload,
            requestPromotedOngoing = true
        )

        assertEquals(Notification.CATEGORY_NAVIGATION, notification.category)
        assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue((notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0)
        @Suppress("DEPRECATION")
        assertNull(notification.contentView)

        assertTrue(NotificationCompat.isRequestPromotedOngoing(notification))
        assertEquals("280m", NotificationCompat.getShortCriticalText(notification))
        assertTrue((NotificationCompat.getShortCriticalText(notification)?.length ?: 99) <= 6)
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_COLORIZED, false))

        if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
            assertTrue(
                "Expected Android 16 QPR1+ notification to satisfy hasPromotableCharacteristics()",
                notification.hasPromotableCharacteristics()
            )
            assertTrue(NotificationCompat.hasPromotableCharacteristics(notification))
            assertTrue(NavigationNotificationFactory.isNotificationPromotedOnSystem(notification))
        }

        val title = notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
        val text = notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()
        assertNotNull(title)
        assertNotNull(text)
        assertTrue("Expected distance & road in title, got $title", title!!.contains("280 m") && title.contains("Lebuhraya KLIA"))
        assertTrue("Expected estimated label in subtitle, got $text", text!!.contains("estimated"))

        // Native API 36 Notification.ProgressStyle verification + stop point mapping
        assertEquals("android.app.Notification\$ProgressStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(350, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(1_000, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(listOf(214), payload.stopProgressMarkersPermille)

        // Channel must not be IMPORTANCE_MIN
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(NavigationNotificationFactory.CHANNEL_ID)
        assertNotNull(channel)
        assertTrue(channel.importance != NotificationManager.IMPORTANCE_MIN)

        // End action exists cleanly
        val actions = notification.actions.orEmpty()
        assertTrue(actions.any { it.title?.toString() == "End" })
    }

    @Test
    fun `demoted or pre-Android-16 mode keeps standard ongoing progress notification without promotion request`() {
        assertFalse(NavigationNotificationFactory.supportsLiveUpdatePromotions(34))
        assertFalse(NavigationNotificationFactory.supportsLiveUpdatePromotions(35))
        assertTrue(NavigationNotificationFactory.supportsLiveUpdatePromotions(36))

        val context = RuntimeEnvironment.getApplication()
        val payload = NavigationNotificationContentMapper.map(
            session = sampleActiveSession(progressFraction = 0.60f),
            unitSystem = UnitSystem.METRIC,
            zoneId = ZoneId.of("Asia/Kuala_Lumpur")
        )

        val notification = NavigationNotificationFactory.buildNotification(
            context = context,
            payload = payload,
            requestPromotedOngoing = false
        )

        assertEquals(Notification.CATEGORY_NAVIGATION, notification.category)
        assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertFalse(NotificationCompat.isRequestPromotedOngoing(notification))
        assertEquals(600, notification.extras.getInt(NotificationCompat.EXTRA_PROGRESS))
        assertEquals(1_000, notification.extras.getInt(NotificationCompat.EXTRA_PROGRESS_MAX))
        assertTrue(notification.actions.orEmpty().any { it.title?.toString() == "End" })
    }

    @Test
    fun `arrival destination notification is non-ongoing and dismissible without End action`() {
        val context = RuntimeEnvironment.getApplication()
        val payload = NavigationNotificationContentMapper.map(
            session = sampleActiveSession(progressFraction = 1f).copy(
                status = NavigationStatus.ARRIVED_DESTINATION
            ),
            unitSystem = UnitSystem.METRIC,
            zoneId = ZoneId.of("Asia/Kuala_Lumpur")
        )

        val notification = NavigationNotificationFactory.buildNotification(
            context = context,
            payload = payload,
            requestPromotedOngoing = true
        )

        assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) == 0)
        assertTrue((notification.flags and Notification.FLAG_AUTO_CANCEL) != 0)
        assertFalse(NotificationCompat.isRequestPromotedOngoing(notification))
        assertEquals("You have arrived", notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString())
        assertTrue(notification.actions.isNullOrEmpty())
    }

    @Test
    fun `compact status chip distance always stays within 6 characters`() {
        val samplesMetric = listOf(4.0, 45.0, 280.0, 950.0, 1_450.0, 12_300.0, 148_000.0)
        samplesMetric.forEach { d ->
            val label = NavigationNotificationContentMapper.formatCompactChipDistance(d, UnitSystem.METRIC)
            assertTrue("Expected <=6 chars for $d m, got '$label'", label.length <= 6)
        }
        val samplesImperial = listOf(10.0, 120.0, 500.0, 1_800.0, 25_000.0, 180_000.0)
        samplesImperial.forEach { d ->
            val label = NavigationNotificationContentMapper.formatCompactChipDistance(d, UnitSystem.IMPERIAL)
            assertTrue("Expected <=6 chars for $d m imperial, got '$label'", label.length <= 6)
        }
    }

    @Test
    fun `throttler collapses rapid unchanged ticks while reacting immediately to steps and state changes`() {
        val throttler = NavigationNotificationThrottler(
            farIntervalMs = 2_000L,
            nearManeuverIntervalMs = 1_000L,
            nearManeuverThresholdMeters = 250.0
        )

        val p0 = NavigationNotificationContentMapper.map(
            sampleActiveSession(progressFraction = 0.20f, distanceToManeuver = 800.0, stepIndex = 0),
            UnitSystem.METRIC
        )
        assertTrue(throttler.shouldPost(p0, nowMs = 1_000L))

        // Too soon & far from maneuver -> throttled
        val p1 = NavigationNotificationContentMapper.map(
            sampleActiveSession(progressFraction = 0.21f, distanceToManeuver = 740.0, stepIndex = 0),
            UnitSystem.METRIC
        )
        assertFalse(throttler.shouldPost(p1, nowMs = 1_800L))

        // Past farIntervalMs with visible text change -> allowed
        assertTrue(throttler.shouldPost(p1, nowMs = 3_100L))

        // Crossing 300 m threshold triggers immediate update
        val p2 = NavigationNotificationContentMapper.map(
            sampleActiveSession(progressFraction = 0.25f, distanceToManeuver = 280.0, stepIndex = 0),
            UnitSystem.METRIC
        )
        assertTrue(throttler.shouldPost(p2, nowMs = 3_500L))

        // Maneuver step change triggers immediate update
        val p3 = NavigationNotificationContentMapper.map(
            sampleActiveSession(progressFraction = 0.28f, distanceToManeuver = 420.0, stepIndex = 1),
            UnitSystem.METRIC
        )
        assertTrue(throttler.shouldPost(p3, nowMs = 3_600L))

        // Identical visible snapshot even well after interval -> deduplicated
        assertFalse(throttler.shouldPost(p3, nowMs = 9_900L))

        // Rerouting state change triggers immediately
        val p4 = p3.copy(isRerouting = true, title = "Rerouting…", shortCriticalText = "Wait…")
        assertTrue(throttler.shouldPost(p4, nowMs = 9_950L))
    }

    private fun sampleActiveSession(
        progressFraction: Float = 0.35f,
        distanceToManeuver: Double = 280.0,
        stepIndex: Int = 1
    ): NavigationSessionState {
        val route = NavRoute(
            id = "route_m6_test",
            geometry = listOf(KlDefaults.KLCC, KlDefaults.KL_SENTRAL, KlDefaults.KLIA),
            cumulativeDistancesMeters = listOf(0.0, 6_000.0, 56_000.0),
            totalDistanceMeters = 56_000.0,
            baseDurationSeconds = 2_700.0,
            adjustedDurationSeconds = 3_000.0,
            trafficEstimated = true,
            legs = listOf(
                RouteLeg("Jalan Damansara", 12_000.0, 900.0, steps = emptyList()),
                RouteLeg("Lebuhraya KLIA", 44_000.0, 2_100.0, steps = emptyList())
            ),
            waypoints = listOf(
                Place("a", "KLCC", "", KlDefaults.KLCC),
                Place("b", "KL Sentral", "", KlDefaults.KL_SENTRAL),
                Place("c", "KLIA Terminal 1", "", KlDefaults.KLIA)
            )
        )
        return NavigationSessionState(
            status = NavigationStatus.ACTIVE,
            route = route,
            currentLegIndex = 0,
            currentStepIndex = stepIndex,
            distanceAlongRouteMeters = 56_000.0 * progressFraction,
            remainingDistanceMeters = 56_000.0 * (1f - progressFraction),
            remainingDurationSeconds = 3_000.0 * (1f - progressFraction),
            estimatedArrivalEpochMs = 1_760_000_000_000L,
            etaTrafficLabel = "estimated",
            progressFraction = progressFraction,
            banner = GuidanceBannerState(
                maneuverType = ManeuverType.TURN,
                maneuverModifier = ManeuverModifier.RIGHT,
                distanceToManeuverMeters = distanceToManeuver,
                primaryText = "Lebuhraya KLIA"
            )
        )
    }
}
