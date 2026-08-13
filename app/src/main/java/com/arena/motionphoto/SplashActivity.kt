package com.arena.motionphoto

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.arena.motionphoto.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private var moved = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Settings.showSplash(this)) {
            goNext(animate = false)
            return
        }

        val b = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.morph.animateMorph(720L) { goNext(animate = true) }
        b.title.alpha = 0f
        b.title.animate().alpha(1f).setStartDelay(180).setDuration(280).start()
        handler.postDelayed({ goNext(animate = true) }, 1100)
    }

    private fun goNext(animate: Boolean) {
        if (moved) return
        moved = true
        handler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, HomeActivity::class.java))
        if (animate) {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        finish()
    }
}
