package livefoto.xyspace.app

/**
 * XySpace Personal Use License v1.0
 * Copyright 2026 XySpace — Haekal Saputra (KALL)
 * 
 * This source is free for personal, educational, non-commercial use.
 * Commercial use requires separate written permission from XySpace.
 * See LICENSE file for full terms.
 */

import android.animation.Animator
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/** Splash dengan GIF animasi + fade transisi ke Home. */
@SuppressLint("CustomSplashScreen")
class LaunchActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val startedAt = SystemClock.uptimeMillis()
        val showIntro = Settings.showSplash(this) && !Settings.isReduceMotion(this)
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { false }

        Settings.prepareActivity(this)
        super.onCreate(savedInstanceState)

        if (!showIntro) {
            openHome()
            return
        }

        setContentView(R.layout.activity_splash)
        val img = findViewById<ImageView>(R.id.imgSplash)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = android.graphics.ImageDecoder.createSource(resources, R.drawable.splash_gif)
                val drawable = android.graphics.ImageDecoder.decodeDrawable(source)
                img.setImageDrawable(drawable)
                if (drawable is AnimatedImageDrawable) drawable.start()
            } else {
                img.setImageResource(R.drawable.ic_splash_card)
            }
        } catch (_: Exception) {
            img.setImageResource(R.drawable.ic_splash_card)
        }

        // Biarkan GIF loop kira-kira 2 putaran biar keliatan, baru fade ke home
        val minShow = 2000L
        val elapsed = SystemClock.uptimeMillis() - startedAt
        val delay = maxOf(100L, minShow - elapsed)

        Handler(Looper.getMainLooper()).postDelayed({
            openHomeWithFade()
        }, delay)
    }

    private fun openHomeWithFade() {
        val root = findViewById<View>(android.R.id.content)
        root.animate()
            .alpha(0f)
            .setDuration(400)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : Animator.AnimatorListener {
                override fun onAnimationEnd(animation: Animator) {
                    openHome()
                }
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationCancel(animation: Animator) { openHome() }
                override fun onAnimationRepeat(animation: Animator) {}
            })
            .start()
    }

    private fun openHome() {
        val next = Intent(this, HomeActivity::class.java)
        if (Settings.isReduceMotion(this)) {
            startActivity(next, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
        } else {
            // Fade in untuk home: alpha 0->1
            val opts = ActivityOptions.makeCustomAnimation(
                this, android.R.anim.fade_in, android.R.anim.fade_out
            )
            startActivity(next, opts.toBundle())
        }
        finish()
    }
}