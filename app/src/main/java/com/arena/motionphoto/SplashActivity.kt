package com.arena.motionphoto

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.arena.motionphoto.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var b: ActivitySplashBinding
    private var moved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Animasi morph hanya sekali. Launch berikutnya langsung ke Home.
        if (Settings.seenSplash(this)) {
            goNext(animate = false)
            return
        }

        b = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.morph.animateMorph(1150L) { goNext(animate = true) }

        b.title.alpha = 0f
        b.title.animate().alpha(1f).setStartDelay(560).setDuration(420).start()

        Handler(Looper.getMainLooper()).postDelayed({ goNext(animate = true) }, 2000)
    }

    private fun goNext(animate: Boolean) {
        if (moved) return
        moved = true
        Settings.markSplashSeen(this)
        startActivity(Intent(this, HomeActivity::class.java))
        if (animate) {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            overridePendingTransition(0, 0)
        }
        finish()
    }
}
