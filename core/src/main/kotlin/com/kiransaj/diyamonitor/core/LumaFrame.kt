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
)

class MetricsExtractor(var brightThreshold: Int = 200) {

    fun extract(frame: LumaFrame, roi: NormRect, timestampMs: Long): FrameMetrics {
        val (x0, y0, x1, y1) = pixelBounds(frame, roi)
        var total = 0
        var bright = 0
        var sumLuma = 0L
        var sumWarmth = 0L
        val v = frame.chromaV
        for (y in y0 until y1) {
            val row = y * frame.width
            for (x in x0 until x1) {
                val l = frame.luma[row + x].toInt() and 0xFF
                sumLuma += l
                total++
                if (l >= brightThreshold) {
                    bright++
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
