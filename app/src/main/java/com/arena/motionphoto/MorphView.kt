package com.arena.motionphoto

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animasi splash morphing: Ikon Video bertransformasi halus menjadi Ikon Live Photo.
 * Dengan transisi warna dinamis ke Royal Indigo.
 */
class MorphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val inkColor = ContextCompat.getColor(context, R.color.ink)
    private val accentColor = ContextCompat.getColor(context, R.color.accent_primary)

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = inkColor
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = inkColor
    }

    private var t = 0f
    private val rect = RectF()

    var progress: Float
        get() = t
        set(v) { t = v.coerceIn(0f, 1f); invalidate() }

    fun animateMorph(duration: Long = 1150L, onEnd: (() -> Unit)? = null) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = PathInterpolator(0.22f, 0.9f, 0.16f, 1f)
            addUpdateListener { progress = it.animatedValue as Float }
            if (onEnd != null) {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) = onEnd()
                })
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val unit = minOf(width, height) / 48f
        stroke.strokeWidth = 2.6f * unit

        // Transisi warna halus dari ink ke royal violet/indigo
        val curColor = blendColor(inkColor, accentColor, smoothstep(0.35f, 0.95f, t))
        stroke.color = curColor
        fill.color = curColor

        // ---- 1. Bingkai video -> cincin ----
        val fw = lerp(20f, 10.4f, ease(t)) * unit
        val fh = lerp(14f, 10.4f, ease(t)) * unit
        val corner = lerp(3f * unit, minOf(fw, fh), ease(t))

        rect.set(cx - fw, cy - fh, cx + fw, cy + fh)
        canvas.drawRoundRect(rect, corner, corner, stroke)

        // ---- 2. Segitiga play -> inti bulat ----
        val triA = (1f - smoothstep(0.15f, 0.62f, t))
        if (triA > 0.01f) {
            val s = lerp(6.4f, 2.2f, ease(t)) * unit
            fill.alpha = (255 * triA).toInt()
            val path = android.graphics.Path().apply {
                moveTo(cx - s * 0.55f, cy - s)
                lineTo(cx - s * 0.55f, cy + s)
                lineTo(cx + s * 0.95f, cy)
                close()
            }
            canvas.drawPath(path, fill)
        }

        val coreA = smoothstep(0.32f, 0.85f, t)
        if (coreA > 0.01f) {
            fill.alpha = (255 * coreA).toInt()
            canvas.drawCircle(cx, cy, 5.2f * unit * coreA, fill)
        }
        fill.alpha = 255

        // ---- 3. Segmen radial tumbuh keluar ----
        for (i in 0 until 12) {
            val delay = 0.40f + (i / 12f) * 0.30f
            val a = smoothstep(delay, delay + 0.26f, t)
            if (a <= 0.01f) continue

            val ang = Math.toRadians((i * 30 - 90).toDouble())
            val rIn = 14.8f * unit
            val rOut = lerp(14.8f, 18.4f, a) * unit

            stroke.alpha = (255 * a).toInt()
            canvas.drawLine(
                cx + (cos(ang) * rIn).toFloat(),
                cy + (sin(ang) * rIn).toFloat(),
                cx + (cos(ang) * rOut).toFloat(),
                cy + (sin(ang) * rOut).toFloat(),
                stroke
            )
        }
        stroke.alpha = 255
    }

    private fun blendColor(from: Int, to: Int, f: Float): Int {
        val a = lerp(Color.alpha(from).toFloat(), Color.alpha(to).toFloat(), f).toInt()
        val r = lerp(Color.red(from).toFloat(), Color.red(to).toFloat(), f).toInt()
        val g = lerp(Color.green(from).toFloat(), Color.green(to).toFloat(), f).toInt()
        val b = lerp(Color.blue(from).toFloat(), Color.blue(to).toFloat(), f).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
    private fun ease(x: Float) = smoothstep(0f, 1f, x)
    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val v = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }
}
