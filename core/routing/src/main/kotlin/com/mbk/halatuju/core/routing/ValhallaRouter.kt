package com.mbk.halatuju.core.routing

import com.mbk.halatuju.core.model.AppInfo
import com.mbk.halatuju.core.model.BannerInstruction
import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.IntersectionLanes
import com.mbk.halatuju.core.model.LaneIndication
import com.mbk.halatuju.core.model.LaneInfo
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.ManeuverModifier
import com.mbk.halatuju.core.model.ManeuverType
import com.mbk.halatuju.core.model.NavRoute
import com.mbk.halatuju.core.model.Place
import com.mbk.halatuju.core.model.RouteLeg
import com.mbk.halatuju.core.model.RouteStep
import com.mbk.halatuju.core.model.SegmentSpeedLimit
import com.mbk.halatuju.core.model.ServiceEndpoints
import com.mbk.halatuju.core.model.VoiceInstruction
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Valhalla `/route` client requesting OSRM-compatible output with banner/voice instructions,
 * per-intersection lane arrays, and `annotation.maxspeed` shape attributes.
 */
@Singleton
class ValhallaRouter internal constructor(
    private val okHttpClient: OkHttpClient,
    private val trafficProvider: TrafficProvider,
    private val json: Json,
    private val clockMs: () -> Long,
    private val cacheTtlMs: Long
) : Router {

    @Inject
    constructor(
        @Named("routing") okHttpClient: OkHttpClient,
        trafficProvider: TrafficProvider
    ) : this(
        okHttpClient = okHttpClient,
        trafficProvider = trafficProvider,
        json = Json { ignoreUnknownKeys = true },
        clockMs = { System.nanoTime() / 1_000_000L },
        cacheTtlMs = DEFAULT_CACHE_TTL_MS
    )

    private data class CacheEntry(
        val expiresAtMs: Long,
        val routes: List<NavRoute>
    )

    private val cacheMutex = Mutex()
    private val cache = object : LinkedHashMap<String, CacheEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    override suspend fun calculateRoutes(request: RouteRequest): Result<List<NavRoute>> {
        if (request.waypoints.size < 2) {
            return Result.failure(IllegalArgumentException("At least 2 waypoints required"))
        }
        val baseUrl = (request.baseUrlOverride?.takeIf { it.isNotBlank() }
            ?: ServiceEndpoints().valhallaBaseUrl)
            .trim()
            .trimEnd('/')

        val cacheKey = buildCacheKey(baseUrl, request)
        val now = clockMs()
        if (request.vehicleHeadingDegrees == null) {
            cacheMutex.withLock {
                val hit = cache[cacheKey]
                if (hit != null && hit.expiresAtMs > now) {
                    val reEstimated = reApplyTraffic(hit.routes, request.departureEpochMs)
                    return Result.success(reEstimated)
                }
            }
        }

        val endpointUrl = "$baseUrl/route".toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("Invalid Valhalla URL: $baseUrl"))

        val payloadJson = buildRequestPayload(request)
        val httpReq = Request.Builder()
            .url(endpointUrl)
            .header("User-Agent", AppInfo.USER_AGENT)
            .header("Accept", "application/json")
            .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return runCatching {
            okHttpClient.awaitExecute(httpReq).use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw IOException("Valhalla routing failed (${response.code}): ${body.take(240)}")
                }
                val rawRoutes = parseOsrmResponse(
                    rawJson = body,
                    waypoints = request.waypoints,
                    routeIdPrefix = "valhalla"
                )
                if (rawRoutes.isEmpty()) {
                    throw IOException("Valhalla returned no routes")
                }
                val estimatedRoutes = reApplyTraffic(rawRoutes, request.departureEpochMs)
                if (request.vehicleHeadingDegrees == null) {
                    cacheMutex.withLock {
                        cache[cacheKey] = CacheEntry(
                            expiresAtMs = clockMs() + cacheTtlMs,
                            routes = estimatedRoutes
                        )
                    }
                }
                estimatedRoutes
            }
        }
    }

    internal fun buildRequestPayload(request: RouteRequest): String {
        val useAlternates = request.preferAlternates && request.waypoints.size <= 2
        val payload = buildJsonObject {
            putJsonArray("locations") {
                request.waypoints.forEachIndexed { index, place ->
                    add(buildJsonObject {
                        put("lat", place.location.latitude)
                        put("lon", place.location.longitude)
                        put("type", "break")
                        if (index == 0 && request.vehicleHeadingDegrees != null) {
                            put("heading", GeoMath.normalizeDegrees(request.vehicleHeadingDegrees).roundToInt() % 360)
                            put("heading_tolerance", 55)
                        }
                    })
                }
            }
            put("costing", "auto")
            putJsonObject("costing_options") {
                putJsonObject("auto") {
                    if (request.avoidances.avoidTolls) put("exclude_tolls", true)
                    if (request.avoidances.avoidHighways) put("exclude_highways", true)
                    if (request.avoidances.avoidFerries) put("exclude_ferries", true)
                }
            }
            put("format", "osrm")
            put("shape_format", "polyline6")
            put("banner_instructions", true)
            put("voice_instructions", true)
            put("language", "en-US")
            if (useAlternates) {
                put("alternates", 2)
            } else {
                // Sent on single-route & active reroute queries so self-hosted traffic.tar uses time-dependent speeds
                putJsonObject("date_time") {
                    put("type", 0)
                }
            }
            putJsonObject("filters") {
                putJsonArray("attributes") {
                    add(JsonPrimitive("shape_attributes.speed_limit"))
                    add(JsonPrimitive("shape_attributes.speed"))
                    add(JsonPrimitive("shape_attributes.time"))
                    add(JsonPrimitive("shape_attributes.length"))
                }
                put("action", "include")
            }
        }
        return payload.toString()
    }

    internal suspend fun parseOsrmResponse(
        rawJson: String,
        waypoints: List<Place>,
        routeIdPrefix: String = "valhalla"
    ): List<NavRoute> {
        val dto = json.decodeFromString(OsrmResponseDto.serializer(), rawJson)
        if (!dto.code.isNullOrBlank() && !dto.code.equals("Ok", ignoreCase = true)) {
            throw IOException("Valhalla error: ${dto.code} ${dto.message.orEmpty()}".trim())
        }

        return dto.routes.mapIndexedNotNull { routeIndex, routeDto ->
            val geometry = PolylineCodec.decodePolyline6(routeDto.geometry)
            if (geometry.size < 2) return@mapIndexedNotNull null

            val cumulativeDistances = computeCumulativeDistances(geometry)
            val totalDist = routeDto.distance.takeIf { it > 0.0 } ?: cumulativeDistances.last()

            var legEdgeCursor = 0

            val parsedLegs = routeDto.legs.map { legDto ->
                val legStartGeomIdx = legEdgeCursor
                val maxSpeeds = legDto.annotation?.maxspeed.orEmpty().map { parseMaxSpeed(it) }
                val segDists = legDto.annotation?.distance.orEmpty()
                val segDurs = legDto.annotation?.duration.orEmpty()
                val explicitLegEdges = segDists.size.takeIf { it > 0 }
                    ?: maxSpeeds.size.takeIf { it > 0 }
                val legEndBound = if (explicitLegEdges != null) {
                    (legStartGeomIdx + explicitLegEdges).coerceAtMost(geometry.lastIndex)
                } else {
                    geometry.lastIndex
                }

                var stepGeomCursor = legStartGeomIdx
                val parsedSteps = legDto.steps.map { stepDto ->
                    val mType = mapManeuverType(stepDto.maneuver.type)
                    val mMod = mapManeuverModifier(stepDto.maneuver.modifier)
                    val stepPts = PolylineCodec.decodePolyline6(stepDto.geometry)
                    val isZeroEdgeArrive = mType == ManeuverType.ARRIVE && stepDto.distance <= 0.0
                    val stepEdges = if (isZeroEdgeArrive) 0 else (stepPts.size - 1).coerceAtLeast(0)
                    val startIdx = stepGeomCursor.coerceIn(legStartGeomIdx, legEndBound)
                    val endIdx = (startIdx + stepEdges).coerceIn(startIdx, legEndBound)
                    stepGeomCursor = endIdx

                    val banners = stepDto.bannerInstructions.map { parseBanner(it) }
                    val voices = stepDto.voiceInstructions.map { vi ->
                        VoiceInstruction(
                            distanceAlongGeometry = vi.distanceAlongGeometry,
                            announcement = vi.announcement,
                            ssmlAnnouncement = vi.ssmlAnnouncement
                        )
                    }
                    val intersections = parseIntersections(stepDto.intersections, legStartGeomIdx)
                    val roadTitle = stepDto.name.ifBlank {
                        banners.firstOrNull()?.primaryText ?: stepDto.maneuver.instruction
                    }

                    RouteStep(
                        instructionText = stepDto.maneuver.instruction,
                        roadName = roadTitle,
                        refCode = stepDto.ref?.takeIf { it.isNotBlank() },
                        destinations = stepDto.destinations?.takeIf { it.isNotBlank() },
                        exits = stepDto.exits?.takeIf { it.isNotBlank() },
                        maneuverType = mType,
                        maneuverModifier = mMod,
                        maneuverLocation = stepDto.maneuver.locationLatLng() ?: geometry[startIdx],
                        bearingBefore = stepDto.maneuver.bearingBefore,
                        bearingAfter = stepDto.maneuver.bearingAfter,
                        rotaryExitNumber = stepDto.maneuver.exit,
                        distanceMeters = stepDto.distance,
                        durationSeconds = stepDto.duration,
                        geometryStartIndex = startIdx,
                        geometryEndIndex = endIdx,
                        bannerInstructions = banners,
                        voiceInstructions = voices,
                        intersections = intersections
                    )
                }

                val legEdgeCount = explicitLegEdges
                    ?: parsedSteps.sumOf { it.geometryEndIndex - it.geometryStartIndex }
                legEdgeCursor = (legStartGeomIdx + legEdgeCount).coerceAtMost(geometry.lastIndex)

                RouteLeg(
                    summary = legDto.summary,
                    distanceMeters = legDto.distance,
                    durationSeconds = legDto.duration,
                    steps = parsedSteps,
                    segmentMaxSpeeds = maxSpeeds,
                    segmentDistancesMeters = segDists,
                    segmentDurationsSeconds = segDurs,
                    segmentStartGeometryIndex = legStartGeomIdx
                )
            }

            val summaryText = parsedLegs
                .map { it.summary.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" → ")

            NavRoute(
                id = "${routeIdPrefix}_$routeIndex",
                geometry = geometry,
                cumulativeDistancesMeters = cumulativeDistances,
                totalDistanceMeters = totalDist,
                baseDurationSeconds = routeDto.duration,
                adjustedDurationSeconds = routeDto.duration,
                trafficEstimated = false,
                trafficDelayFactor = 1.0,
                trafficLabel = "estimated",
                legs = parsedLegs,
                waypoints = waypoints,
                summaryLabel = summaryText
            )
        }.take(3)
    }

    private suspend fun reApplyTraffic(
        routes: List<NavRoute>,
        departureEpochMs: Long
    ): List<NavRoute> {
        return routes.map { route ->
            val estimate = trafficProvider.estimateRouteEta(route, departureEpochMs)
            route.copy(
                adjustedDurationSeconds = estimate.adjustedDurationSeconds,
                trafficEstimated = estimate.isEstimated,
                trafficDelayFactor = estimate.congestionFactor,
                trafficLabel = estimate.label
            )
        }
    }

    private fun parseMaxSpeed(obj: JsonObject): SegmentSpeedLimit {
        val unknown = (obj["unknown"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
        if (unknown) return SegmentSpeedLimit(isUnknown = true)

        val none = (obj["none"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
        if (none) return SegmentSpeedLimit(speedKmh = null, isExplicitNone = true, isUnknown = false)

        val rawSpeed = (obj["speed"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            ?: return SegmentSpeedLimit(isUnknown = true)
        val unit = (obj["unit"] as? JsonPrimitive)?.content ?: "km/h"
        val kmh = if (unit.equals("mph", ignoreCase = true)) {
            (rawSpeed * 1.609344).roundToInt()
        } else {
            rawSpeed.roundToInt()
        }
        return if (kmh in 5..250) {
            SegmentSpeedLimit(speedKmh = kmh, isExplicitNone = false, isUnknown = false)
        } else {
            SegmentSpeedLimit(isUnknown = true)
        }
    }

    private fun parseBanner(dto: BannerDto): BannerInstruction {
        val primary = dto.primary
        val exitNumStr = primary?.components
            ?.firstOrNull { it.type.equals("exit-number", ignoreCase = true) }
            ?.text
            ?.takeIf { it.isNotBlank() }

        return BannerInstruction(
            distanceAlongGeometry = dto.distanceAlongGeometry,
            primaryText = primary?.text.orEmpty(),
            primaryType = mapManeuverType(primary?.type),
            primaryModifier = mapManeuverModifier(primary?.modifier),
            secondaryText = dto.secondary?.text?.takeIf { it.isNotBlank() },
            shieldText = exitNumStr,
            exitNumber = exitNumStr?.filter { it.isDigit() }?.toIntOrNull()
        )
    }

    private fun parseIntersections(
        dtoIntersections: List<IntersectionDto>,
        legStartGeomIdx: Int
    ): List<IntersectionLanes> {
        return dtoIntersections.mapNotNull { ix ->
            val rawLanes = ix.lanes
            if (rawLanes.isEmpty()) return@mapNotNull null
            val loc = ix.locationLatLng() ?: return@mapNotNull null
            val lanes = rawLanes.mapNotNull { lane ->
                val indications = lane.indications.mapNotNull { mapLaneIndication(it) }
                if (indications.isEmpty()) null
                else LaneInfo(indications = indications, valid = lane.valid || lane.active)
            }
            if (lanes.isEmpty()) null
            else IntersectionLanes(
                location = loc,
                lanes = lanes,
                geometryIndex = ix.geometryIndex?.let { legStartGeomIdx + it }
            )
        }
    }

    private fun mapLaneIndication(raw: String): LaneIndication? {
        return when (raw.trim().lowercase(Locale.US)) {
            "none" -> LaneIndication.NONE
            "straight" -> LaneIndication.STRAIGHT
            "slight left" -> LaneIndication.SLIGHT_LEFT
            "left" -> LaneIndication.LEFT
            "sharp left" -> LaneIndication.SHARP_LEFT
            "slight right" -> LaneIndication.SLIGHT_RIGHT
            "right" -> LaneIndication.RIGHT
            "sharp right" -> LaneIndication.SHARP_RIGHT
            "uturn", "u-turn" -> LaneIndication.UTURN
            "merge left" -> LaneIndication.MERGE_LEFT
            "merge right" -> LaneIndication.MERGE_RIGHT
            else -> null
        }
    }

    private fun mapManeuverType(raw: String?): ManeuverType {
        return when (raw?.trim()?.lowercase(Locale.US)) {
            "depart" -> ManeuverType.DEPART
            "turn" -> ManeuverType.TURN
            "new name" -> ManeuverType.NEW_NAME
            "merge" -> ManeuverType.MERGE
            "on ramp" -> ManeuverType.ON_RAMP
            "off ramp" -> ManeuverType.OFF_RAMP
            "fork" -> ManeuverType.FORK
            "end of road" -> ManeuverType.END_OF_ROAD
            "continue" -> ManeuverType.CONTINUE
            "roundabout" -> ManeuverType.ROUNDABOUT
            "rotary" -> ManeuverType.ROTARY
            "roundabout turn" -> ManeuverType.ROUNDABOUT_TURN
            "exit roundabout" -> ManeuverType.EXIT_ROUNDABOUT
            "exit rotary" -> ManeuverType.EXIT_ROTARY
            "notification" -> ManeuverType.NOTIFICATION
            "arrive" -> ManeuverType.ARRIVE
            else -> ManeuverType.TURN
        }
    }

    private fun mapManeuverModifier(raw: String?): ManeuverModifier {
        return when (raw?.trim()?.lowercase(Locale.US)) {
            "uturn", "u-turn" -> ManeuverModifier.UTURN
            "sharp right" -> ManeuverModifier.SHARP_RIGHT
            "right" -> ManeuverModifier.RIGHT
            "slight right" -> ManeuverModifier.SLIGHT_RIGHT
            "straight" -> ManeuverModifier.STRAIGHT
            "slight left" -> ManeuverModifier.SLIGHT_LEFT
            "left" -> ManeuverModifier.LEFT
            "sharp left" -> ManeuverModifier.SHARP_LEFT
            else -> ManeuverModifier.NONE
        }
    }

    private fun computeCumulativeDistances(geometry: List<LatLng>): List<Double> {
        val cum = ArrayList<Double>(geometry.size)
        var acc = 0.0
        cum += 0.0
        for (i in 1 until geometry.size) {
            acc += GeoMath.haversineMeters(geometry[i - 1], geometry[i])
            cum += acc
        }
        return cum
    }

    private fun buildCacheKey(baseUrl: String, request: RouteRequest): String {
        val wpKey = request.waypoints.joinToString(";") {
            val rLat = (it.location.latitude * 10_000.0).roundToLong()
            val rLon = (it.location.longitude * 10_000.0).roundToLong()
            "$rLat,$rLon"
        }
        return "$baseUrl|$wpKey|${request.avoidances}|${request.preferAlternates}"
    }

    @Serializable
    private data class OsrmResponseDto(
        val code: String? = null,
        val message: String? = null,
        val routes: List<OsrmRouteDto> = emptyList()
    )

    @Serializable
    private data class OsrmRouteDto(
        val geometry: String = "",
        val distance: Double = 0.0,
        val duration: Double = 0.0,
        val legs: List<OsrmLegDto> = emptyList()
    )

    @Serializable
    private data class OsrmLegDto(
        val summary: String = "",
        val distance: Double = 0.0,
        val duration: Double = 0.0,
        val steps: List<OsrmStepDto> = emptyList(),
        val annotation: OsrmAnnotationDto? = null
    )

    @Serializable
    private data class OsrmAnnotationDto(
        val maxspeed: List<JsonObject> = emptyList(),
        val distance: List<Double> = emptyList(),
        val duration: List<Double> = emptyList()
    )

    @Serializable
    private data class OsrmStepDto(
        val geometry: String = "",
        val distance: Double = 0.0,
        val duration: Double = 0.0,
        val name: String = "",
        val ref: String? = null,
        val destinations: String? = null,
        val exits: String? = null,
        val maneuver: ManeuverDto = ManeuverDto(),
        val bannerInstructions: List<BannerDto> = emptyList(),
        val voiceInstructions: List<VoiceDto> = emptyList(),
        val intersections: List<IntersectionDto> = emptyList()
    )

    @Serializable
    private data class ManeuverDto(
        val type: String? = null,
        val modifier: String? = null,
        val instruction: String = "",
        val exit: Int? = null,
        @SerialName("bearing_before") val bearingBefore: Double = 0.0,
        @SerialName("bearing_after") val bearingAfter: Double = 0.0,
        val location: List<Double> = emptyList()
    ) {
        fun locationLatLng(): LatLng? {
            if (location.size < 2) return null
            return runCatching { LatLng(location[1], location[0]) }.getOrNull()
        }
    }

    @Serializable
    private data class BannerDto(
        val distanceAlongGeometry: Double = 0.0,
        val primary: BannerTextDto? = null,
        val secondary: BannerTextDto? = null,
        val sub: BannerSubDto? = null
    )

    @Serializable
    private data class BannerTextDto(
        val text: String = "",
        val type: String? = null,
        val modifier: String? = null,
        val components: List<BannerComponentDto> = emptyList()
    )

    @Serializable
    private data class BannerSubDto(
        val components: List<BannerComponentDto> = emptyList()
    )

    @Serializable
    private data class BannerComponentDto(
        val type: String = "",
        val text: String = "",
        val active: Boolean = false,
        val directions: List<String> = emptyList()
    )

    @Serializable
    private data class VoiceDto(
        val distanceAlongGeometry: Double = 0.0,
        val announcement: String = "",
        val ssmlAnnouncement: String? = null
    )

    @Serializable
    private data class IntersectionDto(
        val location: List<Double> = emptyList(),
        @SerialName("geometry_index") val geometryIndex: Int? = null,
        val lanes: List<LaneDto> = emptyList()
    ) {
        fun locationLatLng(): LatLng? {
            if (location.size < 2) return null
            return runCatching { LatLng(location[1], location[0]) }.getOrNull()
        }
    }

    @Serializable
    private data class LaneDto(
        val indications: List<String> = emptyList(),
        val valid: Boolean = false,
        val active: Boolean = false
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val DEFAULT_CACHE_TTL_MS = 5L * 60L * 1000L
        const val MAX_CACHE_ENTRIES = 32
    }
}

private suspend fun OkHttpClient.awaitExecute(request: Request): Response {
    return suspendCancellableCoroutine { cont: CancellableContinuation<Response> ->
        val call = newCall(request)
        cont.invokeOnCancellation {
            runCatching { call.cancel() }
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) {
                    cont.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) {
                    cont.resume(response)
                } else {
                    response.close()
                }
            }
        })
    }
}
