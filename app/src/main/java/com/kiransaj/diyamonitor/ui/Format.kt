package com.kiransaj.diyamonitor.ui

import com.kiransaj.diyamonitor.core.Baseline
import com.kiransaj.diyamonitor.service.LiveReading
import com.kiransaj.diyamonitor.service.Mode

object Format {
    fun short(mode: Mode, live: LiveReading?): String {
        val s = live?.snapshot
        return when {
            mode == Mode.MONITORING && s != null -> "${s.status} · " + flames(live).ifEmpty { "area ${(s.areaRatio * 100).toInt()}%" }
            mode == Mode.FINDING -> "finding flames ${progress(live)}"
            mode == Mode.CALIBRATING -> "learning normal size ${progress(live)}"
            else -> mode.name.lowercase()
        }
    }

    fun long(mode: Mode, live: LiveReading?, baseline: Baseline?): String = buildString {
        val m = live?.metrics
        val s = live?.snapshot
        append("Mode: ${mode.name}")
        if (s != null) append("   •   Status: ${s.status}")
        append('\n')
        if (m == null) {
            append("Waiting for camera…")
            return@buildString
        }
        if (mode == Mode.FINDING || mode == Mode.CALIBRATING) {
            append(if (mode == Mode.FINDING) "Finding the flames… " else "Learning their normal size… ")
            append(progress(live))
            return@buildString
        }
        flames(live).takeIf { it.isNotEmpty() }?.let { append("Flames vs normal: $it\n") }
        append("Both flames: %.2f%% of boxes".format(m.areaPct))
        if (baseline != null && baseline.areaPct > 0) append(" (%d%% of normal)".format((m.areaPct / baseline.areaPct * 100).toInt()))
        append('\n')
        append("Brightness: %.0f   Warmth: %.0f   Height: %.0f%%".format(m.brightness, m.warmth, m.heightPct))
        append('\n')
        append("Flicker score: ${if (s != null) "%.2f".format(s.flickerScore) else "–"}")
        if (baseline != null) append(" (normal %.2f)".format(baseline.flickerCv))
        s?.trendDecline?.let { append("\n30-min trend: %+d%%".format((-it * 100).toInt())) }
        if (s != null && s.activeAlerts.isNotEmpty()) append("\nActive: ${s.activeAlerts.joinToString { it.name }}")
        if (baseline == null) append("\nNot set up yet: press Start (or Find flames).")
    }

    /** "1: 98%  2: 103%" */
    private fun flames(live: LiveReading?): String =
        live?.flameRatios.orEmpty().mapIndexed { i, r -> "${i + 1}: ${(r * 100).toInt()}%" }.joinToString("  ")

    private fun progress(live: LiveReading?) = "${((live?.calibrationProgress ?: 0f) * 100).toInt()}%"
}
