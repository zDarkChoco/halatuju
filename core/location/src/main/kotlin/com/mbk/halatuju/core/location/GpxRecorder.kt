package com.mbk.halatuju.core.location

import android.content.Context
import com.mbk.halatuju.core.model.LocationFix
import com.mbk.halatuju.core.model.LocationProviderKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

data class GpxRecordingState(
    val isRecording: Boolean = false,
    val recordedFixCount: Int = 0,
    val lastExportedFileName: String? = null
)

/**
 * Records raw GPS/network fixes alongside timestamps for later desktop reproduction via GPX export/share.
 */
@Singleton
class GpxRecorder internal constructor(
    private val cacheDirProvider: () -> File
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this({ context.cacheDir })

    private val lock = Any()
    private val fixes = ArrayList<LocationFix>(256)

    private val _state = MutableStateFlow(GpxRecordingState())
    val state: StateFlow<GpxRecordingState> = _state.asStateFlow()

    fun setRecording(enabled: Boolean) {
        synchronized(lock) {
            _state.value = _state.value.copy(isRecording = enabled)
        }
    }

    fun recordFix(fix: LocationFix) {
        if (fix.provider == LocationProviderKind.DEAD_RECKONED) return
        synchronized(lock) {
            if (!_state.value.isRecording) return
            val last = fixes.lastOrNull()
            if (last != null &&
                last.timestampMs == fix.timestampMs &&
                last.provider == fix.provider &&
                last.position == fix.position
            ) {
                return
            }
            if (fixes.size >= MAX_RECORDED_POINTS) {
                fixes.removeAt(0)
            }
            fixes.add(fix)
            _state.value = _state.value.copy(recordedFixCount = fixes.size)
        }
    }

    fun clear() {
        synchronized(lock) {
            fixes.clear()
            _state.value = _state.value.copy(
                recordedFixCount = 0,
                lastExportedFileName = null
            )
        }
    }

    fun snapshotFixes(): List<LocationFix> = synchronized(lock) { fixes.toList() }

    fun exportGpxString(trackName: String = "HalaTuju Drive"): String {
        val snapshot = snapshotFixes()
        return GpxCodec.encode(fixes = snapshot, trackName = trackName)
    }

    fun exportToShareableGpxFile(nowMs: Long = System.currentTimeMillis()): Result<File> = runCatching {
        val snapshot = snapshotFixes()
        require(snapshot.isNotEmpty()) { "No recorded GPS fixes available to export" }
        val gpxDir = File(cacheDirProvider(), "gpx").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(nowMs))
        val outFile = File(gpxDir, "halatuju_trace_$stamp.gpx")
        outFile.writeText(GpxCodec.encode(snapshot, trackName = "HalaTuju $stamp"))
        synchronized(lock) {
            _state.value = _state.value.copy(lastExportedFileName = outFile.name)
        }
        outFile
    }

    companion object {
        private const val MAX_RECORDED_POINTS = 50_000
    }
}
