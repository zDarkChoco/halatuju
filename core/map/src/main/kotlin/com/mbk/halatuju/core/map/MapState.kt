package com.mbk.halatuju.core.map

import com.mbk.halatuju.core.model.DebugOverlayData
import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.MapCameraState
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.PuckRenderState
import com.mbk.halatuju.core.model.ServiceEndpoints

data class MapRenderingState(
    val isDarkTheme: Boolean = false,
    val endpoints: ServiceEndpoints = ServiceEndpoints(),
    val cameraState: MapCameraState = MapCameraState(),
    val puckState: PuckRenderState? = null,
    val activeRoute: NavRoute? = null,
    val alternativeRoutes: List<NavRoute> = emptyList(),
    val selectedRouteIndex: Int = 0,
    val traveledDistanceMeters: Double = 0.0,
    val waypoints: List<Place> = emptyList(),
    val droppedPin: Place? = null,
    val debugOverlayEnabled: Boolean = false,
    val debugOverlayData: DebugOverlayData? = null
)

data class SplitRoutePolylines(
    val traveled: List<LatLng>,
    val remaining: List<LatLng>
)

object RouteGeometrySplitter {
    fun splitAtDistance(route: NavRoute, traveledDistanceMeters: Double): SplitRoutePolylines {
        val pts = route.geometry
        val cum = route.cumulativeDistancesMeters
        if (pts.size < 2 || cum.size != pts.size) {
            return SplitRoutePolylines(emptyList(), pts)
        }
        val d = traveledDistanceMeters.coerceIn(0.0, route.totalDistanceMeters)
        if (d <= 0.5) {
            return SplitRoutePolylines(emptyList(), pts)
        }
        if (d >= route.totalDistanceMeters - 0.5) {
            return SplitRoutePolylines(pts, emptyList())
        }

        // Binary search segment containing d
        var segIdx = 0
        for (i in 0 until cum.lastIndex) {
            if (d <= cum[i + 1]) {
                segIdx = i
                break
            }
        }

        val segStart = cum[segIdx]
        val segEnd = cum[segIdx + 1]
        val segLen = (segEnd - segStart).coerceAtLeast(1e-6)
        val fraction = ((d - segStart) / segLen).coerceIn(0.0, 1.0)
        val splitPoint = GeoMath.interpolateLatLng(pts[segIdx], pts[segIdx + 1], fraction)

        val traveledList = buildList {
            for (i in 0..segIdx) add(pts[i])
            if (lastOrNull() != splitPoint) add(splitPoint)
        }
        val remainingList = buildList {
            add(splitPoint)
            for (i in (segIdx + 1) until pts.size) add(pts[i])
        }
        return SplitRoutePolylines(traveled = traveledList, remaining = remainingList)
    }
}
