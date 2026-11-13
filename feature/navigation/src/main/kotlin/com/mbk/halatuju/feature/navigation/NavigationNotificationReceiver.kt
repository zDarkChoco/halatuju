package com.mbk.halatuju.feature.navigation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mbk.halatuju.core.navigation.NavigationForegroundController
import com.mbk.halatuju.core.navigation.NavigationSessionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Handles notification actions (End trip) and Live Update dismissal callbacks without
 * requiring an Activity launch trampoline.
 */
@AndroidEntryPoint
class NavigationNotificationReceiver : BroadcastReceiver() {

    @Inject
    lateinit var sessionManager: NavigationSessionManager

    @Inject
    lateinit var foregroundController: NavigationForegroundController

    @Inject
    lateinit var promotionState: NavigationNotificationPromotionState

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            NavigationNotificationFactory.ACTION_END_NAVIGATION -> {
                sessionManager.endNavigation()
                foregroundController.onNavigationStopped()
            }

            NavigationNotificationFactory.ACTION_DISMISSED_PROMOTED -> {
                // Respect user dismissal/unpinning of the Live Update so we do not repeatedly
                // force re-promotion during the remainder of the active trip.
                promotionState.markDemoted()
            }
        }
    }
}
