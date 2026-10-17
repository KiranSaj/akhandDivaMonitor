package com.kiransaj.diyamonitor.core

import kotlin.math.max

enum class FlameStatus { OK, WARNING, URGENT }

/** Derived, human-readable view of the flame at one moment. */
data class Snapshot(
    val metrics: FrameMetrics,
    /** Flame area as a fraction of baseline (1.0 = same as calibrated). */
    val areaRatio: Double,
    /** ROI brightness as a fraction of baseline. */
    val brightnessRatio: Double,
    /** Std-dev of area over the flicker window, divided by the baseline area. */
    val flickerScore: Double,
    /** Fitted decline of flame area over the low-oil window (0.3 = 30%), null until enough data. */
    val trendDecline: Double?,
    /** Zone-above-flame luma as a fraction of baseline. */
    val aboveRatio: Double,
    val sootReasons: List<String>,
    val activeAlerts: Set<AlertType>,
    val status: FlameStatus,
) {
    fun describe(): String = buildString {
        append("Flame area: ${pct(areaRatio)} of baseline (${"%.2f".format(metrics.areaPct)}% of box)\n")
        append("Brightness: ${pct(brightnessRatio)} of baseline\n")
        append("Flicker score: ${"%.2f".format(flickerScore)}\n")
        if (trendDecline != null) append("30-min trend: ${if (trendDecline >= 0) "−" else "+"}${pct(kotlin.math.abs(trendDecline))}\n")
        append("Above-flame zone: ${pct(aboveRatio)} of baseline\n")
        append("Status: $status")
    }

    companion object {
        fun pct(v: Double) = "${(v * 100).toInt()}%"
    }
}

sealed class DetectionEvent {
    abstract val type: AlertType
    abstract val snapshot: Snapshot
    abstract fun message(): String

    data class Alert(override val type: AlertType, override val snapshot: Snapshot, val reminder: Boolean = false) : DetectionEvent() {
        override fun message(): String = buildString {
            append(type.title)
            if (reminder) append(" (still)")
            append("\n")
            append(advice(type, snapshot))
            append("\n\n")
            append(snapshot.describe())
        }
    }

    data class Recovered(override val type: AlertType, override val snapshot: Snapshot) : DetectionEvent() {
        override fun message(): String = "✅ Recovered: ${type.name.lowercase().replace('_', ' ')} condition cleared.\n\n${snapshot.describe()}"
    }

    companion object {
        fun advice(type: AlertType, s: Snapshot): String = when (type) {
            AlertType.FLAME_OUT -> "Bright flame area has been below the threshold for a while. Check the diya."
            AlertType.DIMMING -> "Flame area or brightness well below normal for 2+ minutes."
            AlertType.LOW_OIL -> "Flame has been shrinking steadily (${s.trendDecline?.let { Snapshot.pct(it) } ?: "?"} over 30 min). Consider topping up the oil."
            AlertType.SOOT -> "Reason: ${s.sootReasons.joinToString("; ")}. Consider trimming the wick."
            else -> ""
        }
    }
}

/**
 * Pure detection logic. Feed it one [FrameMetrics] every ~2 s via [process]; it compares
 * against the calibrated [Baseline] over rolling windows and emits alerts / recoveries.
 */
