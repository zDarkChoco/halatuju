package com.mbk.halatuju.core.navigation

import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.NavRoute

data class DeadReckonOutput(
    val position: LatLng,
    val bearingDegrees: Double,
    val distanceAlongRouteMeters: Double,
    val speedMps: Float,
    val isDeadReckoning: Boolean
)

/**
 * Tunnel & GPS outage extrapolator.
 * When live GPS updates pause beyond [gpsLossThresholdMs], continues advancing smoothly along
 * the active route geometry at the last known on-route speed, decaying gently after sustained
 * outages so an undetected long stall never overshoots an exit.
 */
class DeadReckoner(
    private val gpsLossThresholdMs: Long = DEFAULT_GPS_LOSS_THRESHOLD_MS,
    private val minExtrapolateSpeedMps: Float = 2.0f,
    private val maxExtrapolateSpeedMps: Float = 36.0f
) {
    private var lastValidFixTimeMs: Long = 0L
    private var lastTickTimeMs: Long = 0L
    private var lastKnownSpeedMps: Float = 0f
    private var lastOnRoute: Boolean = false
    private var deadReckonDistanceMeters: Double = 0.0

    fun reset() {
        lastValidFixTimeMs = 0L
        lastTickTimeMs = 0L
        lastKnownSpeedMps = 0f
        lastOnRoute = false
        deadReckonDistanceMeters = 0.0
    }

    fun recordValidFix(
        timestampMs: Long,
        speedMps: Float,
        onRoute: Boolean,
        distanceAlongRouteMeters: Double
    ) {
        lastValidFixTimeMs = timestampMs
        lastTickTimeMs = timestampMs
        lastKnownSpeedMps = speedMps.coerceIn(0f, maxExtrapolateSpeedMps)
        lastOnRoute = onRoute
        deadReckonDistanceMeters = distanceAlongRouteMeters
    }

    fun tick(
        route: NavRoute,
        nowMs: Long,
        maxDistanceLimitMeters: Double
    ): DeadReckonOutput? {
        if (lastValidFixTimeMs <= 0L) return null
        val outageMs = (nowMs - lastValidFixTimeMs).coerceAtLeast(0L)
        if (outageMs < gpsLossThresholdMs) return null

        val dtSec = ((nowMs - lastTickTimeMs).coerceIn(0L, 1_500L)) / 1000.0
        lastTickTimeMs = nowMs

        if (!lastOnRoute || lastKnownSpeedMps < minExtrapolateSpeedMps) {
            val sample = RoutePointSampler.sampleAtDistance(route, deadReckonDistanceMeters)
            return DeadReckonOutput(
                position = sample.position,
                bearingDegrees = sample.bearingDegrees,
                distanceAlongRouteMeters = sample.distanceMeters,
                speedMps = lastKnownSpeedMps,
                isDeadReckoning = true
            )
        }

        // Maintain constant speed for first 25s (e.g., SMART Tunnel / expressway underpass), then decay linearly to 0 by 60s
        val decay = when {
            outageMs <= 25_000L -> 1.0
            outageMs >= 60_000L -> 0.0
            else -> 1.0 - (outageMs - 25_000L) / 35_000.0
        }
        val effectiveSpeed = (lastKnownSpeedMps * decay).toFloat()
        deadReckonDistanceMeters = (deadReckonDistanceMeters + effectiveSpeed * dtSec)
            .coerceIn(0.0, maxDistanceLimitMeters.coerceAtMost(route.totalDistanceMeters))

        val sample = RoutePointSampler.sampleAtDistance(route, deadReckonDistanceMeters)
        return DeadReckonOutput(
            position = sample.position,
            bearingDegrees = sample.bearingDegrees,
            distanceAlongRouteMeters = sample.distanceMeters,
            speedMps = effectiveSpeed,
            isDeadReckoning = true
        )
    }

    companion object {
        const val DEFAULT_GPS_LOSS_THRESHOLD_MS = 2_500L
    }
}
