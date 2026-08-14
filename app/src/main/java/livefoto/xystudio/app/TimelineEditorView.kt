package livefoto.xystudio.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.withClip
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Timeline editor ala video editor: strip thumbnail, jendela klip 3 detik,
 * dan playhead cover dalam satu kontrol. Tidak memakai Material Slider.
 */
class TimelineEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    fun interface Listener {
        fun onChanged(startMs: Long, keyframeOffsetMs: Long, finished: Boolean)
    }

    private enum class DragMode { NONE, WINDOW, KEYFRAME }

    private val density = resources.displayMetrics.density
    private val timeline = RectF()
    private val selection = RectF()
    private val src = Rect()
    private val dst = RectF()
    private val marker = Path()

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA8000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val keyOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    private var frames: List<Bitmap> = emptyList()
    private var totalMs = 1L
    private var clipStartMs = 0L
    private var clipDurationMs = 1L
    private var keyframeOffsetMs = 0L
    private var listener: Listener? = null
    private var dragMode = DragMode.NONE
    private var downX = 0f
    private var downStartMs = 0L

    init {
        minimumHeight = (112 * density).toInt()
        isClickable = true
        isFocusable = true
        contentDescription = "Timeline editor. Geser bingkai klip dan garis cover."
    }

    fun setListener(value: Listener?) {
        listener = value
    }

    fun setFrames(value: List<Bitmap>) {
        val old = frames
        frames = value
        old.filterNot { value.contains(it) }.forEach { if (!it.isRecycled) it.recycle() }
        invalidate()
    }

    fun configure(totalMs: Long, startMs: Long, durationMs: Long, keyOffsetMs: Long) {
        this.totalMs = totalMs.coerceAtLeast(1L)
        clipDurationMs = durationMs.coerceIn(1L, this.totalMs)
        clipStartMs = startMs.coerceIn(0L, (this.totalMs - clipDurationMs).coerceAtLeast(0L))
        keyframeOffsetMs = keyOffsetMs.coerceIn(0L, clipDurationMs)
        invalidate()
    }

    fun release() {
        frames.forEach { if (!it.isRecycled) it.recycle() }
        frames = emptyList()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        borderPaint.color = Settings.color(context, R.attr.appAccent)
        handlePaint.color = borderPaint.color
        emptyPaint.color = Settings.color(context, R.attr.appLine)
        labelPaint.color = Settings.color(context, R.attr.appTextMid)

        val side = 8f * density
        val top = 8f * density
        val bottomLabel = 24f * density
        timeline.set(side, top, width - side, height - bottomLabel)
        if (timeline.width() <= 1f || timeline.height() <= 1f) return

        canvas.withClip(timeline) {
            drawFrames(this)
            updateSelectionRect()
            if (selection.left > timeline.left) {
                drawRect(timeline.left, timeline.top, selection.left, timeline.bottom, shadePaint)
            }
            if (selection.right < timeline.right) {
                drawRect(selection.right, timeline.top, timeline.right, timeline.bottom, shadePaint)
            }
        }

        canvas.drawRoundRect(selection, 5f * density, 5f * density, borderPaint)
        val handleHalf = 10f * density
        canvas.drawLine(selection.left + 5f * density, selection.centerY() - handleHalf,
            selection.left + 5f * density, selection.centerY() + handleHalf, handlePaint)
        canvas.drawLine(selection.right - 5f * density, selection.centerY() - handleHalf,
            selection.right - 5f * density, selection.centerY() + handleHalf, handlePaint)

        val keyX = keyX()
        canvas.drawLine(
            keyX, selection.top + 2f * density,
            keyX, selection.bottom - 2f * density,
            keyOutlinePaint
        )
        canvas.drawLine(keyX, selection.top + 2f * density, keyX, selection.bottom - 2f * density, keyPaint)
        marker.reset()
        marker.moveTo(keyX, selection.top - 1f * density)
        marker.lineTo(keyX - 5f * density, selection.top - 7f * density)
        marker.lineTo(keyX + 5f * density, selection.top - 7f * density)
        marker.close()
        canvas.drawPath(marker, handlePaint)

        val startText = formatMs(clipStartMs)
        val endText = formatMs(clipStartMs + clipDurationMs)
        canvas.drawText(startText, timeline.left, height - 6f * density, labelPaint)
        val endW = labelPaint.measureText(endText)
        canvas.drawText(endText, timeline.right - endW, height - 6f * density, labelPaint)
    }

    private fun drawFrames(canvas: Canvas) {
        if (frames.isEmpty()) {
            canvas.drawRect(timeline, emptyPaint)
            val block = timeline.width() / 8f
            emptyPaint.color = Settings.color(context, R.attr.appLineStrong)
            for (i in 1 until 8) {
                val x = timeline.left + block * i
                canvas.drawLine(x, timeline.top, x, timeline.bottom, emptyPaint)
            }
            return
        }

        val cellW = timeline.width() / frames.size
        frames.forEachIndexed { index, bitmap ->
            if (bitmap.isRecycled) return@forEachIndexed
            dst.set(
                timeline.left + index * cellW,
                timeline.top,
                timeline.left + (index + 1) * cellW + 1f,
                timeline.bottom
            )
            centerCrop(bitmap, dst, src)
            canvas.drawBitmap(bitmap, src, dst, framePaint)
        }
    }

    private fun updateSelectionRect() {
        val left = timeline.left + timeline.width() * (clipStartMs.toFloat() / totalMs)
        val width = timeline.width() * (clipDurationMs.toFloat() / totalMs)
        selection.set(left, timeline.top, (left + width).coerceAtMost(timeline.right), timeline.bottom)
    }

    private fun keyX(): Float {
        updateSelectionRect()
        return selection.left + selection.width() *
            (keyframeOffsetMs.toFloat() / clipDurationMs.coerceAtLeast(1L))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateSelectionRect()
                downX = event.x
                downStartMs = clipStartMs
                dragMode = if (abs(event.x - keyX()) <= 22f * density) {
                    DragMode.KEYFRAME
                } else {
                    DragMode.WINDOW
                }
                if (dragMode == DragMode.KEYFRAME) updateKeyframe(event.x, false)
                else if (!selection.contains(event.x, event.y)) centerWindowAt(event.x, false)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.KEYFRAME) updateKeyframe(event.x, false)
                else updateWindow(event.x, false)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode == DragMode.KEYFRAME) updateKeyframe(event.x, true)
                else updateWindow(event.x, true)
                dragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateWindow(x: Float, finished: Boolean) {
        if (totalMs <= clipDurationMs || timeline.width() <= 0f) {
            notify(finished)
            return
        }
        val deltaMs = ((x - downX) / timeline.width() * totalMs).roundToLong()
        clipStartMs = (downStartMs + deltaMs)
            .coerceIn(0L, (totalMs - clipDurationMs).coerceAtLeast(0L))
        invalidate()
        notify(finished)
    }

    private fun centerWindowAt(x: Float, finished: Boolean) {
        val centerMs = (((x - timeline.left) / timeline.width()) * totalMs).roundToLong()
        clipStartMs = (centerMs - clipDurationMs / 2)
            .coerceIn(0L, (totalMs - clipDurationMs).coerceAtLeast(0L))
        downStartMs = clipStartMs
        downX = x
        invalidate()
        notify(finished)
    }

    private fun updateKeyframe(x: Float, finished: Boolean) {
        updateSelectionRect()
        if (selection.width() <= 0f) return
        keyframeOffsetMs = (((x - selection.left) / selection.width()) * clipDurationMs)
            .roundToLong().coerceIn(0L, clipDurationMs)
        invalidate()
        notify(finished)
    }

    private fun notify(finished: Boolean) {
        listener?.onChanged(clipStartMs, keyframeOffsetMs, finished)
    }

    private fun formatMs(ms: Long): String = "%.1fs".format(ms / 1000f)

    private fun centerCrop(bitmap: Bitmap, target: RectF, out: Rect) {
        val targetRatio = target.width() / target.height().coerceAtLeast(1f)
        val sourceRatio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        if (sourceRatio > targetRatio) {
            val w = (bitmap.height * targetRatio).toInt().coerceAtLeast(1)
            val left = (bitmap.width - w) / 2
            out.set(left, 0, left + w, bitmap.height)
        } else {
            val h = (bitmap.width / targetRatio).toInt().coerceAtLeast(1)
            val top = (bitmap.height - h) / 2
            out.set(0, top, bitmap.width, (top + h).coerceAtMost(bitmap.height))
        }
    }
}
