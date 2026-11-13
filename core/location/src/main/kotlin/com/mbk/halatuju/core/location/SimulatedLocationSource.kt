package com.mbk.halatuju.core.location

import android.os.SystemClock
import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.LocationProviderKind
import com.mbk.halatuju.core.model.NavRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.util.Random
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin

data class SimulatedRouteProgressSnapshot(
    val route: NavRoute? = null,
    val isPausedAtStop: Boolean = false,
    val isFinished: Boolean = false
)

data class SimulationRuntimeConfig(
    val enabled: Boolean = false,
    val speedMultiplier: Float = 1.0f,
    val noiseEnabled: Boolean = true
)

data class SimulatedFaultStatus(
    val isTunnelBlackoutActive: Boolean = false,
    val isOffRouteDetourActive: Boolean = false
)

/**
 * Generates realistic synthetic [LocationFix] updates along an active [NavRoute], respecting
 * per-segment road speed limits × speed multiplier (1x/2x/4x), optional ENU Gaussian GPS jitter,
 * and on-demand tunnel blackout / off-route detour fault injection so Kalman filtering, snapping,
 * off-route hysteresis, rerouting, TTS, speed badge, and Live Updates all execute on the real path.
 */
class SimulatedLocationSource(
    private val routeSnapshotProvider: () -> SimulatedRouteProgressSnapshot,
    private val configProvider: () -> SimulationRuntimeConfig,
    private val tickIntervalMs: Long = 1_000L,
    private val epochClockMs: () -> Long = { System.currentTimeMillis() },
    private val elapsedClockMs: () -> Long = {
        runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.nanoTime() / 1_000_000L }
    },
    private val faultStatusListener: (SimulatedFaultStatus) -> Unit = {},
    randomSeed: Long = 42_017L
) : LocationSource {

    override val providerKind: LocationProviderKind = LocationProviderKind.SIMULATED

    private val random = Random(randomSeed)

    @Volatile
    private var tunnelBlackoutUntilElapsedMs: Long = 0L

    @Volatile
    private var offRouteDetourUntilElapsedMs: Long = 0L

    @Volatile
    private var offRouteLateralMeters: Double = 55.0

    fun injectTunnelBlackout(durationMs: Long = 8_000L) {
        tunnelBlackoutUntilElapsedMs = elapsedClockMs() + durationMs.coerceIn(3_000L, 25_000L)
    }

    fun injectOffRouteDetour(
        durationMs: Long = 15_000L,
        lateralOffsetMeters: Double = 80.0
    ) {
        offRouteLateralMeters = lateralOffsetMeters
        offRouteDetourUntilElapsedMs = elapsedClockMs() + durationMs.coerceIn(5_000L, 25_000L)
    }

    fun clearOffRouteDetour() {
        offRouteDetourUntilElapsedMs = 0L
        faultStatusListener(
            SimulatedFaultStatus(
                isTunnelBlackoutActive = elapsedClockMs() < tunnelBlackoutUntilElapsedMs,
                isOffRouteDetourActive = false
            )
        )
    }

    fun clearInjectedFaults() {
        tunnelBlackoutUntilElapsedMs = 0L
        offRouteDetourUntilElapsedMs = 0L
        faultStatusListener(SimulatedFaultStatus())
    }

    override fun observeLocations(minIntervalMs: Long): Flow<LocationFix> = flow {
        val stepIntervalMs = minIntervalMs.coerceIn(250L, 1_000L).coerceAtMost(tickIntervalMs)
        var trackedRouteId: String? = null
        var trackedRouteStart: LatLng? = null
        var trackedRouteEnd: LatLng? = null
        var trackedLegsCount: Int = -1
        var distanceAlongRoute = 0.0
        var lastTickElapsedMs = elapsedClockMs()

        while (true) {
            val nowElapsedMs = elapsedClockMs()
            val dtSec = ((nowElapsedMs - lastTickElapsedMs).coerceIn(100L, 2_000L)) / 1000.0
            lastTickElapsedMs = nowElapsedMs

            val snapshot = routeSnapshotProvider()
            val cfg = configProvider()
            val route = snapshot.route

            if (route != null && route.geometry.size >= 2 && !snapshot.isFinished) {
                val routeStart = route.geometry.firstOrNull()
                val routeEnd = route.geometry.lastOrNull()
                val routeSwitched = trackedRouteId != null && (
                    route.id != trackedRouteId ||
                        routeStart != trackedRouteStart ||
                        routeEnd != trackedRouteEnd ||
                        route.legs.size != trackedLegsCount
                    )
                if (routeSwitched && offRouteDetourUntilElapsedMs > 0L) {
                    // Reroute updated the active route; resume cleanly on the new polyline
                    offRouteDetourUntilElapsedMs = 0L
                }
                if (route.id != trackedRouteId ||
                    routeStart != trackedRouteStart ||
                    routeEnd != trackedRouteEnd ||
                    route.legs.size != trackedLegsCount
                ) {
                    trackedRouteId = route.id
                    trackedRouteStart = routeStart
                    trackedRouteEnd = routeEnd
                    trackedLegsCount = route.legs.size
                    distanceAlongRoute = 0.0
                }
            }

            val inTunnel = nowElapsedMs < tunnelBlackoutUntilElapsedMs
            val inDetour = nowElapsedMs < offRouteDetourUntilElapsedMs
            faultStatusListener(
                SimulatedFaultStatus(
                    isTunnelBlackoutActive = inTunnel,
                    isOffRouteDetourActive = inDetour
                )
            )

            if (route != null && route.geometry.size >= 2 && !snapshot.isFinished) {

                val sampleBefore = sampleAlongRoute(route, distanceAlongRoute)
                val baseSpeedMps = resolveTargetSpeedMps(route, sampleBefore.segmentIndex)
                val multiplier = cfg.speedMultiplier.coerceIn(0.5f, 8.0f)

                val effectiveTravelSpeed = if (snapshot.isPausedAtStop) 0.0f else baseSpeedMps * multiplier
                if (!snapshot.isPausedAtStop) {
                    distanceAlongRoute = (distanceAlongRoute + effectiveTravelSpeed * dtSec)
                        .coerceIn(0.0, route.totalDistanceMeters)
                }

                if (!inTunnel) {
                    val sample = sampleAlongRoute(route, distanceAlongRoute)
                    val remainingToEnd = (route.totalDistanceMeters - distanceAlongRoute).coerceAtLeast(0.0)
                    val reportedSpeed = if (snapshot.isPausedAtStop) 0f else baseSpeedMps

                    val baseFix = buildSimulatedFix(
                        nominalPos = sample.position,
                        nominalBearingDeg = sample.bearingDegrees,
                        nominalSpeedMps = reportedSpeed,
                        remainingDistanceMeters = remainingToEnd,
                        noiseEnabled = cfg.noiseEnabled && !snapshot.isPausedAtStop,
                        lateralDetourMeters = if (inDetour) offRouteLateralMeters else 0.0,
                        epochMs = epochClockMs(),
                        elapsedMs = nowElapsedMs
                    )
                    emit(baseFix)
                }
            }

            delay(stepIntervalMs)
        }
    }

    internal fun buildSimulatedFix(
        nominalPos: LatLng,
        nominalBearingDeg: Double,
        nominalSpeedMps: Float,
        remainingDistanceMeters: Double,
        noiseEnabled: Boolean,
        lateralDetourMeters: Double,
        epochMs: Long,
        elapsedMs: Long
    ): LocationFix {
        var eastOffset = 0.0
        var northOffset = 0.0

        // When injecting an off-route detour, offset perpendicular right of road bearing
        if (abs(lateralDetourMeters) > 0.5) {
            val perpRad = Math.toRadians(nominalBearingDeg + 90.0)
            eastOffset += sin(perpRad) * lateralDetourMeters
            northOffset += cos(perpRad) * lateralDetourMeters
        }

        // Taper jitter near destination/stop so simulated arrival isn't bounced out of 32m radius
        val noiseScale = when {
            !noiseEnabled -> 0.0
            abs(lateralDetourMeters) > 0.5 -> 0.35
            remainingDistanceMeters < 45.0 -> (remainingDistanceMeters / 45.0).coerceIn(0.0, 1.0)
            else -> 1.0
        }

        if (noiseScale > 0.0) {
            val rawE = nextClampedGaussian(sigma = 4.2, maxAbs = 13.0) * noiseScale
            val rawN = nextClampedGaussian(sigma = 4.2, maxAbs = 13.0) * noiseScale
            eastOffset += rawE
            northOffset += rawN
        }

        val noisyPos = if (abs(eastOffset) > 0.01 || abs(northOffset) > 0.01) {
            GeoMath.fromLocalEnMeters(nominalPos, eastOffset, northOffset)
        } else {
            nominalPos
        }

        val jitterMagnitude = hypot(eastOffset, northOffset).toFloat()
        val reportedAccuracy = if (noiseScale > 0.0) {
            (7.5f + jitterMagnitude * 0.7f + random.nextFloat() * 4.5f).coerceIn(5f, 22f)
        } else {
            6f
        }

        val noisySpeed = if (noiseScale > 0.0 && nominalSpeedMps > 1f) {
            (nominalSpeedMps + nextClampedGaussian(sigma = 0.85, maxAbs = 2.5).toFloat() * noiseScale.toFloat())
                .coerceAtLeast(0f)
        } else {
            nominalSpeedMps
        }

        val noisyBearing = if (noiseScale > 0.0) {
            GeoMath.normalizeDegrees(
                nominalBearingDeg + nextClampedGaussian(sigma = 3.5, maxAbs = 10.0) * noiseScale
            ).toFloat()
        } else {
            GeoMath.normalizeDegrees(nominalBearingDeg).toFloat()
        }

        return LocationFix(
            position = noisyPos,
            accuracyMeters = reportedAccuracy,
            speedMps = noisySpeed,
            bearingDegrees = noisyBearing,
            timestampMs = epochMs,
            elapsedRealtimeNanos = elapsedMs * 1_000_000L,
            provider = LocationProviderKind.SIMULATED
        )
    }

    private fun nextClampedGaussian(sigma: Double, maxAbs: Double): Double {
        // Box-Muller / java.util.Random.nextGaussian
        return (random.nextGaussian() * sigma).coerceIn(-maxAbs, maxAbs)
    }

    private fun resolveTargetSpeedMps(route: NavRoute, geomIndex: Int): Float {
        for (leg in route.legs) {
            val startIdx = leg.segmentStartGeometryIndex
            val relIdx = geomIndex - startIdx
            if (relIdx in leg.segmentMaxSpeeds.indices) {
                val limit = leg.segmentMaxSpeeds[relIdx].speedKmh
                if (limit != null && limit > 0) {
                    return (limit / 3.6f).coerceIn(5.5f, 34.0f)
                }
            }
        }
        // Fallback when OSM segment lacks maxspeed tag: urban cruise ~52 km/h (14.5 m/s)
        return DEFAULT_SIM_CRUISE_MPS
    }

    private data class RouteSample(
        val position: LatLng,
        val bearingDegrees: Double,
        val segmentIndex: Int
    )

    private fun sampleAlongRoute(route: NavRoute, targetMeters: Double): RouteSample {
        val geom = route.geometry
        if (geom.size < 2) {
            return RouteSample(geom.firstOrNull() ?: LatLng(0.0, 0.0), 0.0, 0)
        }
        val cum = route.cumulativeDistancesMeters
        val clamped = targetMeters.coerceIn(0.0, route.totalDistanceMeters)
        for (i in 0 until geom.lastIndex) {
            val startDist = cum.getOrElse(i) { 0.0 }
            val endDist = cum.getOrElse(i + 1) { route.totalDistanceMeters }
            if (clamped <= endDist || i == geom.lastIndex - 1) {
                val segLen = (endDist - startDist).coerceAtLeast(1e-6)
                val frac = ((clamped - startDist) / segLen).coerceIn(0.0, 1.0)
                val pos = GeoMath.interpolateLatLng(geom[i], geom[i + 1], frac)
                val brg = if (GeoMath.haversineMeters(geom[i], geom[i + 1]) > 0.2) {
                    GeoMath.initialBearingDegrees(geom[i], geom[i + 1])
                } else {
                    0.0
                }
                return RouteSample(pos, brg, i)
            }
        }
        val lastIdx = geom.lastIndex
        val brg = GeoMath.initialBearingDegrees(geom[lastIdx - 1], geom[lastIdx])
        return RouteSample(geom.last(), brg, (lastIdx - 1).coerceAtLeast(0))
    }

    companion object {
        private const val DEFAULT_SIM_CRUISE_MPS = 14.5f
    }
}

