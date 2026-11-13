package com.mbk.halatuju.core.routing

import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.RoutingAvoidances

data class RouteRequest(
    val waypoints: List<Place>,
    val avoidances: RoutingAvoidances = RoutingAvoidances(),
    val preferAlternates: Boolean = true,
    val departureEpochMs: Long = System.currentTimeMillis(),
    val vehicleHeadingDegrees: Double? = null,
    val baseUrlOverride: String? = null
)

/**
 * Swappable turn-by-turn routing engine contract.
 */
interface Router {
    suspend fun calculateRoutes(request: RouteRequest): Result<List<NavRoute>>
}
