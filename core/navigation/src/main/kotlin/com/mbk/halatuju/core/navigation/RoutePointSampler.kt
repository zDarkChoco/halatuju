package com.mbk.halatuju.core.navigation

import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.NavRoute

/**
 * Sampled point along a [NavRoute] at a given cumulative distance.
 */
data class RouteSample(
    val position: LatLng,
    val bearingDegrees: Double,
    val segmentIndex: Int,
    val distanceMeters: Double
)

object RoutePointSampler {

    fun sampleAtDistance(route: NavRoute, distanceMeters: Double): RouteSample {
        val pts = route.geometry
        val cum = route.cumulativeDistancesMeters
        if (pts.isEmpty()) {
            return RouteSample(LatLng(0.0, 0.0), 0.0, 0, 0.0)
        }
        if (pts.size == 1 || cum.size != pts.size) {
            return RouteSample(pts.first(), 0.0, 0, 0.0)
        }

        val maxDist = cum.last().coerceAtLeast(0.0)
        val clamped = distanceMeters.coerceIn(0.0, maxDist)
        if (clamped <= 0.0) {
            val brg = safeSegmentBearing(pts[0], pts[1])
            return RouteSample(pts[0], brg, 0, 0.0)
        }
        if (clamped >= maxDist) {
            val idx = (pts.size - 2).coerceAtLeast(0)
            val brg = safeSegmentBearing(pts[idx], pts[idx + 1])
            return RouteSample(pts.last(), brg, idx, maxDist)
        }

        val segIdx = findSegmentIndex(cum, clamped)
        val startD = cum[segIdx]
        val endD = cum[segIdx + 1]
        val len = (endD - startD).coerceAtLeast(1e-6)
        val t = ((clamped - startD) / len).coerceIn(0.0, 1.0)
        val pos = GeoMath.interpolateLatLng(pts[segIdx], pts[segIdx + 1], t)
        val brg = safeSegmentBearing(pts[segIdx], pts[segIdx + 1])
        return RouteSample(pos, brg, segIdx, clamped)
    }

    fun findSegmentIndex(cum: List<Double>, distanceMeters: Double): Int {
        var lo = 0
        var hi = (cum.size - 2).coerceAtLeast(0)
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cum[mid + 1] < distanceMeters) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        return lo
    }

    fun safeSegmentBearing(a: LatLng, b: LatLng): Double {
        return if (GeoMath.haversineMeters(a, b) < 0.05) 0.0 else GeoMath.initialBearingDegrees(a, b)
    }
}
