package com.arena.motionphoto

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animasi splash: ikon VIDEO berubah menjadi ikon LIVE PHOTO.
 *
 * Bukan fade in/out. Setiap bagian benar-benar bergerak menyatu:
 *
 *  - Bingkai persegi panjang video menyusut & membulat jadi cincin
 *  - Segitiga play mengerut jadi inti bulat di tengah
 *  - 12 segmen radial tumbuh keluar dari cincin
 *
 * Semuanya digambar dari satu progres 0..1 sehingga transisinya menyatu.
 */
class MorphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val ink = ContextCompat.getColor(context, R.color.ink)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ink
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ink
    }

    private var t = 0f
    private val rect = RectF()

    /** Progres 0 = ikon video, 1 = ikon live photo. */
    var progress: Float
        get() = t
        set(v) { t = v.coerceIn(0f, 1f); invalidate() }

    fun animateMorph(duration: Long = 1150L, onEnd: (() -> Unit)? = null) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            // percepatan lembut lalu mengendap — terasa menyatu, bukan patah
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
        val unit = minOf(width, height) / 48f      // skala seperti viewport 48
        stroke.strokeWidth = 2.6f * unit

        // ---- 1. Bingkai video -> cincin ----
        // Lebar menyusut dari 20 ke 10.4 (radius cincin), tinggi dari 14 ke 10.4
        val fw = lerp(20f, 10.4f, ease(t)) * unit
        val fh = lerp(14f, 10.4f, ease(t)) * unit
        // Radius sudut naik sampai menjadi lingkaran penuh
        val corner = lerp(3f * unit, minOf(fw, fh), ease(t))

        rect.set(cx - fw, cy - fh, cx + fw, cy + fh)
        canvas.drawRoundRect(rect, corner, corner, stroke)

        // ---- 2. Segitiga play -> inti bulat ----
        // Segitiga mengecil sambil inti membesar; keduanya tumpang tindih
        // di tengah animasi sehingga terlihat menyatu, bukan bertukar.
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
        // Muncul bergiliran searah jarum jam supaya terasa hidup.
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

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
    private fun ease(x: Float) = smoothstep(0f, 1f, x)
    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val v = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }
}
