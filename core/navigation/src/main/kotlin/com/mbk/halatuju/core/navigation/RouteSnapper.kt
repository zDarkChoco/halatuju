package com.mbk.halatuju.core.navigation

import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.NavRoute
import kotlin.math.hypot

data class SnapResult(
    val snappedPosition: LatLng,
    val snappedBearingDegrees: Double,
    val distanceAlongRouteMeters: Double,
    val segmentIndex: Int,
    val crossTrackErrorMeters: Double,
    val headingDeltaDegrees: Double,
    val isSnapped: Boolean
)

/**
 * Forward-monotonic map matcher projecting the filtered position onto the active [NavRoute].
 *
 * Enforces both:
 * 1) Corridor gate: cross-track error <= max(25 m, 2 * accuracyMeters)
 * 2) Heading gate: |heading - segmentBearing| <= 45 deg (prevents snapping onto opposite carriageways
 *    or perpendicular overpasses)
 * 3) Forward progress window (never snaps backward onto earlier segments or loops)
 */
class RouteSnapper(
    private val baseCorridorMeters: Double = DEFAULT_BASE_CORRIDOR_METERS,
    private val maxHeadingDeltaDegrees: Double = DEFAULT_MAX_HEADING_DELTA_DEGREES,
    private val primaryLookaheadMeters: Double = DEFAULT_PRIMARY_LOOKAHEAD_METERS
) {
    fun snap(
        route: NavRoute,
        position: LatLng,
        headingDegrees: Double,
        hasReliableBearing: Boolean,
        accuracyMeters: Float,
        minProgressMeters: Double,
        maxSearchDistanceMeters: Double? = null
    ): SnapResult {
        val pts = route.geometry
        val cum = route.cumulativeDistancesMeters
        if (pts.size < 2 || cum.size != pts.size) {
            return SnapResult(
                snappedPosition = position,
                snappedBearingDegrees = headingDegrees,
                distanceAlongRouteMeters = minProgressMeters,
                segmentIndex = 0,
                crossTrackErrorMeters = Double.MAX_VALUE,
                headingDeltaDegrees = 0.0,
                isSnapped = false
            )
        }

        val corridorRadius = maxOf(
            baseCorridorMeters,
            2.0 * accuracyMeters.let { if (it.isFinite() && it > 0f) it.toDouble() else 12.0 }
        )

        val legCeiling = (maxSearchDistanceMeters ?: route.totalDistanceMeters)
            .coerceIn(minProgressMeters, route.totalDistanceMeters)

        // Primary forward search window around current progress avoids accidental leaps onto overpass overlaps
        val primaryCandidate = findBestCandidate(
            pts = pts,
            cum = cum,
            position = position,
            headingDegrees = headingDegrees,
            hasReliableBearing = hasReliableBearing,
            corridorRadius = corridorRadius,
            windowStartDist = (minProgressMeters - SMALL_STATIONARY_TOLERANCE_M).coerceAtLeast(0.0),
            windowEndDist = (minProgressMeters + primaryLookaheadMeters).coerceAtMost(legCeiling),
            minProgressMeters = minProgressMeters
        )

        val chosen = primaryCandidate ?: findBestCandidate(
            pts = pts,
            cum = cum,
            position = position,
            headingDegrees = headingDegrees,
            hasReliableBearing = hasReliableBearing,
            corridorRadius = corridorRadius,
            windowStartDist = minProgressMeters,
            windowEndDist = legCeiling,
            minProgressMeters = minProgressMeters
        )

        if (chosen != null) {
            return chosen
        }

        // Compute nearest local distance strictly for debug telemetry when off-route
        val nearestTelemetry = findBestCandidate(
            pts = pts,
            cum = cum,
            position = position,
            headingDegrees = headingDegrees,
            hasReliableBearing = false,
            corridorRadius = Double.MAX_VALUE,
            windowStartDist = (minProgressMeters - 20.0).coerceAtLeast(0.0),
            windowEndDist = legCeiling,
            minProgressMeters = minProgressMeters
        )

        val xte = nearestTelemetry?.crossTrackErrorMeters
            ?: GeoMath.haversineMeters(position, RoutePointSampler.sampleAtDistance(route, minProgressMeters).position)
        val rawSegBrg = nearestTelemetry?.snappedBearingDegrees ?: headingDegrees
        val headingDiff = if (hasReliableBearing) {
            GeoMath.angularDifferenceDegrees(headingDegrees, rawSegBrg)
        } else {
            0.0
        }

        return SnapResult(
            snappedPosition = position,
            snappedBearingDegrees = headingDegrees,
            distanceAlongRouteMeters = minProgressMeters,
            segmentIndex = nearestTelemetry?.segmentIndex ?: 0,
            crossTrackErrorMeters = xte,
            headingDeltaDegrees = headingDiff,
            isSnapped = false
        )
    }

    private fun findBestCandidate(
        pts: List<LatLng>,
        cum: List<Double>,
        position: LatLng,
        headingDegrees: Double,
        hasReliableBearing: Boolean,
        corridorRadius: Double,
        windowStartDist: Double,
        windowEndDist: Double,
        minProgressMeters: Double
    ): SnapResult? {
        var bestScore = Double.MAX_VALUE
        var bestResult: SnapResult? = null

        val startSeg = RoutePointSampler.findSegmentIndex(cum, windowStartDist)
        val endSeg = RoutePointSampler.findSegmentIndex(cum, windowEndDist)

        for (i in startSeg..endSeg) {
            if (i + 1 >= pts.size) break
            val segStart = pts[i]
            val segEnd = pts[i + 1]
            val segLenCum = (cum[i + 1] - cum[i]).coerceAtLeast(0.0)
            if (segLenCum < 0.05) continue

            val segBearing = GeoMath.initialBearingDegrees(segStart, segEnd)
            val headingDelta = if (hasReliableBearing) {
                GeoMath.angularDifferenceDegrees(headingDegrees, segBearing)
            } else {
                0.0
            }
            if (hasReliableBearing && headingDelta > maxHeadingDeltaDegrees) {
                continue
            }

            val (segE, segN) = GeoMath.toLocalEnMeters(segStart, segEnd)
            val (posE, posN) = GeoMath.toLocalEnMeters(segStart, position)
            val lenSq = segE * segE + segN * segN
            if (lenSq < 1e-4) continue

            val t = ((posE * segE + posN * segN) / lenSq).coerceIn(0.0, 1.0)
            val projE = segE * t
            val projN = segN * t
            val xte = hypot(posE - projE, posN - projN)

            if (xte > corridorRadius) continue

            val rawDistAlong = cum[i] + t * segLenCum
            // Never step noticeably backward on route progress
            if (rawDistAlong < minProgressMeters - SMALL_STATIONARY_TOLERANCE_M) continue

            val monotonicDist = maxOf(minProgressMeters, rawDistAlong)
            val projLatLng = GeoMath.interpolateLatLng(segStart, segEnd, t)

            // Prefer closest match in cross-track + slight bias against jumping far ahead along corridor
            val forwardJumpPenalty = ((monotonicDist - minProgressMeters).coerceAtLeast(0.0)) * 0.02
            val headingPenalty = headingDelta * 0.15
            val score = xte + forwardJumpPenalty + headingPenalty

            if (score < bestScore) {
                bestScore = score
                bestResult = SnapResult(
                    snappedPosition = projLatLng,
                    snappedBearingDegrees = segBearing,
                    distanceAlongRouteMeters = monotonicDist,
                    segmentIndex = i,
                    crossTrackErrorMeters = xte,
                    headingDeltaDegrees = headingDelta,
                    isSnapped = true
                )
            }
        }
        return bestResult
    }

    companion object {
        const val DEFAULT_BASE_CORRIDOR_METERS = 25.0
        const val DEFAULT_MAX_HEADING_DELTA_DEGREES = 45.0
        const val DEFAULT_PRIMARY_LOOKAHEAD_METERS = 350.0
        private const val SMALL_STATIONARY_TOLERANCE_M = 12.0
    }
}
