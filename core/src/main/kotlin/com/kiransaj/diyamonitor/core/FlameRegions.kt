package com.kiransaj.diyamonitor.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The box watched for one flame. [anchorX] is the flame's centre and [anchorY] its base
 * (where the wick is); the tracker moves the box when the anchor drifts.
 */
data class FlameRegion(val box: NormRect, val anchorX: Float, val anchorY: Float) {

    fun shifted(dx: Float, dy: Float) = FlameRegion(
        NormRect(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy),
        anchorX + dx,
        anchorY + dy,
    )

    fun contains(x: Float, y: Float) = x >= box.left && x <= box.right && y >= box.top && y <= box.bottom

    companion object {
        /**
         * A box around a detected flame, with room for it to grow, mostly upwards.
         * Minimum sizes assume a downsampled frame of roughly 240x320.
         */
        fun around(flame: NormRect): FlameRegion {
            val w = max(flame.width * 2.2f, 0.06f)
            val h = max(flame.height * 2.4f, 0.08f)
            val cx = (flame.left + flame.right) / 2
            val bottom = flame.bottom + flame.height * 0.4f
            val box = NormRect(cx - w / 2, bottom - h, cx + w / 2, bottom).clamped()
            return FlameRegion(box, cx, flame.bottom)
        }

        /** A default-sized box for a flame the user tapped at ([x], [y]) where nothing was detected. */
        fun atTap(x: Float, y: Float): FlameRegion = around(NormRect(x - 0.02f, y - 0.03f, x + 0.02f, y + 0.03f))

        /** Shrinks boxes that overlap side by side so that each pixel belongs to at most one flame. */
        fun separate(regions: List<FlameRegion>): List<FlameRegion> {
            val sorted = regions.sortedBy { it.anchorX }.toMutableList()
            for (i in 0 until sorted.size - 1) {
                val a = sorted[i]
                val b = sorted[i + 1]
                if (a.box.right > b.box.left) {
                    val mid = (a.anchorX + b.anchorX) / 2
                    sorted[i] = a.copy(box = a.box.copy(right = min(a.box.right, mid)))
                    sorted[i + 1] = b.copy(box = b.box.copy(left = max(b.box.left, mid)))
                }
            }
            return sorted
        }
    }
}

/**
 * Watches the bright blobs over several frames and picks the real flames: present in
 * nearly every frame, with a saturated core, flickering on their own. Reflections in the
 * brass bowl or the oil are penalised: they lack a white core and sit below a brighter blob.
 */
class FlameFinder(private val expected: Int) {

    class Candidate(val box: NormRect, val presence: Double, val score: Double)

    private class Track(blob: Blob) {
        var cx = blob.cx
        var cy = blob.cy
        var box = blob.box
        var lastBox = blob.box
        val areas = mutableListOf(blob.pixels.toDouble())
        var coreSum = blob.coreFraction
        var hits = 1

        fun add(blob: Blob) {
            cx += (blob.cx - cx) * 0.3f
            cy += (blob.cy - cy) * 0.3f
            box = box.union(blob.box)
            lastBox = blob.box
            areas += blob.pixels.toDouble()
            coreSum += blob.coreFraction
            hits++
        }

        fun matchRadius() = max(0.03f, max(lastBox.width, lastBox.height))
        fun dist(b: Blob) = max(abs(b.cx - cx), abs(b.cy - cy))
        val meanCore get() = coreSum / hits
        val meanArea get() = Stats.mean(areas)
        val flicker get() = if (meanArea <= 0) 0.0 else Stats.stdDev(areas) / meanArea
    }

    private val tracks = mutableListOf<Track>()
    var frames = 0
        private set

    fun add(blobs: List<Blob>) {
        frames++
        val used = mutableSetOf<Track>()
        for (b in blobs) {
            val t = tracks.filter { it !in used }.minByOrNull { it.dist(b) }?.takeIf { it.dist(b) <= it.matchRadius() }
            if (t == null) {
                tracks += Track(b).also { used += it }
            } else {
                t.add(b)
                used += t
            }
        }
    }

