package com.mbk.halatuju.core.navigation

import com.mbk.halatuju.core.model.UnitSystem

/**
 * Abstraction bridging [NavigationSessionManager] with the platform foreground service
 * without introducing a reverse module dependency from :core:navigation to :feature:navigation.
 */
interface NavigationForegroundController {
    fun onNavigationStarted()
    fun onNavigationStateChanged(state: NavigationSessionState, unitSystem: UnitSystem) = Unit
    fun onNavigationStopped()
}

object NoOpNavigationForegroundController : NavigationForegroundController {
    override fun onNavigationStarted() = Unit
    override fun onNavigationStopped() = Unit
}
