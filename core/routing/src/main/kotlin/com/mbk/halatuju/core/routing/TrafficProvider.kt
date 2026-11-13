package com.mbk.halatuju.core.routing

import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.NavRoute
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos

@Serializable
data class TrafficEstimate(
    val adjustedDurationSeconds: Double,
    val congestionFactor: Double,
    val isEstimated: Boolean,
    val label: String
)

/**
 * Abstraction for traffic estimation & live in-trip ETA adaptation.
 * Swap cleanly for open floating-car feeds, GTFS-RT bus probes, or self-hosted Valhalla `traffic.tar`.
 */
interface TrafficProvider {
    suspend fun estimateRouteEta(
        route: NavRoute,
        departureTimeEpochMs: Long
    ): TrafficEstimate

    fun adaptRemainingEta(
        route: NavRoute,
        distanceAlongRouteMeters: Double,
        baseRemainingSeconds: Double,
        traveledObservedSeconds: Double,
        traveledExpectedSeconds: Double,
        departureTimeEpochMs: Long
    ): TrafficEstimate
}

data class PeakWindow(
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val peakMultiplier: Double,
    val description: String
)

data class KlTrafficConfig(
    val klPeakModelingEnabled: Boolean = true,
    val rampMinutes: Double = 20.0,
    val weekdayBaselineFactor: Double = 1.03,
    val windows: List<PeakWindow> = listOf(
        PeakWindow(7 * 60, 9 * 60 + 30, 1.32, "morning peak traffic"),
        PeakWindow(12 * 60, 14 * 60, 1.10, "midday buildup"),
        PeakWindow(17 * 60, 20 * 60, 1.38, "evening peak traffic")
    )
)

/**
 * Configurable time-of-day congestion prior tailored for Kuala Lumpur / Klang Valley weekday peaks,
 * plus damped live pace adaptation during active navigation.
 */
@Singleton
class KlPeakTrafficProvider internal constructor(
    private val config: KlTrafficConfig,
    private val klZoneId: ZoneId
) : TrafficProvider {

    @Inject
    constructor() : this(
        config = KlTrafficConfig(),
        klZoneId = ZoneId.of("Asia/Kuala_Lumpur")
    )

    override suspend fun estimateRouteEta(
        route: NavRoute,
        departureTimeEpochMs: Long
    ): TrafficEstimate {
        val factorAndReason = evaluatePriorFactor(route, departureTimeEpochMs)
        val factor = factorAndReason.first
        val peakReason = factorAndReason.second
        val adjusted = (route.baseDurationSeconds * factor).coerceAtLeast(0.0)
        val label = if (peakReason != null) {
            "estimated · $peakReason"
        } else {
            "estimated"
        }
        return TrafficEstimate(
            adjustedDurationSeconds = adjusted,
            congestionFactor = factor,
            isEstimated = true,
            label = label
        )
    }

    override fun adaptRemainingEta(
        route: NavRoute,
        distanceAlongRouteMeters: Double,
        baseRemainingSeconds: Double,
        traveledObservedSeconds: Double,
        traveledExpectedSeconds: Double,
        departureTimeEpochMs: Long
    ): TrafficEstimate {
        val (priorFactor, peakReason) = evaluatePriorFactor(route, departureTimeEpochMs)
        if (distanceAlongRouteMeters < 600.0 || traveledObservedSeconds < 30.0) {
            return TrafficEstimate(
                adjustedDurationSeconds = (baseRemainingSeconds * priorFactor).coerceAtLeast(0.0),
                congestionFactor = priorFactor,
                isEstimated = true,
                label = if (peakReason != null) "estimated · $peakReason" else "estimated"
            )
        }
        val wObs = (distanceAlongRouteMeters / 4_000.0).coerceIn(0.0, 0.65)
        val paceRatio = (traveledObservedSeconds / traveledExpectedSeconds.coerceAtLeast(15.0))
            .coerceIn(0.78, 1.75)
        val blendedFactor = (1.0 - wObs) * priorFactor + wObs * paceRatio
        val adaptedRemaining = (baseRemainingSeconds * blendedFactor).coerceAtLeast(0.0)
        return TrafficEstimate(
            adjustedDurationSeconds = adaptedRemaining,
            congestionFactor = blendedFactor,
            isEstimated = true,
            label = if (peakReason != null) "estimated · live pace ($peakReason)" else "estimated · live pace"
        )
    }

    internal fun evaluatePriorFactor(
        route: NavRoute,
        departureTimeEpochMs: Long
    ): Pair<Double, String?> {
        if (!config.klPeakModelingEnabled) return 1.0 to null
        val inKlangValley = route.geometry.firstOrNull()?.let { isKlangValleyCorridor(it) } == true ||
            route.waypoints.any { isKlangValleyCorridor(it.location) }

        if (!inKlangValley) {
            return 1.0 to null
        }

        val zoned = Instant.ofEpochMilli(departureTimeEpochMs).atZone(klZoneId)
        val day = zoned.dayOfWeek
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return 1.02 to null
        }

        val minuteOfDay = zoned.hour * 60.0 + zoned.minute + zoned.second / 60.0
        var bestFactor = config.weekdayBaselineFactor
        var activeReason: String? = null

        for (window in config.windows) {
            val weight = smoothWindowWeight(
                minute = minuteOfDay,
                start = window.startMinuteOfDay.toDouble(),
                end = window.endMinuteOfDay.toDouble(),
                ramp = config.rampMinutes
            )
            if (weight > 0.0) {
                val factor = config.weekdayBaselineFactor +
                    (window.peakMultiplier - config.weekdayBaselineFactor) * weight
                if (factor > bestFactor) {
                    bestFactor = factor
                    activeReason = if (weight >= 0.35 && window.peakMultiplier >= 1.15) {
                        window.description
                    } else {
                        null
                    }
                }
            }
        }
        return bestFactor to activeReason
    }

    private fun smoothWindowWeight(
        minute: Double,
        start: Double,
        end: Double,
        ramp: Double
    ): Double {
        if (ramp <= 0.0) {
            return if (minute in start..end) 1.0 else 0.0
        }
        return when {
            minute < start - ramp || minute > end + ramp -> 0.0
            minute in start..end -> 1.0
            minute < start -> {
                val t = ((minute - (start - ramp)) / ramp).coerceIn(0.0, 1.0)
                0.5 * (1.0 - cos(PI * t))
            }
            else -> {
                val t = (((end + ramp) - minute) / ramp).coerceIn(0.0, 1.0)
                0.5 * (1.0 - cos(PI * t))
            }
        }
    }

    private fun isKlangValleyCorridor(point: LatLng): Boolean {
        return point.latitude in 2.45..3.50 && point.longitude in 101.20..102.00
    }
}