/**
 * Coordinates Settings-driven Simulation activation, 1x/2x/4x speed scaling, noise toggle,
 * and Developer Debug fault injections against [LocationSourceCoordinator].
 */
@Singleton
class RouteSimulationController @Inject constructor(
    private val locationSourceCoordinator: LocationSourceCoordinator
) {
    @Volatile
    private var latestRouteSnapshot = SimulatedRouteProgressSnapshot()

    @Volatile
    private var latestConfig = SimulationRuntimeConfig()

    private val _faultStatus = MutableStateFlow(SimulatedFaultStatus())
    val faultStatus: StateFlow<SimulatedFaultStatus> = _faultStatus.asStateFlow()

    private val simulatedSource = SimulatedLocationSource(
        routeSnapshotProvider = { latestRouteSnapshot },
        configProvider = { latestConfig },
        faultStatusListener = { _faultStatus.value = it }
    )

    @Volatile
    private var prevHadGpxReplay = false

    fun updateRuntimeConfig(
        config: SimulationRuntimeConfig,
        hasActiveGpxReplay: Boolean
    ) {
        val wasEnabled = latestConfig.enabled
        val replayJustStopped = prevHadGpxReplay && !hasActiveGpxReplay
        latestConfig = config
        prevHadGpxReplay = hasActiveGpxReplay
        if (config.enabled) {
            if ((!wasEnabled || replayJustStopped) && !hasActiveGpxReplay) {
                locationSourceCoordinator.setOverrideSource(simulatedSource)
            }
        } else if (wasEnabled) {
            simulatedSource.clearInjectedFaults()
            if (!hasActiveGpxReplay) {
                locationSourceCoordinator.setOverrideSource(null)
            }
        }
    }

    fun updateRouteSnapshot(snapshot: SimulatedRouteProgressSnapshot) {
        latestRouteSnapshot = snapshot
    }

    fun injectTunnelBlackout(durationMs: Long = 8_000L) {
        simulatedSource.injectTunnelBlackout(durationMs)
    }

    fun injectOffRouteDetour(durationMs: Long = 15_000L, lateralOffsetMeters: Double = 80.0) {
        simulatedSource.injectOffRouteDetour(durationMs, lateralOffsetMeters)
    }

    fun clearOffRouteDetour() {
        simulatedSource.clearOffRouteDetour()
    }

    fun clearFaults() {
        simulatedSource.clearInjectedFaults()
    }
}
