package com.kiransaj.diyamonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoreTest {

    @Test
    fun `extractor measures flame area and warmth`() {
        val m = MetricsExtractor().extract(SyntheticFrames.frame(20.0), SyntheticFrames.ROI, 0)
        // Ellipse area = pi * 20 * 32 ~= 2011 px of a 100x100 ROI.
        assertEquals(20.1, m.areaPct, 1.0)
        assertEquals(42.0, m.warmth, 0.01)
        assertEquals(70.0, m.aboveLuma, 0.01)
    }

    @Test
    fun `calibration fails when no flame is visible`() {
        val c = Calibrator()
        repeat(20) { c.add(MetricsExtractor().extract(SyntheticFrames.frame(0.0), SyntheticFrames.ROI, it * 2000L)) }
        try {
            c.build()
            fail("expected CalibrationException")
        } catch (e: CalibrationException) {
            assertTrue(e.message!!.contains("No flame"))
        }
    }

    @Test
    fun `rotation maps upright rect to raw buffer`() {
        val r = NormRect(0.1f, 0.2f, 0.3f, 0.6f)
        assertEquals(r, Rotation.uprightRectToRaw(r, 0))
        // Back camera in portrait: raw is landscape, rotated 90 deg clockwise to be upright.
        assertEquals(NormRect(0.2f, 0.7f, 0.6f, 0.9f).toString(), Rotation.uprightRectToRaw(r, 90).rounded().toString())
        assertEquals(NormRect(0.7f, 0.4f, 0.9f, 0.8f).toString(), Rotation.uprightRectToRaw(r, 180).rounded().toString())
        assertEquals(NormRect(0.4f, 0.1f, 0.8f, 0.3f).toString(), Rotation.uprightRectToRaw(r, 270).rounded().toString())
    }

    @Test
    fun `cooldown gate`() {
        val g = CooldownGate(1000)
        assertTrue(g.tryFire(AlertType.BATTERY_LOW, 0))
        assertFalse(g.tryFire(AlertType.BATTERY_LOW, 500))
        assertTrue(g.tryFire(AlertType.BATTERY_HOT, 500))
        assertTrue(g.tryFire(AlertType.BATTERY_LOW, 1000))
    }

    private fun NormRect.rounded() = NormRect(r(left), r(top), r(right), r(bottom))
    private fun r(f: Float) = Math.round(f * 100) / 100f
}