    /** All blobs seen often enough to be worth showing, best first. */
    fun candidates(): List<Candidate> {
        if (frames == 0) return emptyList()
        val scored = tracks.filter { it.hits.toDouble() / frames >= 0.3 }.map { t -> t to score(t) }
        return scored.sortedByDescending { it.second }.map { (t, s) -> Candidate(t.box, t.hits.toDouble() / frames, s) }
    }

    /** The [expected] best flames as watch regions, or fewer if fewer were found. */
    fun flames(): List<FlameRegion> {
        if (frames == 0) return emptyList()
        val best = tracks
            .filter { it.hits.toDouble() / frames >= MIN_PRESENCE }
            .map { it to score(it) }
            .sortedByDescending { it.second }
            .take(expected)
            .map { (t, _) -> FlameRegion.around(t.box) }
        return FlameRegion.separate(best)
    }

    private fun score(t: Track): Double {
        val presence = t.hits.toDouble() / frames
        var s = presence * (0.3 + t.meanCore) * (1 + min(t.flicker, 0.5)) * ln(1 + t.meanArea)
        // A reflection sits below a flame that has a much whiter core.
        val isReflection = tracks.any { o ->
            o !== t && o.cy < t.cy && abs(o.cx - t.cx) < max(0.05f, o.box.width * 1.5f) &&
                o.meanCore > t.meanCore * 2 && o.meanArea >= t.meanArea * 0.5
        }
        if (isReflection) s *= 0.3
        return s
    }

    companion object {
        const val MIN_PRESENCE = 0.6
    }
}

/** Result of following the flames into a new frame. */
data class TrackResult(
    val regions: List<FlameRegion>,
    /** For each region, whether a flame was seen near it. */
    val found: List<Boolean>,
    /** Flame-like blobs that belong to no region (candle, fire, or the flames after the camera moved). */
    val unassigned: List<Blob>,
)

object FlameTracker {
    /** A blob counts as flame-like when it has some saturated core. */
    fun isFlameLike(b: Blob) = b.pixels >= 6 && b.coreFraction >= 0.2

    /**
     * Moves each region a little towards its flame so that slow drift (a nudged phone)
     * is followed. Horizontal position follows the flame centre and vertical position its
     * base (the wick), because a shrinking flame lowers its centre but not its base.
     */
    fun follow(regions: List<FlameRegion>, blobs: List<Blob>, gain: Float = 0.2f, maxStep: Float = 0.02f): TrackResult {
        val claimed = mutableSetOf<Blob>()
        val found = mutableListOf<Boolean>()
        val moved = regions.map { r ->
            val search = r.box.expanded(0.5f)
            val blob = blobs.filter { it !in claimed && search.contains(it.cx, it.cy) }.maxByOrNull { it.pixels }
            found += blob != null
            if (blob == null) {
                r
            } else {
                claimed += blob
                val dx = ((blob.cx - r.anchorX) * gain).coerceIn(-maxStep, maxStep)
                val dy = ((blob.box.bottom - r.anchorY) * gain).coerceIn(-maxStep, maxStep)
                r.shifted(dx, dy)
            }
        }
        val unassigned = blobs.filter { it !in claimed && isFlameLike(it) && regions.none { r -> r.box.expanded(0.5f).contains(it.cx, it.cy) } }
        return TrackResult(moved, found, unassigned)
    }

    private fun NormRect.contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom
}

/**
 * Decides that the camera or lamp was moved: every flame has vanished from its box,
 * while as many flame-like blobs show up elsewhere, for [holdMs].
 * (If only some flames vanish, that is a flame going out, not a move.)
 */
class MoveDetector(private val holdMs: Long = 30_000) {
    private var since: Long? = null

    fun update(result: TrackResult, nowMs: Long): Boolean {
        val allMissing = result.found.isNotEmpty() && result.found.none { it }
        val elsewhere = result.unassigned.size >= result.found.size
        if (!(allMissing && elsewhere)) {
            since = null
            return false
        }
        val start = since ?: nowMs.also { since = it }
        return nowMs - start >= holdMs
    }

    fun reset() {
        since = null
    }
}
