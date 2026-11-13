package com.mbk.halatuju.core.location

import android.os.SystemClock
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.LocationProviderKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

data class GpxReplayState(
    val isActive: Boolean = false,
    val trackName: String? = null,
    val pointCount: Int = 0,
    val errorMessage: String? = null
)

/**
 * Replays a parsed GPX trace at realistic (optionally accelerated) intervals into the real location pipeline.
 */
class GpxReplayLocationSource(
    private val trackPoints: List<LocationFix>,
    private val speedMultiplierProvider: () -> Float = { 1f },
    private val maxStepDelayMs: Long = 3_000L,
    private val minStepDelayMs: Long = 100L,
    private val epochClockMs: () -> Long = { System.currentTimeMillis() },
    private val monoClockNanos: () -> Long = {
        runCatching { SystemClock.elapsedRealtimeNanos() }.getOrElse { System.nanoTime() }
    }
) : LocationSource {

    override val providerKind: LocationProviderKind = LocationProviderKind.GPX_REPLAY

    override fun observeLocations(minIntervalMs: Long): Flow<LocationFix> = flow {
        if (trackPoints.isEmpty()) return@flow

        for (i in trackPoints.indices) {
            val pt = trackPoints[i]
            val mult = speedMultiplierProvider().coerceIn(0.5f, 8f)
            val liveSpeed = pt.speedMps?.let { (it * mult).coerceAtLeast(0f) }
            emit(
                pt.copy(
                    speedMps = liveSpeed,
                    timestampMs = epochClockMs(),
                    elapsedRealtimeNanos = monoClockNanos(),
                    provider = LocationProviderKind.GPX_REPLAY
                )
            )
            if (i < trackPoints.lastIndex) {
                val rawDeltaMs = (trackPoints[i + 1].timestampMs - pt.timestampMs).coerceIn(250L, 10_000L)
                val scaledDelay = (rawDeltaMs / mult).roundToLong().coerceIn(minStepDelayMs, maxStepDelayMs)
                delay(scaledDelay)
            }
        }
    }
}

@Singleton
class GpxReplayController @Inject constructor(
    private val locationSourceCoordinator: LocationSourceCoordinator
) {
    private val _state = MutableStateFlow(GpxReplayState())
    val state: StateFlow<GpxReplayState> = _state.asStateFlow()

    fun startReplayFromGpx(
        gpxXml: String,
        trackName: String,
        speedMultiplierProvider: () -> Float
    ): Result<Int> {
        val parsed = GpxCodec.parse(gpxXml)
        return parsed.map { points ->
            val replaySource = GpxReplayLocationSource(
                trackPoints = points,
                speedMultiplierProvider = speedMultiplierProvider
            )
            locationSourceCoordinator.setOverrideSource(replaySource)
            points.firstOrNull()?.let { first ->
                locationSourceCoordinator.recordLatestFix(
                    first.copy(
                        timestampMs = System.currentTimeMillis(),
                        provider = LocationProviderKind.GPX_REPLAY
                    )
                )
            }
            _state.value = GpxReplayState(
                isActive = true,
                trackName = trackName,
                pointCount = points.size,
                errorMessage = null
            )
            points.size
        }.onFailure { err ->
            _state.value = _state.value.copy(
                errorMessage = err.message ?: "Failed to parse GPX file"
            )
        }
    }

    fun stopReplay() {
        if (_state.value.isActive) {
            locationSourceCoordinator.setOverrideSource(null)
        }
        _state.value = GpxReplayState()
    }
}
