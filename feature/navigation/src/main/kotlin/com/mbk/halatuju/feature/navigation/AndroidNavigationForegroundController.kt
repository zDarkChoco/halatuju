package com.mbk.halatuju.feature.navigation

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mbk.halatuju.core.location.LocationPermissionHelper
import com.mbk.halatuju.core.model.UnitSystem
import com.mbk.halatuju.core.navigation.NavigationForegroundController
import com.mbk.halatuju.core.navigation.NavigationSessionState
import com.mbk.halatuju.core.navigation.NavigationStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavigationNotificationPromotionState @Inject constructor() {
    private val _demoted = MutableStateFlow(false)
    val demoted: StateFlow<Boolean> = _demoted.asStateFlow()

    fun reset() {
        _demoted.value = false
    }

    fun markDemoted() {
        _demoted.value = true
    }

    fun isDemoted(): Boolean = _demoted.value
}

@Singleton
class AndroidNavigationForegroundController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val promotionState: NavigationNotificationPromotionState
) : NavigationForegroundController {

    private val fallbackThrottler = NavigationNotificationThrottler()
    @Volatile
    private var runningFallbackWithoutFgs = false

    override fun onNavigationStarted() {
        promotionState.reset()
        fallbackThrottler.reset()

        val hasLocation = LocationPermissionHelper.hasAnyLocationPermission(context)
        if (hasLocation) {
            val startedFgs = runCatching {
                val intent = Intent(context, NavigationForegroundService::class.java).apply {
                    action = NavigationForegroundService.ACTION_START_OR_SYNC
                }
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrDefault(false)
            runningFallbackWithoutFgs = !startedFgs
        } else {
            // Avoid SecurityException when running Simulation without location permission;
            // still deliver throttled Live Update notifications when notification permission exists.
            runningFallbackWithoutFgs = true
        }
    }

    override fun onNavigationStateChanged(state: NavigationSessionState, unitSystem: UnitSystem) {
        if (!runningFallbackWithoutFgs) return
        if (state.status == NavigationStatus.IDLE) {
            NotificationManagerCompat.from(context).cancel(NavigationNotificationFactory.NOTIFICATION_ID)
            runningFallbackWithoutFgs = false
            fallbackThrottler.reset()
            return
        }
        val payload = NavigationNotificationContentMapper.map(state, unitSystem)
        if (fallbackThrottler.shouldPost(payload, SystemClock.elapsedRealtime())) {
            NavigationNotificationFactory.postOrUpdateNotification(
                context = context,
                payload = payload,
                requestPromotedOngoing = payload.isOngoing && !promotionState.isDemoted()
            )
            if (state.status == NavigationStatus.ARRIVED_DESTINATION) {
                runningFallbackWithoutFgs = false
            }
        }
    }

    override fun onNavigationStopped() {
        runningFallbackWithoutFgs = false
        fallbackThrottler.reset()
        runCatching {
            context.stopService(Intent(context, NavigationForegroundService::class.java))
        }
        NotificationManagerCompat.from(context).cancel(NavigationNotificationFactory.NOTIFICATION_ID)
    }
}
