package com.kiransaj.diyamonitor.ui

import com.kiransaj.diyamonitor.core.Baseline
import com.kiransaj.diyamonitor.service.LiveReading
import com.kiransaj.diyamonitor.service.Mode

object Format {
    fun short(mode: Mode, live: LiveReading?): String {
        val s = live?.snapshot
        return when {
            mode == Mode.MONITORING && s != null -> "${s.status} · area ${(s.areaRatio * 100).toInt()}%"
            mode == Mode.CALIBRATING -> "calibrating ${((live?.calibrationProgress ?: 0f) * 100).toInt()}%"
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
        append("Flame area: %.2f%% of box".format(m.areaPct))
        if (baseline != null) append(" (%d%% of baseline)".format((m.areaPct / baseline.areaPct * 100).toInt()))
        append('\n')
        append("Brightness: %.0f".format(m.brightness))
        if (baseline != null && baseline.brightness > 0) append(" (%d%%)".format((m.brightness / baseline.brightness * 100).toInt()))
        append("   Warmth: %.0f".format(m.warmth))
        append('\n')
        append("Flicker score: ${if (s != null) "%.2f".format(s.flickerScore) else "–"}")
        if (baseline != null) append(" (normal %.2f)".format(baseline.flickerCv))
        s?.trendDecline?.let { append("\n30-min trend: %+d%%".format((-it * 100).toInt())) }
        if (s != null && s.activeAlerts.isNotEmpty()) append("\nActive: ${s.activeAlerts.joinToString { it.name }}")
        live?.calibrationProgress?.let { append("\nCalibrating: ${(it * 100).toInt()}%") }
        if (baseline == null) append("\nNot calibrated yet.")
    }
}
