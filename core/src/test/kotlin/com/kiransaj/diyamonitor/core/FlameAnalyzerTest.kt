package com.kiransaj.diyamonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class FlameAnalyzerTest {

    @Test
    fun `normal steady flame produces no alerts for 3 hours`() {
        val s = Scenario()
        s.run(3 * 3600_000L) { 20.0 + s.rnd.nextDouble(-0.6, 0.6) }
        assertTrue("unexpected events: ${s.events}", s.events.isEmpty())
        assertEquals(FlameStatus.OK, s.analyzer.lastSnapshot!!.status)
        val ratio = s.analyzer.lastSnapshot!!.areaRatio
        assertTrue("area ratio $ratio", ratio in 0.9..1.1)
    }

    @Test
    fun `dimming flame raises warning after 2 minutes and recovers`() {
        val s = Scenario()
        s.run(60_000) { 20.0 }
        val start = s.nowMs
        // Radius 13 => area ~42% of baseline: dim but not out.
        s.run(5 * 60_000L) { 13.0 }
        val dim = s.alerts(AlertType.DIMMING)
        assertEquals(1, dim.size)
        val delay = dim.first().first - start
        assertTrue("dim alert after ${delay}ms", delay in 118_000..126_000)
        assertTrue(s.alerts(AlertType.FLAME_OUT).isEmpty())

        s.run(2 * 60_000L) { 20.0 }
        assertEquals(1, s.recoveries(AlertType.DIMMING).size)
        assertEquals(FlameStatus.OK, s.analyzer.lastSnapshot!!.status)
    }

    @Test
    fun `brief dip does not alert`() {
        val s = Scenario()
        s.run(60_000) { 13.0 }
        s.run(10 * 60_000L) { 20.0 }
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun `flame out raises urgent alert after 30 seconds and repeats after cooldown`() {
        val s = Scenario()
        s.run(60_000) { 20.0 }
        val start = s.nowMs
        s.run(20 * 60_000L) { 0.0 }
        val out = s.alerts(AlertType.FLAME_OUT)
        assertEquals("one alert plus one reminder after 15 min", 2, out.size)
        val delay = out.first().first - start
        assertTrue("out alert after ${delay}ms", delay in 28_000..34_000)
        assertTrue((out[1].second as DetectionEvent.Alert).reminder)
        assertEquals(Severity.URGENT, out.first().second.type.severity)
        assertTrue("dimming must not fire when the flame is out", s.alerts(AlertType.DIMMING).isEmpty())
        assertEquals(FlameStatus.URGENT, s.analyzer.lastSnapshot!!.status)
    }

    @Test
    fun `cooldown suppresses repeated alert of the same type`() {
        val s = Scenario()
        s.run(60_000) { 0.0 }
        s.run(60_000) { 20.0 } // recovers
        assertEquals(1, s.recoveries(AlertType.FLAME_OUT).size)
        s.run(60_000) { 0.0 } // out again within 15 min cooldown
        assertEquals(1, s.alerts(AlertType.FLAME_OUT).size)
    }

    @Test
    fun `heavy flicker raises soot warning`() {
        val s = Scenario()
        s.run(60_000) { 20.0 }
        // Area jumps between ~30% and ~150% of baseline every frame; mean stays near normal.
        var toggle = false
        s.run(3 * 60_000L) { toggle = !toggle; if (toggle) 11.0 + s.rnd.nextDouble(0.0, 1.0) else 24.0 + s.rnd.nextDouble(0.0, 1.0) }
        val soot = s.alerts(AlertType.SOOT)
        assertEquals(1, soot.size)
        assertTrue(soot.first().second.snapshot.sootReasons.any { "flicker" in it })
        assertTrue(soot.first().second.message().contains("trim"))
    }

    @Test
    fun `oversized flame raises soot warning`() {
        val s = Scenario()
        s.run(3 * 60_000L) { 28.0 } // area ~196% of baseline
        val soot = s.alerts(AlertType.SOOT)
        assertEquals(1, soot.size)
        assertTrue(soot.first().second.snapshot.sootReasons.any { "normal size" in it })
    }

    @Test
    fun `darkening above the flame raises soot warning`() {
        val s = Scenario()
        s.run(3 * 60_000L, wall = 35) { 20.0 }
        val soot = s.alerts(AlertType.SOOT)
        assertEquals(1, soot.size)
        assertTrue(soot.first().second.snapshot.sootReasons.any { "darkened" in it })
    }

    @Test
    fun `gradual shrink over 40 minutes is reported as low oil but not dimming`() {
        val s = Scenario()
        val start = s.nowMs
        val total = 40 * 60_000L
        // Area falls linearly from 100% to ~62% of baseline over 40 minutes (still above the dim threshold).
        s.run(total) { t -> 20.0 * sqrt(1.0 - 0.38 * t / total) }
        val lowOil = s.alerts(AlertType.LOW_OIL)
        assertEquals(1, lowOil.size)
        assertTrue("low oil should not fire in the first 30 min", lowOil.first().first - start > 30 * 60_000L)
        assertNotNull(lowOil.first().second.snapshot.trendDecline)
        assertTrue(s.alerts(AlertType.DIMMING).isEmpty())
        assertTrue(s.alerts(AlertType.FLAME_OUT).isEmpty())
    }

    @Test
    fun `time scaled config fires quickly in test mode`() {
        val s = Scenario(DetectionConfig().timeScaled(0.1))
        s.run(8_000) { 0.0 }
        assertEquals(1, s.alerts(AlertType.FLAME_OUT).size)
    }
}
