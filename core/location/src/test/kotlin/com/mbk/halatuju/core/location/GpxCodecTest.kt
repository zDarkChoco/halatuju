package com.mbk.halatuju.core.location

import com.mbk.halatuju.core.model.KlDefaults
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.LocationProviderKind
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class GpxCodecTest {

    @Test
    fun `encode and parse round trips KL coordinates, timestamps, accuracy, speed and bearing`() {
        val original = listOf(
            LocationFix(
                position = KlDefaults.KLCC,
                accuracyMeters = 8.5f,
                speedMps = 13.8f,
                bearingDegrees = 192.4f,
                timestampMs = 1_778_580_000_000L,
                provider = LocationProviderKind.GPS
            ),
            LocationFix(
                position = KlDefaults.KL_SENTRAL,
                accuracyMeters = 11.2f,
                speedMps = 22.1f,
                bearingDegrees = 205.0f,
                timestampMs = 1_778_580_120_000L,
                provider = LocationProviderKind.GPS
            )
        )

        val xml = GpxCodec.encode(original, trackName = "KLCC to Sentral <Test>")
        assertTrue(xml.contains("<gpx version=\"1.1\""))
        assertTrue(xml.contains("KLCC to Sentral &lt;Test&gt;"))

        val decoded = GpxCodec.parse(xml).getOrThrow()
        assertEquals(2, decoded.size)
        assertEquals(KlDefaults.KLCC.latitude, decoded[0].position.latitude, 1e-6)
        assertEquals(KlDefaults.KLCC.longitude, decoded[0].position.longitude, 1e-6)
        assertEquals(8.5f, decoded[0].accuracyMeters, 0.05f)
        assertEquals(13.8f, decoded[0].speedMps ?: 0f, 0.05f)
        assertEquals(192.4f, decoded[0].bearingDegrees ?: 0f, 0.1f)
        assertEquals(1_778_580_000_000L, decoded[0].timestampMs)
        assertEquals(LocationProviderKind.GPX_REPLAY, decoded[0].provider)
        assertEquals(1_778_580_120_000L, decoded[1].timestampMs)
    }

    @Test
    fun `parse computes missing speed and bearing and honors hdop on standard GPX files`() {
        val externalGpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg>
                <trkpt lat="3.157900" lon="101.711600"><hdop>2.4</hdop><time>2026-11-13T08:00:00Z</time></trkpt>
                <trkpt lat="3.158800" lon="101.711600"><hdop>1.8</hdop><time>2026-11-13T08:00:10Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val pts = GpxCodec.parse(externalGpx).getOrThrow()
        assertEquals(2, pts.size)
        assertEquals(12.0f, pts[0].accuracyMeters, 0.1f)
        assertEquals(9.0f, pts[1].accuracyMeters, 0.1f)
        // Moving due north from 3.1579 to 3.1588 (~100m in 10s -> ~10 m/s, bearing ~0 deg)
        assertTrue((pts[1].speedMps ?: 0f) in 9.0f..11.5f)
        val brg = pts[0].bearingDegrees ?: -1f
        assertTrue("Expected near-north bearing around 0 deg but was $brg", brg < 2f || brg > 358f)
    }

    @Test
    fun `parse rejects empty content and invalid non-gpx xml`() {
        assertTrue(GpxCodec.parse("").isFailure)
        assertTrue(GpxCodec.parse("<notgpx><trkpt lat=\"3.1\" lon=\"101.7\"/></notgpx>").isFailure)
        assertTrue(GpxCodec.parse("<gpx><trk><trkseg><trkpt lat=\"0.0\" lon=\"0.0\"/></trkseg></trk></gpx>").isFailure)
    }

    @Test
    fun `recorder tracks fixes, ignores dead reckoned ticks, and writes shareable gpx file`() {
        val tempRoot = createTempDirectory("gpx_rec_test").toFile()
        try {
            val recorder = GpxRecorder { tempRoot }
            recorder.setRecording(true)

            recorder.recordFix(
                LocationFix(
                    position = KlDefaults.KLCC,
                    accuracyMeters = 9f,
                    speedMps = 12f,
                    bearingDegrees = 180f,
                    timestampMs = 1_778_580_000_000L,
                    provider = LocationProviderKind.GPS
                )
            )
            recorder.recordFix(
                LocationFix(
                    position = LatLng(3.155, 101.710),
                    accuracyMeters = 15f,
                    speedMps = 12f,
                    bearingDegrees = 180f,
                    timestampMs = 1_778_580_001_000L,
                    provider = LocationProviderKind.DEAD_RECKONED
                )
            )
            assertEquals(1, recorder.state.value.recordedFixCount)

            val file = recorder.exportToShareableGpxFile(1_778_580_050_000L).getOrThrow()
            assertTrue(file.exists())
            assertEquals("gpx", file.parentFile?.name)
            val reDecoded = GpxCodec.parse(file.readText()).getOrThrow()
            assertEquals(1, reDecoded.size)
            assertNotNull(recorder.state.value.lastExportedFileName)

            recorder.clear()
            assertEquals(0, recorder.state.value.recordedFixCount)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `gpx replay location source emits parsed fixes scaled by speed multiplier`() = runBlocking {
        val points = listOf(
            LocationFix(KlDefaults.KLCC, 10f, 10f, 90f, 1_000L, provider = LocationProviderKind.GPX_REPLAY),
            LocationFix(KlDefaults.KL_SENTRAL, 10f, 15f, 210f, 2_000L, provider = LocationProviderKind.GPX_REPLAY)
        )
        val source = GpxReplayLocationSource(
            trackPoints = points,
            speedMultiplierProvider = { 2.0f },
            maxStepDelayMs = 120L,
            minStepDelayMs = 10L
        )
        val collected = source.observeLocations(500L).take(2).toList()
        assertEquals(2, collected.size)
        assertEquals(20.0f, collected[0].speedMps ?: 0f, 0.01f)
        assertEquals(30.0f, collected[1].speedMps ?: 0f, 0.01f)
        assertEquals(LocationProviderKind.GPX_REPLAY, collected[0].provider)
    }
}
