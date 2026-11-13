package com.mbk.halatuju.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.LocationProviderKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure Android platform [LocationManager] implementation (GPS + Network).
 * Works cleanly on AOSP, LineageOS, GrapheneOS, and de-Googled phones with zero GMS dependency.
 */
@Singleton
class PlatformLocationSource @Inject constructor(
    @ApplicationContext private val context: Context
) : LocationSource {

    override val providerKind: LocationProviderKind = LocationProviderKind.GPS

    @SuppressLint("MissingPermission")
    override fun observeLocations(minIntervalMs: Long): Flow<LocationFix> = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager == null || !LocationPermissionHelper.hasAnyLocationPermission(context)) {
            close()
            return@callbackFlow
        }

        val arbitrator = LocationArbitrator()
        val hasFine = LocationPermissionHelper.hasFineLocationPermission(context)

        fun emitAndroidLocation(location: Location, providerKind: LocationProviderKind) {
            val fix = location.toLocationFix(providerKind) ?: return
            arbitrator.evaluate(fix)?.let { trySend(it) }
        }

        // Seed from most recent valid lastKnownLocation (preferring GPS)
        val initialGps = runCatching {
            if (hasFine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } else null
        }.getOrNull()

        val initialNet = runCatching {
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else null
        }.getOrNull()

        val seed = listOfNotNull(
            initialGps?.toLocationFix(LocationProviderKind.GPS),
            initialNet?.toLocationFix(LocationProviderKind.NETWORK)
        ).maxByOrNull { it.timestampMs }
        seed?.let { arbitrator.evaluate(it)?.let { accepted -> trySend(accepted) } }

        val gpsListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                emitAndroidLocation(location, LocationProviderKind.GPS)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        val networkListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                emitAndroidLocation(location, LocationProviderKind.NETWORK)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        try {
            if (hasFine && manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    minIntervalMs.coerceAtLeast(250L),
                    0f,
                    gpsListener,
                    Looper.getMainLooper()
                )
            }
            if (manager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    (minIntervalMs * 2L).coerceAtLeast(2_000L),
                    0f,
                    networkListener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {
            close()
            return@callbackFlow
        }

        awaitClose {
            runCatching { manager.removeUpdates(gpsListener) }
            runCatching { manager.removeUpdates(networkListener) }
        }
    }

    private fun Location.toLocationFix(kind: LocationProviderKind): LocationFix? =
        LocationArbitrator.toValidLocationFix(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy() && accuracy.isFinite()) accuracy else 35f,
            speedMps = if (hasSpeed() && speed.isFinite()) speed else null,
            bearingDegrees = if (hasBearing() && bearing.isFinite()) bearing else null,
            timestampMs = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            provider = kind
        )
}
