package livefoto.xystudio.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Progress nempel di tepi preview, sudut siku, mulai pojok kiri atas
 * lalu searah jarum jam. Bukan cincin, bukan rounded.
 */
class BoxProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private val stroke = 2.5f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
        color = Settings.color(context, R.attr.appLine)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
        color = Settings.color(context, R.attr.appInk)
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
        val inset = stroke / 2f
        rect.set(inset, inset, w - inset, h - inset)
        rebuildPath()
    }

    private fun rebuildPath() {
        fullPath.reset()
        if (rect.width() <= 0f || rect.height() <= 0f) return
        // Pojok kiri atas → kanan → bawah → kiri → naik lagi.
        fullPath.moveTo(rect.left, rect.top)
        fullPath.lineTo(rect.right, rect.top)
        fullPath.lineTo(rect.right, rect.bottom)
        fullPath.lineTo(rect.left, rect.bottom)
        fullPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        if (fullPath.isEmpty) rebuildPath()
        canvas.drawPath(fullPath, trackPaint)
        if (progress <= 0) return
        measure.setPath(fullPath, true)
        val len = measure.length
        if (len <= 0f) return
        segPath.reset()
        measure.getSegment(0f, len * (progress / 100f), segPath, true)
        canvas.drawPath(segPath, progressPaint)
    }
}
