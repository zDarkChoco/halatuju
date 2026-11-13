package com.mbk.halatuju.core.routing

import com.mbk.halatuju.core.model.LatLng
import kotlin.math.roundToLong

/**
 * Polyline6 (precision = 1e-6) encoder & decoder used by Valhalla routing geometry.
 */
object PolylineCodec {
    private const val FACTOR_6 = 1_000_000.0

    fun decodePolyline6(encoded: String): List<LatLng> {
        if (encoded.isEmpty()) return emptyList()
        val points = ArrayList<LatLng>(encoded.length / 4 + 2)
        var index = 0
        var lat = 0L
        var lon = 0L
        val len = encoded.length

        while (index < len) {
            var shift = 0
            var result = 0L
            while (index < len) {
                val b = (encoded[index++].code - 63).toLong()
                result = result or ((b and 0x1FL) shl shift)
                shift += 5
                if (b < 0x20L) break
            }
            val dLat = if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)
            lat += dLat

            shift = 0
            result = 0L
            while (index < len) {
                val b = (encoded[index++].code - 63).toLong()
                result = result or ((b and 0x1FL) shl shift)
                shift += 5
                if (b < 0x20L) break
            }
            val dLon = if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)
            lon += dLon

            val decodedLat = (lat / FACTOR_6).coerceIn(-90.0, 90.0)
            val decodedLon = (lon / FACTOR_6).coerceIn(-180.0, 180.0)
            points += LatLng(decodedLat, decodedLon)
        }
        return points
    }

    fun encodePolyline6(points: List<LatLng>): String {
        val sb = StringBuilder(points.size * 6)
        var prevLat = 0L
        var prevLon = 0L

        for (p in points) {
            val lat = (p.latitude * FACTOR_6).roundToLong()
            val lon = (p.longitude * FACTOR_6).roundToLong()
            appendSigned(sb, lat - prevLat)
            appendSigned(sb, lon - prevLon)
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    private fun appendSigned(sb: StringBuilder, value: Long) {
        var v = if (value < 0L) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20L) {
            sb.append(((0x20L or (v and 0x1FL)) + 63L).toInt().toChar())
            v = v shr 5
        }
        sb.append((v + 63L).toInt().toChar())
    }
}
