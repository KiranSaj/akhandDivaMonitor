package com.kiransaj.diyamonitor.core

/**
 * An upright greyscale frame (row-major, one unsigned byte per pixel).
 * [chromaV] optionally holds the V (Cr, "redness") channel at the same resolution;
 * it is used to derive the flame colour (warmth).
 */
class LumaFrame(
    val width: Int,
    val height: Int,
    val luma: ByteArray,
    val chromaV: ByteArray? = null,
) {
    init {
        require(luma.size >= width * height) { "luma buffer too small" }
        require(chromaV == null || chromaV.size >= width * height) { "chroma buffer too small" }
    }

    fun lumaAt(x: Int, y: Int): Int = luma[y * width + x].toInt() and 0xFF
}

/** Metrics measured from one frame. */
data class FrameMetrics(
    val timestampMs: Long,
    /** Percentage (0..100) of the ROI covered by bright "flame" pixels. */
    val areaPct: Double,
    /** Mean luma (0..255) of the whole ROI. */
    val brightness: Double,
    /** Mean luma of the zone just above the ROI (soot/smoke zone). */
    val aboveLuma: Double,
    /** Mean (V - 128) of flame pixels: higher = more orange/red, ~0 = white/yellow. */
    val warmth: Double,
    /** Height of the bright pixels (top to bottom) as a percentage of the ROI height. */
    val heightPct: Double = 0.0,
) {
    companion object {
        /** Combines per-flame readings into one for the whole lamp, weighting each by its box size. */
        fun combine(parts: List<FrameMetrics>, boxAreas: List<Double>, timestampMs: Long): FrameMetrics {
            require(parts.size == boxAreas.size) { "one box area per reading" }
            val total = boxAreas.sum()
            if (parts.isEmpty() || total <= 0) return FrameMetrics(timestampMs, 0.0, 0.0, 0.0, 0.0)
            fun weighted(f: (FrameMetrics) -> Double) = parts.indices.sumOf { f(parts[it]) * boxAreas[it] } / total
            val brightWeights = parts.indices.map { parts[it].areaPct * boxAreas[it] }
            val brightTotal = brightWeights.sum()
            return FrameMetrics(
                timestampMs = timestampMs,
                areaPct = weighted { it.areaPct },
                brightness = weighted { it.brightness },
                aboveLuma = weighted { it.aboveLuma },
                warmth = if (brightTotal <= 0) 0.0 else parts.indices.sumOf { parts[it].warmth * brightWeights[it] } / brightTotal,
                heightPct = parts.maxOf { it.heightPct },
            )
        }
    }
}

class MetricsExtractor(var brightThreshold: Int = 200) {

    fun extract(frame: LumaFrame, roi: NormRect, timestampMs: Long): FrameMetrics {
        val (x0, y0, x1, y1) = pixelBounds(frame, roi)
        var total = 0
        var bright = 0
        var sumLuma = 0L
        var sumWarmth = 0L
        var topBright = -1
        var bottomBright = -1
        val v = frame.chromaV
        for (y in y0 until y1) {
            val row = y * frame.width
            for (x in x0 until x1) {
                val l = frame.luma[row + x].toInt() and 0xFF
                sumLuma += l
                total++
                if (l >= brightThreshold) {
                    bright++
                    if (topBright < 0) topBright = y
                    bottomBright = y
                    if (v != null) sumWarmth += (v[row + x].toInt() and 0xFF) - 128
                }
            }
        }
        val above = roi.aboveZone()
        var aboveSum = 0L
        var aboveCount = 0
        if (above.height > 0f) {
            val (ax0, ay0, ax1, ay1) = pixelBounds(frame, above)
            for (y in ay0 until ay1) {
                val row = y * frame.width
                for (x in ax0 until ax1) {
                    aboveSum += frame.luma[row + x].toInt() and 0xFF
                    aboveCount++
                }
            }
        }
        return FrameMetrics(
            timestampMs = timestampMs,
            areaPct = if (total == 0) 0.0 else bright * 100.0 / total,
            brightness = if (total == 0) 0.0 else sumLuma.toDouble() / total,
            aboveLuma = if (aboveCount == 0) 0.0 else aboveSum.toDouble() / aboveCount,
            warmth = if (bright == 0) 0.0 else sumWarmth.toDouble() / bright,
            heightPct = if (bright == 0 || y1 <= y0) 0.0 else (bottomBright - topBright + 1) * 100.0 / (y1 - y0),
        )
    }

    private fun pixelBounds(frame: LumaFrame, r: NormRect): IntArray {
        val c = r.clamped()
        val x0 = (c.left * frame.width).toInt().coerceIn(0, frame.width)
        val y0 = (c.top * frame.height).toInt().coerceIn(0, frame.height)
        val x1 = (c.right * frame.width).toInt().coerceIn(x0, frame.width)
        val y1 = (c.bottom * frame.height).toInt().coerceIn(y0, frame.height)
        return intArrayOf(x0, y0, x1, y1)
    }
}
