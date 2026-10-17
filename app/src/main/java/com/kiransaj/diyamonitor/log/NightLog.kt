package com.kiransaj.diyamonitor.log

import android.content.Context
import android.util.Log
import com.kiransaj.diyamonitor.core.Blob
import com.kiransaj.diyamonitor.core.FrameMetrics
import com.kiransaj.diyamonitor.core.Snapshot
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One monitoring session on disk: `log.csv` with a row per check, plus JPEG snapshots.
 * Used to tune the detection from real nights. Not thread-safe: use from the analysis thread.
 */
class NightLog private constructor(val dir: File) {

    private val writer: BufferedWriter = File(dir, "log.csv").bufferedWriter()
    private val pendingEvents = mutableListOf<String>()
    private var rowsSinceFlush = 0
    private var lastSnapshotMs = 0L
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileTime = SimpleDateFormat("HHmmss", Locale.US)

    init {
        writer.write(HEADER)
        writer.newLine()
        writer.flush()
    }

    /** Notes something that happened; it goes into the `events` column of the next row. */
    fun event(text: String) {
        pendingEvents += text.replace(',', ' ').replace(';', ' ').replace('\n', ' ')
    }

    fun row(
        mode: String,
        combined: FrameMetrics,
        snapshot: Snapshot?,
        flames: List<FrameMetrics>,
        flameRatios: List<Double>,
        blobs: List<Blob>,
        battery: BatteryInfo?,
        screenOn: Boolean,
    ) {
        val cells = mutableListOf<String>()
        cells += time.format(Date(combined.timestampMs))
        cells += combined.timestampMs.toString()
        cells += mode
        cells += snapshot?.status?.name.orEmpty()
        cells += f(snapshot?.areaRatio)
        cells += f(snapshot?.brightnessRatio)
        cells += f(snapshot?.flickerScore)
        cells += f(snapshot?.trendDecline)
        cells += f(combined.areaPct)
        for (i in 0 until MAX_FLAMES) {
            val m = flames.getOrNull(i)
            cells += f(m?.areaPct)
            cells += f(m?.heightPct)
            cells += f(m?.brightness)
            cells += f(m?.warmth)
            cells += f(flameRatios.getOrNull(i))
        }
        // Up to 6 largest blobs as x:y:pixels:core% (normalised centre), for tuning flame/fire detection.
        cells += blobs.take(6).joinToString(" ") { "%.3f:%.3f:%d:%d".format(Locale.US, it.cx, it.cy, it.pixels, (it.coreFraction * 100).toInt()) }
        cells += battery?.percent?.toString().orEmpty()
        cells += battery?.let { if (it.plugged) "1" else "0" }.orEmpty()
        cells += battery?.let { "%.1f".format(Locale.US, it.tempC) }.orEmpty()
        cells += if (screenOn) "1" else "0"
        cells += snapshot?.activeAlerts?.joinToString(" ") { it.name }.orEmpty()
        cells += pendingEvents.joinToString("; ")
        pendingEvents.clear()
        try {
            writer.write(cells.joinToString(","))
            writer.newLine()
            if (++rowsSinceFlush >= FLUSH_EVERY_ROWS) flush()
        } catch (e: Exception) {
            Log.w(TAG, "log write failed", e)
        }
    }

    /** Saves a JPEG now if [force], or if the last periodic one is older than [SNAPSHOT_EVERY_MS]. */
    fun snapshot(nowMs: Long, tag: String, force: Boolean, jpeg: () -> ByteArray?) {
        if (!force && nowMs - lastSnapshotMs < SNAPSHOT_EVERY_MS) return
        if (!force) lastSnapshotMs = nowMs
        val bytes = jpeg() ?: return
        runCatching { File(dir, "${fileTime.format(Date(nowMs))}-$tag.jpg").writeBytes(bytes) }
    }

    fun flush() {
        rowsSinceFlush = 0
        runCatching { writer.flush() }
    }

    fun close() {
        runCatching { writer.close() }
    }

    private fun f(v: Double?) = if (v == null || v.isNaN()) "" else "%.3f".format(Locale.US, v)

    data class BatteryInfo(val percent: Int, val plugged: Boolean, val tempC: Double)

    companion object {
        private const val TAG = "NightLog"
        const val MAX_FLAMES = 3
        private const val KEEP_SESSIONS = 7
        private const val FLUSH_EVERY_ROWS = 15
        const val SNAPSHOT_EVERY_MS = 15 * 60_000L

        private val HEADER = buildString {
            append("time,epoch_ms,mode,status,area_ratio,brightness_ratio,flicker,trend_decline,area_pct")
            for (i in 1..MAX_FLAMES) append(",f${i}_area_pct,f${i}_height_pct,f${i}_brightness,f${i}_warmth,f${i}_ratio")
            append(",blobs,battery_pct,plugged,battery_c,screen_on,active_alerts,events")
        }

        private fun root(context: Context) = File(context.filesDir, "logs")

        /** Starts a new session folder and deletes all but the newest [KEEP_SESSIONS]. */
        fun start(context: Context, startMs: Long): NightLog {
            val root = root(context).apply { mkdirs() }
            val name = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(startMs))
            val dir = File(root, name).apply { mkdirs() }
            root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.drop(KEEP_SESSIONS)
                ?.forEach { it.deleteRecursively() }
            return NightLog(dir)
        }

        fun latestSession(context: Context): File? =
            root(context).listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }

        /** Zips a session folder into the cache, ready to send. */
        fun zip(context: Context, session: File): File {
            val out = File(context.cacheDir, "diya-log-${session.name}.zip")
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                session.listFiles()?.sortedBy { it.name }?.forEach { file ->
                    zip.putNextEntry(ZipEntry("${session.name}/${file.name}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            return out
        }
    }
}
