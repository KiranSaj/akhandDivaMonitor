package com.kiransaj.diyamonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** A dark scene with two flames (white core, orange edge) and a dimmer, coreless reflection below each. */
object LampFrames {
    const val W = 240
    const val H = 320

    data class Flame(val x: Double, val y: Double, val radius: Double)

    fun frame(flames: List<Flame>, reflections: Boolean = true, shiftX: Double = 0.0, shiftY: Double = 0.0): LumaFrame {
        val luma = ByteArray(W * H) { 12 }
        val v = ByteArray(W * H) { 128.toByte() }
        fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double, paint: (Int, Double) -> Unit) {
            if (rx <= 0) return
            for (y in (cy - ry).toInt()..(cy + ry).toInt() + 1) for (x in (cx - rx).toInt()..(cx + rx).toInt() + 1) {
                if (x !in 0 until W || y !in 0 until H) continue
                val d = ((x - cx) / rx).let { it * it } + ((y - cy) / ry).let { it * it }
                if (d <= 1.0) paint(y * W + x, d)
            }
        }
        for (f in flames) {
            val cx = f.x + shiftX
            val cy = f.y + shiftY
            if (reflections) {
                ellipse(cx, cy + 50, f.radius * 0.8, f.radius * 1.0) { p, _ -> luma[p] = 215.toByte(); v[p] = 160.toByte() }
            }
            ellipse(cx, cy, f.radius, f.radius * 1.8) { p, d ->
                luma[p] = (if (d < 0.4) 252 else 215).toByte()
                v[p] = (if (d < 0.4) 132 else 170).toByte()
            }
        }
        return LumaFrame(W, H, luma, v)
    }

    val TWO = listOf(Flame(80.0, 150.0, 7.0), Flame(170.0, 140.0, 7.0))
}

class FlameRegionsTest {
    private val detector = FlameDetector()

    @Test
    fun `detector separates flames and reflections`() {
        val blobs = detector.detect(LampFrames.frame(LampFrames.TWO))
        assertEquals(4, blobs.size)
        val flames = blobs.filter { it.coreFraction > 0.2 }
        assertEquals(2, flames.size)
        val left = flames.minByOrNull { it.cx }!!
        assertEquals(80.5 / LampFrames.W, left.cx.toDouble(), 0.01)
        assertTrue("flame core is near white", left.warmth < 40)
    }

    @Test
    fun `finder picks the two flickering flames, not their reflections`() {
        val rnd = Random(3)
        val finder = FlameFinder(expected = 2)
        repeat(20) {
            val flicker = LampFrames.TWO.map { it.copy(radius = it.radius + rnd.nextDouble(-1.5, 1.5)) }
            finder.add(detector.detect(LampFrames.frame(flicker)))
        }
        val regions = finder.flames()
        assertEquals(2, regions.size)
        for (f in LampFrames.TWO) {
            val x = (f.x / LampFrames.W).toFloat()
            val y = (f.y / LampFrames.H).toFloat()
            val reflectionY = ((f.y + 50) / LampFrames.H).toFloat()
            assertTrue("a region covers the flame at ${f.x}", regions.any { it.contains(x, y) })
            assertFalse("no region is centred on the reflection", regions.any { it.contains(x, reflectionY) && !it.contains(x, y) })
        }
        assertTrue("regions do not overlap", regions[0].box.right <= regions[1].box.left)
    }

    @Test
    fun `finder returns fewer flames when fewer are lit`() {
        val finder = FlameFinder(expected = 2)
        repeat(10) { finder.add(detector.detect(LampFrames.frame(LampFrames.TWO.take(1), reflections = false))) }
        assertEquals(1, finder.flames().size)
    }

    @Test
    fun `finder ignores a blob that appears only briefly`() {
        val finder = FlameFinder(expected = 2)
        repeat(10) { i ->
            val flames = if (i < 3) LampFrames.TWO else LampFrames.TWO.take(1)
            finder.add(detector.detect(LampFrames.frame(flames, reflections = false)))
        }
        assertEquals(1, finder.flames().size)
    }

    @Test
    fun `tracker follows slow drift`() {
        val finder = FlameFinder(expected = 2)
        repeat(10) { finder.add(detector.detect(LampFrames.frame(LampFrames.TWO))) }
        var regions = finder.flames()
        // The phone slowly slides 20 px to the right and 10 px down.
        for (step in 1..60) {
            val shift = minOf(step, 20).toDouble()
            val result = FlameTracker.follow(regions, detector.detect(LampFrames.frame(LampFrames.TWO, shiftX = shift, shiftY = shift / 2)))
            assertTrue("both flames found at step $step", result.found.all { it })
            regions = result.regions
        }
        val leftAnchor = regions.minByOrNull { it.anchorX }!!.anchorX
        assertEquals((80.5 + 20) / LampFrames.W, leftAnchor.toDouble(), 0.01)
    }

    @Test
    fun `move detector fires when all flames reappear elsewhere`() {
        val finder = FlameFinder(expected = 2)
        repeat(10) { finder.add(detector.detect(LampFrames.frame(LampFrames.TWO, reflections = false))) }
        val regions = finder.flames()
        val moved = MoveDetector(holdMs = 30_000)
        var fired = false
        for (t in 0..40_000L step 2_000) {
            val result = FlameTracker.follow(regions, detector.detect(LampFrames.frame(LampFrames.TWO, reflections = false, shiftY = 110.0)))
            assertTrue(result.found.none { it })
            if (moved.update(result, t)) fired = true
            if (t < 30_000) assertFalse("not before 30 s", fired)
        }
        assertTrue(fired)
    }

    @Test
    fun `one flame going out is not a move`() {
        val finder = FlameFinder(expected = 2)
        repeat(10) { finder.add(detector.detect(LampFrames.frame(LampFrames.TWO, reflections = false))) }
        val regions = finder.flames()
        val moved = MoveDetector(holdMs = 30_000)
        for (t in 0..60_000L step 2_000) {
            val result = FlameTracker.follow(regions, detector.detect(LampFrames.frame(LampFrames.TWO.take(1), reflections = false)))
            assertFalse(moved.update(result, t))
        }
    }

    @Test
    fun `combined reading weights flames by box size`() {
        val a = FrameMetrics(0, areaPct = 20.0, brightness = 100.0, aboveLuma = 30.0, warmth = 40.0, heightPct = 50.0)
        val b = FrameMetrics(0, areaPct = 0.0, brightness = 20.0, aboveLuma = 10.0, warmth = 0.0, heightPct = 0.0)
        val c = FrameMetrics.combine(listOf(a, b), listOf(1.0, 3.0), 5)
        assertEquals(5.0, c.areaPct, 1e-9)
        assertEquals(40.0, c.brightness, 1e-9)
        assertEquals(40.0, c.warmth, 1e-9) // only the lit flame has colour
        assertEquals(50.0, c.heightPct, 1e-9)
        assertEquals(5L, c.timestampMs)
    }

    @Test
    fun `extractor measures flame height`() {
        val m = MetricsExtractor().extract(SyntheticFrames.frame(20.0), SyntheticFrames.ROI, 0)
        // Ellipse is 2 * 1.6 * 20 = 64 px tall inside a 100 px tall ROI.
        assertEquals(65.0, m.heightPct, 2.0)
    }
}
