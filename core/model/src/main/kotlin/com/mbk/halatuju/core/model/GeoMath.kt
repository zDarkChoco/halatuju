package com.mbk.halatuju.core.model

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared spherical & local tangent-plane geometry helpers used across routing, navigation, and map modules.
 */
object GeoMath {
    const val EARTH_RADIUS_METERS = 6_371_000.0

    fun haversineMeters(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val sinLat = sin(dLat / 2.0)
        val sinLon = sin(dLon / 2.0)
        val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
        return 2.0 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt((1.0 - h).coerceAtLeast(0.0)))
    }

    fun initialBearingDegrees(from: LatLng, to: LatLng): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x))
        return normalizeDegrees(deg)
    }

    fun normalizeDegrees(deg: Double): Double {
        var r = deg % 360.0
        if (r < 0.0) r += 360.0
        return r
    }

    fun angularDifferenceDegrees(a: Double, b: Double): Double {
        val diff = ((b - a + 540.0) % 360.0) - 180.0
        return kotlin.math.abs(diff)
    }

    /**
     * Shortest signed delta from [fromDeg] to [toDeg] in [-180, 180).
     */
    fun signedAngleDeltaDegrees(fromDeg: Double, toDeg: Double): Double {
        return ((toDeg - fromDeg + 540.0) % 360.0) - 180.0
    }

    /**
     * Converts [point] to local East-North meters relative to [anchor].
     */
    fun toLocalEnMeters(anchor: LatLng, point: LatLng): Pair<Double, Double> {
        val dLat = Math.toRadians(point.latitude - anchor.latitude)
        val dLon = Math.toRadians(point.longitude - anchor.longitude)
        val cosLat = cos(Math.toRadians(anchor.latitude))
        val east = EARTH_RADIUS_METERS * dLon * cosLat
        val north = EARTH_RADIUS_METERS * dLat
        return east to north
    }

    /**
     * Converts local East-North meters relative to [anchor] back to WGS84 [LatLng].
     */
    fun fromLocalEnMeters(anchor: LatLng, eastMeters: Double, northMeters: Double): LatLng {
        val cosLat = cos(Math.toRadians(anchor.latitude)).coerceAtLeast(1e-6)
        val dLat = northMeters / EARTH_RADIUS_METERS
        val dLon = eastMeters / (EARTH_RADIUS_METERS * cosLat)
        val lat = (anchor.latitude + Math.toDegrees(dLat)).coerceIn(-90.0, 90.0)
        var lon = anchor.longitude + Math.toDegrees(dLon)
        while (lon > 180.0) lon -= 360.0
        while (lon < -180.0) lon += 360.0
        return LatLng(lat, lon)
    }

    fun interpolateLatLng(a: LatLng, b: LatLng, fraction: Double): LatLng {
        val t = fraction.coerceIn(0.0, 1.0)
        return LatLng(
            latitude = a.latitude + (b.latitude - a.latitude) * t,
            longitude = a.longitude + (b.longitude - a.longitude) * t
        )
    }
}
