package com.mbk.halatuju.feature.navigation

/**
 * Throttles foreground service notification IPC updates while ensuring instant refresh
 * when maneuver step, reroute state, signal state, or arrival status changes.
 */
class NavigationNotificationThrottler(
    private val farIntervalMs: Long = 2_000L,
    private val nearManeuverIntervalMs: Long = 1_000L,
    private val nearManeuverThresholdMeters: Double = 250.0
) {
    private var lastPostedTimeMs: Long = Long.MIN_VALUE
    private var lastPayload: NavigationNotificationPayload? = null

    fun shouldPost(
        payload: NavigationNotificationPayload,
        nowMs: Long
    ): Boolean {
        val prev = lastPayload ?: return recordAndAllow(payload, nowMs)

        val urgentStateChange =
            prev.isOngoing != payload.isOngoing ||
                prev.isArrivalCompleted != payload.isArrivalCompleted ||
                prev.stepIndex != payload.stepIndex ||
                prev.legIndex != payload.legIndex ||
                prev.icon != payload.icon ||
                prev.isRerouting != payload.isRerouting ||
                prev.isGpsSignalWeak != payload.isGpsSignalWeak

        if (urgentStateChange) {
            return recordAndAllow(payload, nowMs)
        }

        // Crossed a critical distance threshold approaching turn
        val crossedThreshold =
            (prev.distanceToManeuverMeters > 300.0 && payload.distanceToManeuverMeters <= 300.0) ||
                (prev.distanceToManeuverMeters > 100.0 && payload.distanceToManeuverMeters <= 100.0) ||
                (prev.distanceToManeuverMeters > 40.0 && payload.distanceToManeuverMeters <= 40.0)
        if (crossedThreshold) {
            return recordAndAllow(payload, nowMs)
        }

        // Deduplicate if nothing visible actually changed (0.5% progress granularity)
        val sameVisibleText =
            prev.title == payload.title &&
                prev.text == payload.text &&
                prev.shortCriticalText == payload.shortCriticalText &&
                (prev.progressPermille / 5) == (payload.progressPermille / 5)
        if (sameVisibleText) {
            return false
        }

        val interval = if (payload.distanceToManeuverMeters <= nearManeuverThresholdMeters) {
            nearManeuverIntervalMs
        } else {
            farIntervalMs
        }

        return if (nowMs - lastPostedTimeMs >= interval) {
            recordAndAllow(payload, nowMs)
        } else {
            false
        }
    }

    fun reset() {
        lastPostedTimeMs = Long.MIN_VALUE
        lastPayload = null
    }

    private fun recordAndAllow(payload: NavigationNotificationPayload, nowMs: Long): Boolean {
        lastPostedTimeMs = nowMs
        lastPayload = payload
        return true
    }
}