class FlameAnalyzer(
    val baseline: Baseline,
    val config: DetectionConfig = DetectionConfig(),
) {
    private val history = ArrayDeque<FrameMetrics>()
    private val conditionSince = mutableMapOf<AlertType, Long>()
    private val clearSince = mutableMapOf<AlertType, Long>()
    private val active = mutableSetOf<AlertType>()
    private val notified = mutableSetOf<AlertType>()
    private val cooldown = CooldownGate(config.cooldownMs)
    private val lastAlertAt = mutableMapOf<AlertType, Long>()

    var lastSnapshot: Snapshot? = null
        private set

    val activeAlerts: Set<AlertType> get() = active.toSet()

    fun process(m: FrameMetrics): List<DetectionEvent> {
        val now = m.timestampMs
        history.addLast(m)
        val keepMs = max(config.lowOilWindowMs, config.flickerWindowMs) + 60_000
        while (history.isNotEmpty() && now - history.first().timestampMs > keepMs) history.removeFirst()

        val areaRatio = ratio(m.areaPct, baseline.areaPct)
        val brightnessRatio = ratio(m.brightness, baseline.brightness)
        val aboveRatio = ratio(m.aboveLuma, baseline.aboveLuma)
        val flicker = flickerScore(now)
        val decline = trendDecline(now)

        val out = areaRatio < config.outFraction
        val dim = !out && (areaRatio < config.dimFraction || brightnessRatio < config.dimFraction)

        val sootReasons = mutableListOf<String>()
        if (!out) {
            if (areaRatio > config.oversizeFraction) sootReasons += "flame ${Snapshot.pct(areaRatio)} of normal size (wick too high?)"
            if (flicker != null && flicker > max(baseline.flickerCv * config.flickerFactor, config.flickerMinCv)) {
                sootReasons += "heavy flicker (score ${"%.2f".format(flicker)} vs normal ${"%.2f".format(baseline.flickerCv)})"
            }
            // The zone above is partly lit by the flame itself, so only judge it while the flame is healthy.
            if (baseline.aboveLuma >= 15 && areaRatio >= config.dimFraction && aboveRatio < config.aboveDarkenFraction) {
                sootReasons += "area above the flame darkened to ${Snapshot.pct(aboveRatio)} (smoke/soot?)"
            }
        }
        val lowOil = !out && decline != null && decline > config.lowOilDecline

        val conditions = mapOf(
            AlertType.FLAME_OUT to (out to config.outDurationMs),
            AlertType.DIMMING to (dim to config.dimDurationMs),
            AlertType.SOOT to (sootReasons.isNotEmpty() to config.sootDurationMs),
            AlertType.LOW_OIL to (lowOil to 0L),
        )

        val pending = mutableListOf<Pair<AlertType, Boolean>>() // type, isReminder
        val recovered = mutableListOf<AlertType>()
        for ((type, pair) in conditions) {
            val (cond, duration) = pair
            if (cond) {
                clearSince.remove(type)
                val since = conditionSince.getOrPut(type) { now }
                if (type !in active && now - since >= duration) active += type
                if (type in active) {
                    // A new urgent incident (e.g. out again after a relight) must not wait for the cooldown.
                    if (type !in notified && (type.severity == Severity.URGENT || cooldown.canFire(type, now))) {
                        pending += type to false
                    } else if (type in notified && type.repeatWhileActive &&
                        now - (lastAlertAt[type] ?: now) >= config.cooldownMs
                    ) {
                        pending += type to true
                    }
                }
            } else {
                conditionSince.remove(type)
                if (type in active) {
                    val since = clearSince.getOrPut(type) { now }
                    if (now - since >= config.recoveryMs) {
                        active -= type
                        clearSince.remove(type)
                        if (notified.remove(type)) recovered += type
                    }
                }
            }
        }

        val status = when {
            AlertType.FLAME_OUT in active || out -> FlameStatus.URGENT
            active.isNotEmpty() || dim || sootReasons.isNotEmpty() || lowOil -> FlameStatus.WARNING
            else -> FlameStatus.OK
        }
        val snapshot = Snapshot(m, areaRatio, brightnessRatio, flicker ?: 0.0, decline, aboveRatio, sootReasons, active.toSet(), status)
        lastSnapshot = snapshot

        val events = mutableListOf<DetectionEvent>()
        for ((type, reminder) in pending) {
            cooldown.markFired(type, now)
            lastAlertAt[type] = now
            notified += type
            events += DetectionEvent.Alert(type, snapshot, reminder)
        }
        recovered.forEach { events += DetectionEvent.Recovered(it, snapshot) }
        return events
    }

    private fun ratio(value: Double, base: Double) = if (base <= 0.0) 0.0 else value / base

    /** Std-dev of area over the flicker window / baseline area; null until the window is mostly filled. */
    private fun flickerScore(now: Long): Double? {
        val window = history.filter { now - it.timestampMs <= config.flickerWindowMs }
        if (window.size < 5 || now - window.first().timestampMs < config.flickerWindowMs * 0.7) return null
        return Stats.stdDev(window.map { it.areaPct }) / baseline.areaPct
    }

    /**
     * Linear regression of flame area over the low-oil window. Returns the fitted decline
     * (start - end) / start, or null until the window is at least 80% covered.
     */
    private fun trendDecline(now: Long): Double? {
        val window = history.filter { now - it.timestampMs <= config.lowOilWindowMs && ratio(it.areaPct, baseline.areaPct) >= config.outFraction }
        if (window.size < 20) return null
        val span = window.last().timestampMs - window.first().timestampMs
        if (span < config.lowOilWindowMs * 0.8) return null
        val t0 = window.first().timestampMs
        val fit = Stats.linearRegression(
            window.map { (it.timestampMs - t0).toDouble() },
            window.map { it.areaPct },
        ) ?: return null
        val (intercept, slope) = fit
        if (intercept <= 0) return null
        return -(slope * span) / intercept
    }
}
