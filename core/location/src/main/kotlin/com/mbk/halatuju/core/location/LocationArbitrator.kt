package com.mbk.halatuju.core.location

import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.LocationProviderKind
import kotlin.math.abs

/**
 * Arbitrates dual Android LocationManager providers (GPS + NETWORK) and validates incoming fixes.
 */
class LocationArbitrator(
    private val gpsFreshnessHoldMs: Long = 4_500L
) {
    private var lastAcceptedGps: LocationFix? = null

    fun evaluate(candidate: LocationFix): LocationFix? {
        if (!isValidCoordinate(candidate.position)) return null

        val normalized = candidate.copy(
            accuracyMeters = candidate.accuracyMeters.coerceIn(1f, 5_000f),
            speedMps = candidate.speedMps?.takeIf { it.isFinite() && it >= 0f },
            bearingDegrees = candidate.bearingDegrees?.takeIf { it.isFinite() }
                ?.let { ((it % 360f) + 360f) % 360f }
        )

        val previousGps = lastAcceptedGps
        return when (normalized.provider) {
            LocationProviderKind.GPS -> {
                lastAcceptedGps = normalized
                normalized
            }

            LocationProviderKind.NETWORK -> {
                if (previousGps != null) {
                    val ageMs = normalized.timestampMs - previousGps.timestampMs
                    val gpsIsFresh = ageMs in 0..gpsFreshnessHoldMs
                    val networkFarWorse = normalized.accuracyMeters > previousGps.accuracyMeters * 1.5f
                    if (gpsIsFresh || (ageMs <= gpsFreshnessHoldMs * 2 && networkFarWorse)) {
                        return null
                    }
                }
                normalized
            }

            else -> normalized
        }
    }

    fun evaluateRaw(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float = 35f,
        speedMps: Float? = null,
        bearingDegrees: Float? = null,
        timestampMs: Long = System.currentTimeMillis(),
        elapsedRealtimeNanos: Long = 0L,
        provider: LocationProviderKind = LocationProviderKind.GPS
    ): LocationFix? {
        val fix = toValidLocationFix(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            speedMps = speedMps,
            bearingDegrees = bearingDegrees,
            timestampMs = timestampMs,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            provider = provider
        ) ?: return null
        return evaluate(fix)
    }

    fun reset() {
        lastAcceptedGps = null
    }

    companion object {
        fun isValidLatLon(latitude: Double, longitude: Double): Boolean {
            if (!latitude.isFinite() || !longitude.isFinite()) return false
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
            // Guard against common uninitialized (0, 0) null-island fixes
            if (abs(latitude) < 1e-6 && abs(longitude) < 1e-6) return false
            return true
        }

        fun isValidCoordinate(pos: LatLng): Boolean = isValidLatLon(pos.latitude, pos.longitude)

        fun toValidLocationFix(
            latitude: Double,
            longitude: Double,
            accuracyMeters: Float = 35f,
            speedMps: Float? = null,
            bearingDegrees: Float? = null,
            timestampMs: Long = System.currentTimeMillis(),
            elapsedRealtimeNanos: Long = 0L,
            provider: LocationProviderKind = LocationProviderKind.GPS
        ): LocationFix? {
            if (!isValidLatLon(latitude, longitude)) return null
            val safeAccuracy = if (accuracyMeters.isFinite()) accuracyMeters.coerceIn(1f, 5_000f) else 35f
            return LocationFix(
                position = LatLng(latitude, longitude),
                accuracyMeters = safeAccuracy,
                speedMps = speedMps?.takeIf { it.isFinite() && it >= 0f },
                bearingDegrees = bearingDegrees?.takeIf { it.isFinite() },
                timestampMs = timestampMs,
                elapsedRealtimeNanos = elapsedRealtimeNanos,
                provider = provider
            )
        }
    }
}
