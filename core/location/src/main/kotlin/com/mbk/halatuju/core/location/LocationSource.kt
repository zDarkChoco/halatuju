package com.mbk.halatuju.core.location

import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.LocationProviderKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Abstraction for any live, simulated, replay, or optional fused location provider.
 */
interface LocationSource {
    val providerKind: LocationProviderKind
    fun observeLocations(minIntervalMs: Long = 1000L): Flow<LocationFix>
}

/**
 * Optional fused location provider interface so a Google Play Services / MicroG / UnifiedNlp
 * adapter can be plugged in cleanly without coupling core navigation to proprietary GMS APIs.
 */
interface FusedLocationAdapter : LocationSource {
    val isAvailableOnDevice: Boolean
}

enum class LocationOverrideKind {
    PLATFORM_DEFAULT,
    SIMULATED,
    GPX_REPLAY
}

/**
 * Coordinates the active [LocationSource] so simulation and GPX replay seamlessly feed
 * the exact same downstream Kalman, snapping, camera, notification, and voice pipeline.
 */
@Singleton
class LocationSourceCoordinator @Inject constructor(
    private val platformLocationSource: PlatformLocationSource,
    private val fusedLocationAdapter: FusedLocationAdapter
) : LocationSource {

    private val _overrideSource = MutableStateFlow<LocationSource?>(null)
    private val _useOptionalFused = MutableStateFlow(false)

    private val _latestFix = MutableStateFlow<LocationFix?>(null)
    val latestFix: StateFlow<LocationFix?> = _latestFix.asStateFlow()

    override val providerKind: LocationProviderKind
        get() = activeSource().providerKind

    fun setOverrideSource(source: LocationSource?) {
        _overrideSource.value = source
    }

    fun setUseOptionalFused(enabled: Boolean) {
        _useOptionalFused.value = enabled && fusedLocationAdapter.isAvailableOnDevice
    }

    fun recordLatestFix(fix: LocationFix) {
        _latestFix.value = fix
    }

    private fun activeSource(): LocationSource {
        _overrideSource.value?.let { return it }
        if (_useOptionalFused.value && fusedLocationAdapter.isAvailableOnDevice) {
            return fusedLocationAdapter
        }
        return platformLocationSource
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeLocations(minIntervalMs: Long): Flow<LocationFix> {
        return kotlinx.coroutines.flow.combine(
            _overrideSource,
            _useOptionalFused
        ) { overrideSrc, useFused ->
            overrideSrc ?: if (useFused && fusedLocationAdapter.isAvailableOnDevice) {
                fusedLocationAdapter
            } else {
                platformLocationSource
            }
        }.flatMapLatest { source ->
            source.observeLocations(minIntervalMs)
        }
    }
}
