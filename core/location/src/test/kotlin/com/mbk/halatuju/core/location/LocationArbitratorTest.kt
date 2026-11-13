package com.mbk.halatuju.core.location

import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.LocationProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocationArbitratorTest {

    @Test
    fun rejectsNullIslandAndNaNCoordinates() {
        val arbitrator = LocationArbitrator()
        assertNull(
            arbitrator.evaluate(
                LocationFix(LatLng(0.0, 0.0), 10f, 0f, 0f, 1_000L, provider = LocationProviderKind.GPS)
            )
        )
        assertNull(
            arbitrator.evaluateRaw(Double.NaN, 101.7, 10f, 0f, 0f, 1_000L)
        )
        assertNull(
            arbitrator.evaluateRaw(95.0, 101.7, 10f, 0f, 0f, 1_000L)
        )
        val threw = runCatching { LatLng(Double.NaN, 101.7) }.exceptionOrNull()
        assertNotNull(threw)
    }

    @Test
    fun suppressesFreshNetworkWhenGpsIsActive() {
        val arbitrator = LocationArbitrator(gpsFreshnessHoldMs = 4_000L)
        val gps = LocationFix(KlDefaults.KLCC, 6f, 12f, 45f, 10_000L, provider = LocationProviderKind.GPS)
        val network = LocationFix(KlDefaults.KL_SENTRAL, 120f, null, null, 12_000L, provider = LocationProviderKind.NETWORK)

        assertNotNull(arbitrator.evaluate(gps))
        assertNull(arbitrator.evaluate(network))
    }

    @Test
    fun fallsBackToNetworkAfterGpsExpires() {
        val arbitrator = LocationArbitrator(gpsFreshnessHoldMs = 4_000L)
        val gps = LocationFix(KlDefaults.KLCC, 6f, 12f, 45f, 10_000L, provider = LocationProviderKind.GPS)
        val staleNetwork = LocationFix(KlDefaults.KL_SENTRAL, 40f, null, null, 20_000L, provider = LocationProviderKind.NETWORK)

        assertNotNull(arbitrator.evaluate(gps))
        val accepted = arbitrator.evaluate(staleNetwork)
        assertNotNull(accepted)
        assertEquals(350f, arbitrator.evaluate(staleNetwork.copy(bearingDegrees = -10f))?.bearingDegrees)
    }
}
