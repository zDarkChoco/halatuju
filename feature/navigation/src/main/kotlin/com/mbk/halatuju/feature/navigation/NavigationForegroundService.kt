package com.mbk.halatuju.feature.navigation

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mbk.halatuju.core.location.LocationPermissionHelper
import com.mbk.halatuju.core.model.UnitSystem
import com.mbk.halatuju.core.navigation.NavigationSessionManager
import com.mbk.halatuju.core.navigation.NavigationStatus
import com.mbk.halatuju.core.storage.SettingsStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Location foreground service keeping turn-by-turn guidance alive in background/screen-off
 * and maintaining the Android 16 Live Update (ProgressStyle) or pre-36 ongoing notification.
 */
@AndroidEntryPoint
class NavigationForegroundService : LifecycleService() {

    @Inject
    lateinit var sessionManager: NavigationSessionManager

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var promotionState: NavigationNotificationPromotionState

    private val throttler = NavigationNotificationThrottler()
    private var observerJob: Job? = null
    private var isForegroundPromoted = false
    private var sawNonIdleState = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (!LocationPermissionHelper.hasAnyLocationPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isForegroundPromoted) {
            NavigationNotificationFactory.ensureNotificationChannel(this)
            val initialPayload = NavigationNotificationContentMapper.map(
                session = sessionManager.state.value,
                unitSystem = UnitSystem.METRIC
            )
            val initialNotification = NavigationNotificationFactory.buildNotification(
                context = this,
                payload = initialPayload,
                requestPromotedOngoing = !promotionState.isDemoted()
            )
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
            val startedForeground = runCatching {
                ServiceCompat.startForeground(
                    this,
                    NavigationNotificationFactory.NOTIFICATION_ID,
                    initialNotification,
                    serviceType
                )
                true
            }.getOrDefault(false)
            if (!startedForeground) {
                stopSelf()
                return START_NOT_STICKY
            }
            isForegroundPromoted = true
            throttler.reset()
        }

        ensureStateObserver()
        return START_NOT_STICKY
    }

    private fun ensureStateObserver() {
        if (observerJob?.isActive == true) return
        observerJob = lifecycleScope.launch {
            combine(
                sessionManager.state,
                settingsStore.settings,
                promotionState.demoted
            ) { session, settings, demoted ->
                Triple(session, settings.unitSystem, demoted)
            }.collect { (session, unitSystem, demoted) ->
                when (session.status) {
                    NavigationStatus.ACTIVE,
                    NavigationStatus.ARRIVED_STOP -> {
                        sawNonIdleState = true
                        val payload = NavigationNotificationContentMapper.map(session, unitSystem)
                        if (throttler.shouldPost(payload, SystemClock.elapsedRealtime())) {
                            NavigationNotificationFactory.postOrUpdateNotification(
                                context = this@NavigationForegroundService,
                                payload = payload,
                                requestPromotedOngoing = !demoted
                            )
                        }
                    }

                    NavigationStatus.ARRIVED_DESTINATION -> {
                        sawNonIdleState = true
                        val arrivalPayload = NavigationNotificationContentMapper.map(session, unitSystem)
                        // Release foreground location lock cleanly, leaving a dismissible arrival summary.
                        ServiceCompat.stopForeground(
                            this@NavigationForegroundService,
                            ServiceCompat.STOP_FOREGROUND_DETACH
                        )
                        isForegroundPromoted = false
                        NavigationNotificationFactory.postOrUpdateNotification(
                            context = this@NavigationForegroundService,
                            payload = arrivalPayload,
                            requestPromotedOngoing = false
                        )
                        stopSelf()
                    }

                    NavigationStatus.IDLE -> {
                        if (sawNonIdleState) {
                            stopForegroundAndRemove()
                            stopSelf()
                        }
                    }
                }
            }
        }
    }

    private fun stopForegroundAndRemove() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NavigationNotificationFactory.NOTIFICATION_ID)
        isForegroundPromoted = false
    }

    override fun onDestroy() {
        observerJob?.cancel()
        observerJob = null
        throttler.reset()
        isForegroundPromoted = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_OR_SYNC = "com.mbk.halatuju.action.START_OR_SYNC_NAVIGATION"
    }
}
