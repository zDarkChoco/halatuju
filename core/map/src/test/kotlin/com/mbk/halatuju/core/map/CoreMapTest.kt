package com.mbk.halatuju.core.map

import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.PuckRenderState
import com.mbk.halatuju.core.model.ServiceEndpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CoreMapTest {

    @Test
    fun tileSourceConfiguresLightAndCustomDark3DStyle() {
        val tileSource = OpenFreeMapTileSource(RuntimeEnvironment.getApplication())
        val endpoints = ServiceEndpoints(
            lightStyleUrl = "https://tiles.example.org/styles/custom-light",
            darkTileSourceUrl = "https://pmtiles.example.org/malaysia.json"
        )

        val light = tileSource.resolveStyle(isDarkTheme = false, endpoints = endpoints)
        assertEquals(MapStyleSpec.StyleUrl("https://tiles.example.org/styles/custom-light"), light)

        val dark = tileSource.resolveStyle(isDarkTheme = true, endpoints = endpoints)
        assertTrue(dark is MapStyleSpec.StyleJson)
        val json = (dark as MapStyleSpec.StyleJson).json
        assertTrue(json.contains("https://pmtiles.example.org/malaysia.json"))
        assertTrue(!json.contains(OpenFreeMapTileSource.TILE_URL_PLACEHOLDER))
        assertTrue(json.contains("\"fill-extrusion\""))
        assertTrue(json.contains("\"building-3d\""))
    }

    @Test
    fun puckAnimatorInterpolatesCircularlyAcrossNorthWithoutFlip() {
        val animator = PuckAnimator(defaultTransitionMs = 400L)
        animator.setTarget(
            PuckRenderState(position = LatLng(3.1500, 101.7100), bearingDegrees = 350f, accuracyMeters = 10f),
            nowMs = 1_000L
        )
        animator.setTarget(
            PuckRenderState(position = LatLng(3.1510, 101.7100), bearingDegrees = 10f, accuracyMeters = 6f),
            nowMs = 1_000L
        )

        val mid = animator.currentAt(1_200L)!!
        // Midpoint between 350 and 10 deg shortest arc must be ~0 deg, NEVER 180 deg!
        assertEquals(0.0, mid.bearingDegrees.toDouble(), 1.5)
        assertEquals(3.1505, mid.position.latitude, 1e-4)
        assertEquals(8.0f, mid.accuracyMeters, 0.2f)
    }

    @Test
    fun routeGeometrySplitterSplitsAccurately() {
        val p0 = KlDefaults.KLCC
        val p1 = LatLng(3.1579, 101.7206) // ~1 km east
        val p2 = LatLng(3.1579, 101.7296) // ~2 km east
        val d01 = GeoMath.haversineMeters(p0, p1)
        val d12 = GeoMath.haversineMeters(p1, p2)
        val total = d01 + d12

        val route = NavRoute(
            id = "test",
            geometry = listOf(p0, p1, p2),
            cumulativeDistancesMeters = listOf(0.0, d01, total),
            totalDistanceMeters = total,
            baseDurationSeconds = 120.0,
            adjustedDurationSeconds = 120.0,
            trafficEstimated = false,
            legs = emptyList(),
            waypoints = emptyList()
        )

        val split = RouteGeometrySplitter.splitAtDistance(route, d01 * 0.5)
        assertEquals(2, split.traveled.size)
        assertEquals(3, split.remaining.size)
        assertEquals(p0.longitude, split.traveled.first().longitude, 1e-6)
        assertEquals(p2.longitude, split.remaining.last().longitude, 1e-6)
        assertEquals(split.traveled.last().longitude, split.remaining.first().longitude, 1e-6)
    }
}
