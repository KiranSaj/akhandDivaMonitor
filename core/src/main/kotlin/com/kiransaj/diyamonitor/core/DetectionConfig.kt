package com.kiransaj.diyamonitor.core

/** Thresholds and timings for [FlameAnalyzer]. Ratios are relative to the calibrated baseline. */
data class DetectionConfig(
    /** Flame out: area below this fraction of baseline... */
    val outFraction: Double = 0.10,
    /** ...for this long. */
    val outDurationMs: Long = 30_000,
    /** Dimming: area or brightness below this fraction of baseline... */
    val dimFraction: Double = 0.60,
    /** ...for this long. */
    val dimDurationMs: Long = 120_000,
    /** Soot: flame area above this fraction of baseline (wick too high). */
    val oversizeFraction: Double = 1.60,
    /** Window over which flicker (std-dev of area) is measured. */
    val flickerWindowMs: Long = 30_000,
    /** Flicker alert when the window's flicker is this many times the calibrated flicker... */
    val flickerFactor: Double = 3.0,
    /** ...and at least this absolute value (std-dev / baseline area). */
    val flickerMinCv: Double = 0.15,
    /** Soot: zone above the flame darker than this fraction of its baseline luma. */
    val aboveDarkenFraction: Double = 0.75,
    /** A soot condition must persist this long before alerting. */
    val sootDurationMs: Long = 30_000,
    /** Low oil: window for the trend regression. */
    val lowOilWindowMs: Long = 30 * 60_000L,
    /** Low oil: fitted decline over the window that triggers the alert (0.30 = 30%). */
    val lowOilDecline: Double = 0.30,
    /** A condition must be clear this long before "recovered" is sent. */
    val recoveryMs: Long = 30_000,
    /** Minimum time between two alerts of the same type. */
    val cooldownMs: Long = 15 * 60_000L,
) {
    /** Shrinks all timings (used by quiet test mode so you can try alerts quickly). */
    fun timeScaled(factor: Double): DetectionConfig {
        fun s(ms: Long) = (ms * factor).toLong().coerceAtLeast(1)
        return copy(
            outDurationMs = s(outDurationMs),
            dimDurationMs = s(dimDurationMs),
            flickerWindowMs = s(flickerWindowMs).coerceAtLeast(10_000),
            sootDurationMs = s(sootDurationMs),
            lowOilWindowMs = s(lowOilWindowMs),
            recoveryMs = s(recoveryMs),
            cooldownMs = s(cooldownMs),
        )
    }
}
