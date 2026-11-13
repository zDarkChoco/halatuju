package com.mbk.halatuju.core.navigation

import com.mbk.halatuju.core.model.GeoMath

/**
 * Circular angular smoother enforcing the ~2 m/s speed floor before trusting heading measurements.
 *
 * Below `minSpeedMps`, the last good bearing is held unchanged so stationary traffic-light jitter
 * never spins the course-up navigation camera. Angles wrap seamlessly across 359° -> 0°.
 */
class BearingSmoother(
    private val minSpeedMps: Float = DEFAULT_MIN_SPEED_MPS,
    private val lowSpeedAlpha: Double = 0.35,
    private val highSpeedAlpha: Double = 0.55
) {
    private var currentBearingDegrees: Double = 0.0
    private var initialized: Boolean = false

    val hasReliableBearing: Boolean
        get() = initialized

    val bearingDegrees: Double
        get() = currentBearingDegrees

    fun reset(initialBearingDegrees: Double? = null) {
        if (initialBearingDegrees != null && initialBearingDegrees.isFinite()) {
            currentBearingDegrees = GeoMath.normalizeDegrees(initialBearingDegrees)
            initialized = true
        } else {
            currentBearingDegrees = 0.0
            initialized = false
        }
    }

    fun update(
        speedMps: Float?,
        rawBearingDegrees: Float?
    ): Pair<Double, Boolean> {
        val validRaw = rawBearingDegrees?.takeIf { it.isFinite() }?.let { GeoMath.normalizeDegrees(it.toDouble()) }
        val effectiveSpeed = speedMps?.takeIf { it.isFinite() && it >= 0f } ?: 0f

        if (!initialized) {
            return if (validRaw != null && effectiveSpeed >= minSpeedMps) {
                currentBearingDegrees = validRaw
                initialized = true
                currentBearingDegrees to true
            } else {
                // Hold until we observe a reliable moving heading (or seed gently from initial fix if needed)
                currentBearingDegrees to false
            }
        }

        // Hold last good bearing strictly below minimum moving speed (~2 m/s = 7.2 km/h)
        if (effectiveSpeed < minSpeedMps || validRaw == null) {
            return currentBearingDegrees to true
        }

        val alpha = if (effectiveSpeed >= 12.0f) highSpeedAlpha else lowSpeedAlpha
        val delta = GeoMath.signedAngleDeltaDegrees(currentBearingDegrees, validRaw)
        currentBearingDegrees = GeoMath.normalizeDegrees(currentBearingDegrees + alpha * delta)
        return currentBearingDegrees to true
    }

    companion object {
        const val DEFAULT_MIN_SPEED_MPS = 2.0f
    }
}
