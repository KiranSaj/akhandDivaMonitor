package com.kiransaj.diyamonitor.core

/** One connected region of bright pixels in an upright frame. Coordinates are normalised (0..1). */
data class Blob(
    val box: NormRect,
    val cx: Float,
    val cy: Float,
    /** Number of bright pixels. */
    val pixels: Int,
    /** Pixels at or above the saturated-core threshold: real flames have a white-hot core, reflections rarely do. */
    val corePixels: Int,
    /** Mean (V - 128) of the bright pixels: higher = more orange/red. */
    val warmth: Double,
) {
    val coreFraction: Double get() = if (pixels == 0) 0.0 else corePixels.toDouble() / pixels
}

/** Finds connected bright regions (8-connectivity) in a frame. */
class FlameDetector(
    var brightThreshold: Int = 200,
    var coreThreshold: Int = 245,
    var minPixels: Int = 4,
) {
    private var visited = BooleanArray(0)
    private var stack = IntArray(0)

    /** Bright blobs, largest first. */
    fun detect(frame: LumaFrame): List<Blob> {
        val w = frame.width
        val h = frame.height
        val n = w * h
        if (visited.size < n) {
            visited = BooleanArray(n)
            stack = IntArray(n)
        } else {
            visited.fill(false, 0, n)
        }
        val luma = frame.luma
        val v = frame.chromaV
        val blobs = mutableListOf<Blob>()
        for (start in 0 until n) {
            if (visited[start] || (luma[start].toInt() and 0xFF) < brightThreshold) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var count = 0
            var core = 0
            var sumX = 0L
            var sumY = 0L
            var warm = 0L
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w
                val y = p / w
                val l = luma[p].toInt() and 0xFF
                count++
                if (l >= coreThreshold) core++
                sumX += x
                sumY += y
                if (v != null) warm += (v[p].toInt() and 0xFF) - 128
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w || (dx == 0 && dy == 0)) continue
                        val q = ny * w + nx
                        if (!visited[q] && (luma[q].toInt() and 0xFF) >= brightThreshold) {
                            visited[q] = true
                            stack[sp++] = q
                        }
                    }
                }
            }
            if (count < minPixels) continue
            blobs += Blob(
                box = NormRect(minX.toFloat() / w, minY.toFloat() / h, (maxX + 1).toFloat() / w, (maxY + 1).toFloat() / h),
                cx = (sumX.toFloat() / count + 0.5f) / w,
                cy = (sumY.toFloat() / count + 0.5f) / h,
                pixels = count,
                corePixels = core,
                warmth = if (v == null) 0.0 else warm.toDouble() / count,
            )
        }
        return blobs.sortedByDescending { it.pixels }
    }
}
