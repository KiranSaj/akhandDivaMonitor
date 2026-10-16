package com.kiransaj.diyamonitor.core

/** Typical flame metrics recorded during calibration. */
data class Baseline(
    val areaPct: Double,
    val brightness: Double,
    val aboveLuma: Double,
    /** Std-dev of flame area / mean area during calibration (normal flicker level). */
    val flickerCv: Double,
    val samples: Int,
)

class CalibrationException(message: String) : Exception(message)

class Calibrator {
    private val samples = mutableListOf<FrameMetrics>()

    val count: Int get() = samples.size

    fun add(m: FrameMetrics) {
        samples += m
    }

    fun reset() = samples.clear()

    /** Builds the baseline, or throws [CalibrationException] if the flame wasn't seen. */
    fun build(minSamples: Int = 10): Baseline {
        if (samples.size < minSamples) throw CalibrationException("Only ${samples.size} frames recorded; need $minSamples.")
        val areas = samples.map { it.areaPct }
        val meanArea = Stats.mean(areas)
        if (meanArea < 0.3) {
            throw CalibrationException(
                "No flame found in the box (bright area ${"%.2f".format(meanArea)}%). " +
                    "Draw the box tightly around the flame, or lower the bright-pixel threshold in Settings."
            )
        }
        return Baseline(
            areaPct = meanArea,
            brightness = Stats.mean(samples.map { it.brightness }),
            aboveLuma = Stats.mean(samples.map { it.aboveLuma }),
            flickerCv = Stats.stdDev(areas) / meanArea,
            samples = samples.size,
        )
    }
}
