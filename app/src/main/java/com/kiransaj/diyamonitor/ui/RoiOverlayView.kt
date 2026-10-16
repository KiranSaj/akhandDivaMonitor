package com.kiransaj.diyamonitor.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.kiransaj.diyamonitor.core.NormRect

/**
 * Draws the flame region of interest over the camera preview and lets the user drag
 * a new box. Coordinates are normalised to the upright camera image, which the preview
 * shows letterboxed (FIT_CENTER) with aspect [imageAspect] (width / height).
 */
class RoiOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var imageAspect = 3f / 4f
    var editable = true
    var onRoiChanged: ((NormRect) -> Unit)? = null

    var roi: NormRect = NormRect.DEFAULT
        set(value) {
            field = value
            invalidate()
        }

    private var dragStart: Pair<Float, Float>? = null
    private var previous: NormRect? = null
    private val density = resources.displayMetrics.density

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * density; color = Color.rgb(255, 170, 0)
    }
    private val abovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = Color.LTGRAY
        pathEffect = DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12 * density; setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    private fun content(): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        return if (w / h > imageAspect) {
            val cw = h * imageAspect
            RectF((w - cw) / 2, 0f, (w + cw) / 2, h)
        } else {
            val ch = w / imageAspect
            RectF(0f, (h - ch) / 2, w, (h + ch) / 2)
        }
    }

    private fun toView(r: NormRect, c: RectF) =
        RectF(c.left + r.left * c.width(), c.top + r.top * c.height(), c.left + r.right * c.width(), c.top + r.bottom * c.height())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = content()
        val box = toView(roi, c)
        canvas.drawRect(toView(roi.aboveZone(), c), abovePaint)
        canvas.drawRect(box, boxPaint)
        canvas.drawText("flame", box.left + 4 * density, box.bottom - 4 * density, textPaint)
        canvas.drawText("soot zone", box.left + 4 * density, box.top - 4 * density, textPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editable) return false
        val c = content()
        val nx = ((event.x - c.left) / c.width()).coerceIn(0f, 1f)
        val ny = ((event.y - c.top) / c.height()).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStart = nx to ny
                previous = roi
            }
            MotionEvent.ACTION_MOVE -> dragStart?.let { (sx, sy) -> roi = NormRect.of(sx, sy, nx, ny) }
            MotionEvent.ACTION_UP -> {
                dragStart?.let { (sx, sy) -> roi = NormRect.of(sx, sy, nx, ny) }
                if (roi.isUsable() && roi != previous) onRoiChanged?.invoke(roi) else roi = previous ?: roi
                dragStart = null
            }
            MotionEvent.ACTION_CANCEL -> {
                roi = previous ?: roi
                dragStart = null
            }
        }
        return true
    }
}
