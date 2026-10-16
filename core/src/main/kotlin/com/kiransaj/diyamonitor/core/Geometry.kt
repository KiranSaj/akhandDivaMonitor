package com.kiransaj.diyamonitor.core

import kotlin.math.max
import kotlin.math.min

/**
 * A rectangle in normalised coordinates (0..1) of the *upright* camera image,
 * i.e. the image as the user sees it in the portrait preview.
 */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun clamped(): NormRect = NormRect(
        left.coerceIn(0f, 1f), top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f),
    )

    /** Zone directly above this rect (same width), where soot/smoke would appear. */
    fun aboveZone(fraction: Float = 0.6f): NormRect =
        NormRect(left, max(0f, top - height * fraction), right, top)

    /** Grows the rect by [fraction] of its size on every side (clamped to the image). */
    fun expanded(fraction: Float): NormRect =
        NormRect(left - width * fraction, top - height * fraction, right + width * fraction, bottom + height * fraction).clamped()

    fun union(other: NormRect): NormRect =
        NormRect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

    fun isUsable(): Boolean = width >= 0.03f && height >= 0.03f

    companion object {
        val DEFAULT = NormRect(0.35f, 0.4f, 0.65f, 0.7f)

        fun of(x1: Float, y1: Float, x2: Float, y2: Float): NormRect =
            NormRect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)).clamped()
    }
}

/**
 * Maps between the upright image and the raw sensor buffer. CameraX reports
 * `rotationDegrees`: how far the raw buffer must be rotated clockwise to be upright.
 */
object Rotation {
    /** Returns the raw-buffer normalised (x, y) for an upright normalised point (u, v). */
    fun uprightToRaw(u: Float, v: Float, rotationDegrees: Int): Pair<Float, Float> = when (normalise(rotationDegrees)) {
        90 -> Pair(v, 1f - u)
        180 -> Pair(1f - u, 1f - v)
        270 -> Pair(1f - v, u)
        else -> Pair(u, v)
    }

    /** Maps an upright rect to the equivalent rect in raw-buffer normalised coordinates. */
    fun uprightRectToRaw(rect: NormRect, rotationDegrees: Int): NormRect {
        val (x1, y1) = uprightToRaw(rect.left, rect.top, rotationDegrees)
        val (x2, y2) = uprightToRaw(rect.right, rect.bottom, rotationDegrees)
        return NormRect.of(x1, y1, x2, y2)
    }

    fun normalise(rotationDegrees: Int): Int = ((rotationDegrees % 360) + 360) % 360
}
