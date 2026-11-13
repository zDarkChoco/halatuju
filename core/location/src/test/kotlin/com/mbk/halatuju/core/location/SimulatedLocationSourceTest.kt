package com.mbk.halatuju.core.location

import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.RouteLeg
import com.mbk.halatuju.core.model.RouteStep
import com.mbk.halatuju.core.model.SegmentSpeedLimit
import com.mbk.halatuju.core.model.ManeuverModifier
import com.mbk.halatuju.core.model.ManeuverType
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedLocationSourceTest {

    private fun testRoute(id: String = "sim_route_1", limitKmh: Int = 80): NavRoute {
        val start = KlDefaults.KLCC
        val end = GeoMath.fromLocalEnMeters(start, eastMeters = 2_000.0, northMeters = 0.0)
        val mid = GeoMath.interpolateLatLng(start, end, 0.5)
        return NavRoute(
            id = id,
            geometry = listOf(start, mid, end),
            cumulativeDistancesMeters = listOf(0.0, 1_000.0, 2_000.0),
            totalDistanceMeters = 2_000.0,
            baseDurationSeconds = 90.0,
            adjustedDurationSeconds = 90.0,
            trafficEstimated = true,
            legs = listOf(
                RouteLeg(
                    summary = "Lebuhraya Sim",
                    distanceMeters = 2_000.0,
                    durationSeconds = 90.0,
                    segmentMaxSpeeds = listOf(
                        SegmentSpeedLimit(speedKmh = limitKmh, isExplicitNone = false, isUnknown = false),
                        SegmentSpeedLimit(speedKmh = limitKmh, isExplicitNone = false, isUnknown = false)
                    ),
                    steps = listOf(
                        RouteStep(
                            instructionText = "Drive east",
                            roadName = "Jalan Ampang",
                            maneuverType = ManeuverType.DEPART,
                            maneuverModifier = ManeuverModifier.STRAIGHT,
                            maneuverLocation = start,
                            bearingBefore = 90.0,
                            bearingAfter = 90.0,
                            distanceMeters = 2_000.0,
                            durationSeconds = 90.0,
                            geometryStartIndex = 0,
                            geometryEndIndex = 2
                        )
                    )
                )
            ),
            waypoints = listOf(
                Place("a", "Start", "", start),
                Place("b", "End", "", end)
            )
        )
    }

    @Test
    fun `simulation respects 4x speed multiplier and adds bounded GPS jitter when enabled`() {
        val route = testRoute()
        var clockMs = 1_000L
        val src = SimulatedLocationSource(
            routeSnapshotProvider = { SimulatedRouteProgressSnapshot(route = route) },
            configProvider = {
                SimulationRuntimeConfig(enabled = true, speedMultiplier = 4.0f, noiseEnabled = true)
            },
            tickIntervalMs = 200L,
            epochClockMs = { clockMs },
            elapsedClockMs = {
                clockMs += 1_000L
                clockMs
            }
        )

        val fixes = runBlocking { src.observeLocations(250L).take(4).toList() }
        assertEquals(4, fixes.size)
        // Expected base speed 80 km/h (~22.2 m/s) x 4x = ~88.8 m/s -> after 3s step ~355m east
        val eastDist = GeoMath.haversineMeters(KlDefaults.KLCC, fixes.last().position)
        assertTrue("Expected significant 4x progress >220m, got $eastDist", eastDist > 220.0)
        // Jitter adds lateral north displacement while remaining close to route corridor (< 20m north)
        val (_, northMeters) = GeoMath.toLocalEnMeters(KlDefaults.KLCC, fixes[1].position)
        assertTrue("Expected lateral jitter under 18m north, got $northMeters", kotlin.math.abs(northMeters) < 18.0)
    }

    @Test
    fun `off route detour injection pushes fixes laterally outside snap corridor then recovers`() {
        val route = testRoute()
        var clockMs = 1_000L
        var lastFault = SimulatedFaultStatus()
        lateinit var src: SimulatedLocationSource
        src = SimulatedLocationSource(
            routeSnapshotProvider = { SimulatedRouteProgressSnapshot(route = route) },
            configProvider = {
                SimulationRuntimeConfig(enabled = true, speedMultiplier = 1.0f, noiseEnabled = false)
            },
            tickIntervalMs = 20L,
            epochClockMs = { clockMs },
            elapsedClockMs = { clockMs },
            faultStatusListener = { lastFault = it }
        )

        src.injectOffRouteDetour(durationMs = 6_000L, lateralOffsetMeters = 58.0)
        val cleanThenDetour = src.buildSimulatedFix(
            nominalPos = KlDefaults.KLCC,
            nominalBearingDeg = 90.0,
            nominalSpeedMps = 20f,
            remainingDistanceMeters = 1_500.0,
            noiseEnabled = false,
            lateralDetourMeters = 58.0,
            epochMs = 5_000L,
            elapsedMs = 5_000L
        )
        // Bearing east (90 deg) -> +90 deg lateral offset goes due south (-north)
        val (_, northMeters) = GeoMath.toLocalEnMeters(KlDefaults.KLCC, cleanThenDetour.position)
        assertTrue("Expected ~58m lateral shift south, got $northMeters", northMeters < -50.0)

        src.clearInjectedFaults()
        assertFalse(lastFault.isOffRouteDetourActive)
        assertFalse(lastFault.isTunnelBlackoutActive)
    }

    @Test
    fun `pausing at intermediate stop freezes forward progress cleanly`() = runBlocking {
        val route = testRoute()
        var clockMs = 1_000L
        var paused = true
        val src = SimulatedLocationSource(
            routeSnapshotProvider = { SimulatedRouteProgressSnapshot(route = route, isPausedAtStop = paused) },
            configProvider = {
                SimulationRuntimeConfig(enabled = true, speedMultiplier = 2.0f, noiseEnabled = false)
            },
            tickIntervalMs = 20L,
            epochClockMs = { clockMs },
            elapsedClockMs = {
                clockMs += 1_000L
                clockMs
            }
        )

        val fixes = src.observeLocations(250L).take(3).toList()
        assertEquals(0f, fixes[0].speedMps ?: -1f, 0.01f)
        assertEquals(fixes[0].position.longitude, fixes[2].position.longitude, 1e-7)
        paused = false
        val resumed = src.observeLocations(250L).take(2).toList()
        assertTrue(resumed[1].position.longitude > fixes[2].position.longitude)
    }

    @Test
    fun `rerouting to new route clears active off route detour cleanly`() = runBlocking {
        val routeA = testRoute(id = "valhalla_0")
        val rerouteStart = GeoMath.fromLocalEnMeters(KlDefaults.KLCC, eastMeters = 400.0, northMeters = -80.0)
        val rerouteEnd = routeA.geometry.last()
        val routeB = routeA.copy(
            id = "valhalla_0",
            geometry = listOf(rerouteStart, GeoMath.interpolateLatLng(rerouteStart, rerouteEnd, 0.5), rerouteEnd),
            waypoints = listOf(Place("r0", "Reroute", "", rerouteStart), routeA.waypoints.last())
        )
        var currentRoute = routeA
        var clockMs = 1_000L
        var lastFault = SimulatedFaultStatus()
        val src = SimulatedLocationSource(
            routeSnapshotProvider = { SimulatedRouteProgressSnapshot(route = currentRoute) },
            configProvider = {
                SimulationRuntimeConfig(enabled = true, speedMultiplier = 1.0f, noiseEnabled = false)
            },
            tickIntervalMs = 20L,
            epochClockMs = { clockMs },
            elapsedClockMs = {
                clockMs += 1_000L
                clockMs
            },
            faultStatusListener = { lastFault = it }
        )

        src.injectOffRouteDetour(durationMs = 20_000L, lateralOffsetMeters = 80.0)
        val emitted = mutableListOf<com.mbk.halatuju.core.model.LocationFix>()
        src.observeLocations(250L).take(4).collect { fix ->
            emitted += fix
            if (emitted.size == 2) {
                assertTrue(lastFault.isOffRouteDetourActive)
                currentRoute = routeB
            }
        }
        assertFalse(lastFault.isOffRouteDetourActive)
        // Third fix is emitted immediately on routeB without further 80m detour offset
        val (_, northDeltaFromB) = GeoMath.toLocalEnMeters(rerouteStart, emitted[2].position)
        assertTrue("Expected post-reroute fix to lie on routeB, got northDelta=$northDeltaFromB", kotlin.math.abs(northDeltaFromB) < 5.0)
    }
}
