package com.kiransaj.diyamonitor.core

import kotlin.random.Random

/** Builds synthetic upright frames: dark room, a lit wall above, and an elliptical flame. */
object SyntheticFrames {
    const val W = 200
    const val H = 200

    /** ROI used by the tests: a 100x100 box in the lower middle of the frame. */
    val ROI = NormRect(0.25f, 0.4f, 0.75f, 0.9f)

    private const val BACKGROUND = 15
    private const val WALL = 70
    private const val FLAME = 250

    fun frame(flameRadius: Double, wallLuma: Int = WALL, noise: Random? = null): LumaFrame {
        val luma = ByteArray(W * H)
        val v = ByteArray(W * H)
        val cx = W / 2.0
        val cy = H * 0.65
        for (y in 0 until H) {
            for (x in 0 until W) {
                val base = if (y < H * 0.4) wallLuma else BACKGROUND
                var l = base + (noise?.nextInt(-3, 4) ?: 0)
                // Flames are taller than wide: ellipse with vertical semi-axis 1.6 * radius.
                val dx = (x - cx) / flameRadius
                val dy = (y - cy) / (flameRadius * 1.6)
                val inFlame = flameRadius > 0 && dx * dx + dy * dy <= 1.0
                if (inFlame) l = FLAME
                luma[y * W + x] = l.coerceIn(0, 255).toByte()
                v[y * W + x] = (if (inFlame) 170 else 128).toByte()
            }
        }
        return LumaFrame(W, H, luma, v)
    }
}

/** Drives calibration + analysis with a simulated clock (one frame every 2 s). */
class Scenario(config: DetectionConfig = DetectionConfig(), baselineRadius: Double = 20.0, seed: Int = 1) {
    val rnd = Random(seed)
    private val extractor = MetricsExtractor()
    var nowMs = 0L
        private set
    val baseline: Baseline
    val analyzer: FlameAnalyzer
    val events = mutableListOf<Pair<Long, DetectionEvent>>()

    init {
        val calibrator = Calibrator()
        repeat(30) {
            calibrator.add(measure(baselineRadius + rnd.nextDouble(-0.5, 0.5)))
            nowMs += STEP
        }
        baseline = calibrator.build()
        analyzer = FlameAnalyzer(baseline, config)
    }

    fun measure(radius: Double, wall: Int = 70): FrameMetrics =
        extractor.extract(SyntheticFrames.frame(radius, wall, rnd), SyntheticFrames.ROI, nowMs)

    /** Runs for [durationMs] with the flame radius given by [radiusAt] (time since start of this run). */
    fun run(durationMs: Long, wall: Int = 70, radiusAt: (Long) -> Double) {
        val start = nowMs
        while (nowMs - start < durationMs) {
            analyzer.process(measure(radiusAt(nowMs - start), wall)).forEach { events += nowMs to it }
            nowMs += STEP
        }
    }

    fun alerts(type: AlertType) = events.filter { it.second is DetectionEvent.Alert && it.second.type == type }
    fun recoveries(type: AlertType) = events.filter { it.second is DetectionEvent.Recovered && it.second.type == type }

    companion object {
        const val STEP = 2_000L
    }
}
