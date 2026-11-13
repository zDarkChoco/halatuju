package com.mbk.halatuju.core.location

import com.mbk.halatuju.core.model.AppInfo
import com.mbk.halatuju.core.model.GeoMath
import com.mbk.halatuju.core.model.LatLng
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.LocationProviderKind
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * Pure GPX 1.1 encoder and parser for drive trace recording, share-sheet export, and replay testing.
 */
object GpxCodec {

    fun encode(
        fixes: List<LocationFix>,
        trackName: String = "HalaTuju Drive",
        creator: String = AppInfo.USER_AGENT
    ): String {
        val sb = StringBuilder(256 + fixes.size * 180)
        val firstTimeMs = fixes.firstOrNull()?.timestampMs ?: System.currentTimeMillis()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<gpx version="1.1" creator="${escapeXml(creator)}" xmlns="http://www.topografix.com/GPX/1/1">"""
        ).append('\n')
        sb.append("  <metadata>\n")
        sb.append("    <name>${escapeXml(trackName)}</name>\n")
        sb.append("    <time>${formatIsoTimestamp(firstTimeMs)}</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>${escapeXml(trackName)}</name>\n")
        sb.append("    <trkseg>\n")
        for (fix in fixes) {
            sb.append(
                String.format(
                    Locale.US,
                    "      <trkpt lat=\"%.7f\" lon=\"%.7f\">\n",
                    fix.position.latitude,
                    fix.position.longitude
                )
            )
            sb.append("        <time>${formatIsoTimestamp(fix.timestampMs)}</time>\n")
            val hasExtensions = fix.speedMps != null || fix.bearingDegrees != null ||
                abs(fix.accuracyMeters - DEFAULT_ACCURACY_METERS) > 0.1f
            if (hasExtensions) {
                sb.append("        <extensions>\n")
                sb.append(
                    String.format(Locale.US, "          <accuracy>%.2f</accuracy>\n", fix.accuracyMeters)
                )
                fix.speedMps?.let { spd ->
                    sb.append(String.format(Locale.US, "          <speed>%.2f</speed>\n", spd))
                }
                fix.bearingDegrees?.let { brg ->
                    sb.append(String.format(Locale.US, "          <course>%.2f</course>\n", brg))
                }
                sb.append("          <provider>${escapeXml(fix.provider.name)}</provider>\n")
                sb.append("        </extensions>\n")
            }
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    fun parse(gpxXml: String): Result<List<LocationFix>> = runCatching {
        val trimmed = gpxXml.trim()
        require(trimmed.isNotEmpty()) { "GPX content is empty" }

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(InputSource(StringReader(trimmed)))
        val root = doc.documentElement ?: error("Missing GPX root element")
        require(localTagName(root.nodeName).equals("gpx", ignoreCase = true)) {
            "Root XML element is not <gpx>"
        }

        val rawPoints = mutableListOf<RawPoint>()
        collectPointsByTag(root, "trkpt", rawPoints)
        if (rawPoints.isEmpty()) {
            collectPointsByTag(root, "rtept", rawPoints)
        }
        if (rawPoints.isEmpty()) {
            collectPointsByTag(root, "wpt", rawPoints)
        }
        require(rawPoints.isNotEmpty()) { "No valid track or route points found in GPX file" }

        val baseTimeMs = rawPoints.firstOrNull { it.timestampMs != null }?.timestampMs ?: 1_700_000_000_000L
        var runningTimeMs = baseTimeMs

        val fixes = ArrayList<LocationFix>(rawPoints.size)
        for (i in rawPoints.indices) {
            val cur = rawPoints[i]
            val ts = when {
                cur.timestampMs != null -> {
                    runningTimeMs = maxOf(runningTimeMs, cur.timestampMs)
                    cur.timestampMs
                }
                i == 0 -> baseTimeMs
                else -> {
                    runningTimeMs += 1_000L
                    runningTimeMs
                }
            }

            val prev = if (i > 0) fixes.lastOrNull() else null
            val next = rawPoints.getOrNull(i + 1)
            val computedBearing = cur.bearingDegrees ?: when {
                next != null && GeoMath.haversineMeters(cur.pos, next.pos) > 0.5 ->
                    GeoMath.initialBearingDegrees(cur.pos, next.pos).toFloat()
                prev != null && GeoMath.haversineMeters(prev.position, cur.pos) > 0.5 ->
                    GeoMath.initialBearingDegrees(prev.position, cur.pos).toFloat()
                else -> null
            }
            val computedSpeed = cur.speedMps ?: if (prev != null) {
                val dtSec = ((ts - prev.timestampMs).coerceAtLeast(100L)) / 1000.0
                val dist = GeoMath.haversineMeters(prev.position, cur.pos)
                (dist / dtSec).toFloat().coerceIn(0f, 70f)
            } else {
                0f
            }

            fixes += LocationFix(
                position = cur.pos,
                accuracyMeters = cur.accuracyMeters,
                speedMps = computedSpeed,
                bearingDegrees = computedBearing?.let { ((it % 360f) + 360f) % 360f },
                timestampMs = ts,
                elapsedRealtimeNanos = (ts - baseTimeMs).coerceAtLeast(0L) * 1_000_000L,
                provider = LocationProviderKind.GPX_REPLAY
            )
        }
        fixes
    }

    private data class RawPoint(
        val pos: LatLng,
        val timestampMs: Long?,
        val accuracyMeters: Float,
        val speedMps: Float?,
        val bearingDegrees: Float?
    )

    private fun collectPointsByTag(
        root: Element,
        targetTag: String,
        out: MutableList<RawPoint>
    ) {
        val nodes = root.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val elem = node as Element
            if (!localTagName(elem.nodeName).equals(targetTag, ignoreCase = true)) continue

            val lat = elem.getAttribute("lat").toDoubleOrNull() ?: continue
            val lon = elem.getAttribute("lon").toDoubleOrNull() ?: continue
            if (!LocationArbitrator.isValidLatLon(lat, lon)) continue

            val timeText = findFirstDescendantText(elem, "time")
            val timestampMs = timeText?.let(::parseIsoTimestamp)

            val explicitAcc = findFirstDescendantText(elem, "accuracy")?.toFloatOrNull()
            val hdop = findFirstDescendantText(elem, "hdop")?.toFloatOrNull()
            val accuracy = when {
                explicitAcc != null && explicitAcc.isFinite() -> explicitAcc.coerceIn(1f, 5_000f)
                hdop != null && hdop.isFinite() -> (hdop * 5.0f).coerceIn(3f, 200f)
                else -> DEFAULT_ACCURACY_METERS
            }

            val speedMps = findFirstDescendantText(elem, "speed")
                ?.toFloatOrNull()
                ?.takeIf { it.isFinite() && it >= 0f }

            val bearingDeg = (findFirstDescendantText(elem, "course")
                ?: findFirstDescendantText(elem, "bearing"))
                ?.toFloatOrNull()
                ?.takeIf { it.isFinite() }

            out += RawPoint(
                pos = LatLng(lat, lon),
                timestampMs = timestampMs,
                accuracyMeters = accuracy,
                speedMps = speedMps,
                bearingDegrees = bearingDeg
            )
        }
    }

    private fun findFirstDescendantText(parent: Element, tag: String): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType != Node.ELEMENT_NODE) continue
            val childElem = child as Element
            val name = localTagName(childElem.nodeName)
            if (name.equals(tag, ignoreCase = true)) {
                return childElem.textContent?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (name.equals("extensions", ignoreCase = true)) {
                findFirstDescendantText(childElem, tag)?.let { return it }
            }
        }
        return null
    }

    private fun localTagName(nodeName: String): String {
        val idx = nodeName.indexOf(':')
        return if (idx >= 0 && idx + 1 < nodeName.length) nodeName.substring(idx + 1) else nodeName
    }

    private fun parseIsoTimestamp(raw: String): Long? {
        val trimmed = raw.trim()
        return runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun formatIsoTimestamp(epochMs: Long): String {
        return ISO_FORMATTER.format(Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC))
    }

    private fun escapeXml(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private const val DEFAULT_ACCURACY_METERS = 12f
    private val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
}
