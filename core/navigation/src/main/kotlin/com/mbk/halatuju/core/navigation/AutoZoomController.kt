package com.mbk.halatuju.core.navigation

import kotlin.math.abs
import kotlin.math.exp

/**
 * Speed-adaptive and maneuver-approaching camera zoom controller with hysteresis
 * and slew-rate limiting so map zoom never "breathes" around speed thresholds.
 *
 * - City streets (`<= 35 km/h`): `~17.5`
 * - Highways (`>= 80 km/h`): `~14.6`
 * - Approaching maneuver (`<= 300 m`): zooms in cleanly toward `~17.8`, then restores once past (`> 380 m`).
 */
class AutoZoomController(
    private val cityZoom: Double = DEFAULT_CITY_ZOOM,
    private val highwayZoom: Double = DEFAULT_HIGHWAY_ZOOM,
    private val maneuverZoom: Double = DEFAULT_MANEUVER_ZOOM
) {
    private var latchedSpeedKmh: Double? = null
    private var maneuverZoomActive = false
    private var currentZoom = DEFAULT_CITY_ZOOM - 0.6 // start comfortably around 16.9
    private var lastUpdateMs = 0L

    val targetZoom: Double
        get() = currentZoom

    val isManeuverZoomInActive: Boolean
        get() = maneuverZoomActive

    fun reset(initialZoom: Double = 16.8) {
        latchedSpeedKmh = null
        maneuverZoomActive = false
        currentZoom = initialZoom
        lastUpdateMs = 0L
    }

    fun update(
        speedMps: Float,
        distanceToNextManeuverMeters: Double,
        hasUpcomingManeuver: Boolean,
        timestampMs: Long
    ): Double {
        val instantKmh = (speedMps.coerceAtLeast(0f) * 3.6).coerceIn(0.0, 200.0)
        val dtSec = if (lastUpdateMs == 0L) 1.0 else ((timestampMs - lastUpdateMs).coerceIn(50L, 5_000L)) / 1000.0
        lastUpdateMs = timestampMs

        // 1. Speed hysteresis deadband + exponential damping
        val currentLatched = latchedSpeedKmh
        val filteredKmh = if (currentLatched == null) {
            instantKmh.also { latchedSpeedKmh = it }
        } else if (abs(instantKmh - currentLatched) >= SPEED_DEADBAND_KMH) {
            val alpha = 1.0 - exp(-dtSec / SPEED_TAU_SECONDS)
            (currentLatched + alpha * (instantKmh - currentLatched)).also { latchedSpeedKmh = it }
        } else {
            currentLatched
        }

        // Smoothstep interpolation between city speed (35 km/h) and highway speed (80 km/h)
        val tSpeed = ((filteredKmh - CITY_SPEED_KMH) / (HIGHWAY_SPEED_KMH - CITY_SPEED_KMH)).coerceIn(0.0, 1.0)
        val smoothSpeed = tSpeed * tSpeed * (3.0 - 2.0 * tSpeed)
        val baseSpeedZoom = cityZoom + (highwayZoom - cityZoom) * smoothSpeed

        // 2. Maneuver approach zoom-in hysteresis (enter <= 300 m, hold until > 380 m)
        if (!maneuverZoomActive) {
            if (hasUpcomingManeuver && distanceToNextManeuverMeters in 1.0..MANEUVER_ENTER_METERS) {
                maneuverZoomActive = true
            }
        } else {
            if (!hasUpcomingManeuver || distanceToNextManeuverMeters > MANEUVER_EXIT_METERS) {
                maneuverZoomActive = false
            }
        }

        val desiredZoom = if (maneuverZoomActive) {
            val rampT = ((MANEUVER_EXIT_METERS - distanceToNextManeuverMeters) / (MANEUVER_EXIT_METERS - 110.0))
                .coerceIn(0.0, 1.0)
            val smoothRamp = rampT * rampT * (3.0 - 2.0 * rampT)
            val boosted = baseSpeedZoom + (maneuverZoom - baseSpeedZoom) * smoothRamp
            maxOf(baseSpeedZoom, boosted)
        } else {
            baseSpeedZoom
        }

        // 3. Output deadband & slew rate limit prevent micro-oscillation / breathing
        val diff = desiredZoom - currentZoom
        if (abs(diff) < OUTPUT_ZOOM_DEADBAND) {
            return currentZoom
        }

        val maxDelta = MAX_ZOOM_SLEW_PER_SEC * dtSec
        currentZoom += diff.coerceIn(-maxDelta, maxDelta)
        return currentZoom
    }

    companion object {
        const val DEFAULT_CITY_ZOOM = 17.5
        const val DEFAULT_HIGHWAY_ZOOM = 14.6
        const val DEFAULT_MANEUVER_ZOOM = 17.8

        private const val CITY_SPEED_KMH = 35.0
        private const val HIGHWAY_SPEED_KMH = 80.0
        private const val SPEED_DEADBAND_KMH = 4.0
        private const val SPEED_TAU_SECONDS = 3.0
        private const val MANEUVER_ENTER_METERS = 300.0
        private const val MANEUVER_EXIT_METERS = 380.0
        private const val OUTPUT_ZOOM_DEADBAND = 0.08
        private const val MAX_ZOOM_SLEW_PER_SEC = 0.70
    }
}
