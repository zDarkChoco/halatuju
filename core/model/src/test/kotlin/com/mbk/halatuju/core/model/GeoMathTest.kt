package com.mbk.halatuju.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {
    @Test
    fun klccToKliaDistanceIsReasonable() {
        val dist = GeoMath.haversineMeters(KlDefaults.KLCC, KlDefaults.KLIA)
        // Straight-line KLCC to KLIA is ~45.8 km
        assertTrue("Expected ~45.8km, got $dist", dist in 44_000.0..48_000.0)
    }

    @Test
    fun circularAngleDifferenceWraps359To0() {
        assertEquals(2.0, GeoMath.angularDifferenceDegrees(359.0, 1.0), 1e-6)
        assertEquals(2.0, GeoMath.signedAngleDeltaDegrees(359.0, 1.0), 1e-6)
        assertEquals(-2.0, GeoMath.signedAngleDeltaDegrees(1.0, 359.0), 1e-6)
    }

    @Test
    fun enuRoundTripIsAccurateWithinMillimeters() {
        val anchor = KlDefaults.KLCC
        val target = KlDefaults.KL_SENTRAL
        val (e, n) = GeoMath.toLocalEnMeters(anchor, target)
        val restored = GeoMath.fromLocalEnMeters(anchor, e, n)
        assertEquals(target.latitude, restored.latitude, 1e-5)
        assertEquals(target.longitude, restored.longitude, 1e-5)
    }
}
