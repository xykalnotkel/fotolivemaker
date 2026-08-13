package com.arena.motionphoto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * Progress mengikuti bentuk kotak preview — stroke mengelilingi
 * bingkai, mulai dari tengah atas, searah jarum jam.
 */
class BoxProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private val stroke = 4f * density
    private val radius = 12f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = ContextCompat.getColor(context, R.color.line)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.ink)
    }

    var progress: Int = 0
        set(value) {
            val v = value.coerceIn(0, 100)
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val rect = RectF()
    private val fullPath = Path()
    private val segPath = Path()
    private val measure = PathMeasure()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = stroke / 2f + density
        rect.set(inset, inset, w - inset, h - inset)
        rebuildPath()
    }

    private fun rebuildPath() {
        fullPath.reset()
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val r = radius.coerceAtMost(min(rect.width(), rect.height()) / 2f)
        fullPath.moveTo(rect.centerX(), rect.top)
        fullPath.lineTo(rect.right - r, rect.top)
        fullPath.arcTo(RectF(rect.right - 2 * r, rect.top, rect.right, rect.top + 2 * r), -90f, 90f, false)
        fullPath.lineTo(rect.right, rect.bottom - r)
        fullPath.arcTo(RectF(rect.right - 2 * r, rect.bottom - 2 * r, rect.right, rect.bottom), 0f, 90f, false)
        fullPath.lineTo(rect.left + r, rect.bottom)
        fullPath.arcTo(RectF(rect.left, rect.bottom - 2 * r, rect.left + 2 * r, rect.bottom), 90f, 90f, false)
        fullPath.lineTo(rect.left, rect.top + r)
        fullPath.arcTo(RectF(rect.left, rect.top, rect.left + 2 * r, rect.top + 2 * r), 180f, 90f, false)
        fullPath.lineTo(rect.centerX(), rect.top)
    }

    override fun onDraw(canvas: Canvas) {
        if (fullPath.isEmpty) rebuildPath()
        canvas.drawPath(fullPath, trackPaint)
        if (progress <= 0) return
        measure.setPath(fullPath, false)
        val len = measure.length
        if (len <= 0f) return
        segPath.reset()
        measure.getSegment(0f, len * (progress / 100f), segPath, true)
        canvas.drawPath(segPath, progressPaint)
    }
}
