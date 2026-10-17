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
import com.kiransaj.diyamonitor.core.FlameRegion
import com.kiransaj.diyamonitor.core.NormRect

/**
 * Draws the watched flame boxes (numbered, with the soot zone above each) and, faintly,
 * other bright spots over the camera preview. A tap reports a normalised point so the
 * user can add a missed flame or remove a wrong one. Coordinates are normalised to the
 * upright camera image, which the preview shows letterboxed (FIT_CENTER) with aspect
 * [imageAspect] (width / height).
 */
class FlameOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var imageAspect = 3f / 4f
    var editable = true
    var onTap: ((Float, Float) -> Unit)? = null

    var flames: List<FlameRegion> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var blobs: List<NormRect> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * density; color = Color.rgb(255, 170, 0)
    }
    private val abovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = Color.LTGRAY
        pathEffect = DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
    }
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1 * density; color = Color.argb(140, 200, 200, 200)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 14 * density; isFakeBoldText = true; setShadowLayer(3f, 0f, 0f, Color.BLACK)
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
        for (b in blobs) {
            if (flames.none { it.box.contains(b) }) canvas.drawRect(toView(b.expanded(0.3f), c), blobPaint)
        }
        flames.forEachIndexed { i, f ->
            val box = toView(f.box, c)
            canvas.drawRect(toView(f.box.aboveZone(), c), abovePaint)
            canvas.drawRect(box, boxPaint)
            canvas.drawText("${i + 1}", box.left + 4 * density, box.top - 4 * density, textPaint)
        }
    }

    private fun NormRect.contains(o: NormRect) =
        (o.left + o.right) / 2 in left..right && (o.top + o.bottom) / 2 in top..bottom

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editable) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val c = content()
            val nx = (event.x - c.left) / c.width()
            val ny = (event.y - c.top) / c.height()
            if (nx in 0f..1f && ny in 0f..1f) onTap?.invoke(nx, ny)
        }
        return true
    }
}
