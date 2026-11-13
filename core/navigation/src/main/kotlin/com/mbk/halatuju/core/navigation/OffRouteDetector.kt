package com.mbk.halatuju.core.navigation

data class OffRouteStatus(
    val consecutiveOffRouteFixes: Int,
    val offRouteDurationSeconds: Double,
    val isOffRoute: Boolean,
    val shouldRequestReroute: Boolean
)

/**
 * Hysteresis gate before marking the user off-route & dispatching a Valhalla reroute request.
 * Requires BOTH `minConsecutiveFixes` outside the corridor AND `minDurationMs` elapsed off-route.
 */
class OffRouteDetector(
    private val minConsecutiveFixes: Int = DEFAULT_MIN_CONSECUTIVE_FIXES,
    private val minDurationMs: Long = DEFAULT_MIN_DURATION_MS,
    private val rerouteCooldownMs: Long = DEFAULT_REROUTE_COOLDOWN_MS
) {
    private var consecutiveCount = 0
    private var firstOffRouteTimestampMs: Long? = null
    private var lastRerouteTimestampMs: Long = 0L

    fun reset(clearCooldown: Boolean = false) {
        consecutiveCount = 0
        firstOffRouteTimestampMs = null
        if (clearCooldown) {
            lastRerouteTimestampMs = 0L
        }
    }

    fun evaluate(isSnapped: Boolean, timestampMs: Long): OffRouteStatus {
        if (isSnapped) {
            consecutiveCount = 0
            firstOffRouteTimestampMs = null
            return OffRouteStatus(
                consecutiveOffRouteFixes = 0,
                offRouteDurationSeconds = 0.0,
                isOffRoute = false,
                shouldRequestReroute = false
            )
        }

        consecutiveCount += 1
        val startMs = firstOffRouteTimestampMs ?: timestampMs.also { firstOffRouteTimestampMs = it }
        val elapsedMs = (timestampMs - startMs).coerceAtLeast(0L)
        val offRoute = consecutiveCount >= minConsecutiveFixes && elapsedMs >= minDurationMs
        val canDispatchReroute = offRoute &&
            (lastRerouteTimestampMs == 0L || (timestampMs - lastRerouteTimestampMs) >= rerouteCooldownMs)

        if (canDispatchReroute) {
            lastRerouteTimestampMs = timestampMs
        }

        return OffRouteStatus(
            consecutiveOffRouteFixes = consecutiveCount,
            offRouteDurationSeconds = elapsedMs / 1000.0,
            isOffRoute = offRoute,
            shouldRequestReroute = canDispatchReroute
        )
    }

    companion object {
        const val DEFAULT_MIN_CONSECUTIVE_FIXES = 5
        const val DEFAULT_MIN_DURATION_MS = 6_000L
        const val DEFAULT_REROUTE_COOLDOWN_MS = 8_000L
    }
}
