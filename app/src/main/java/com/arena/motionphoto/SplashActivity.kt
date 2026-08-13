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
        b = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.morph.animateMorph(1150L) { goNext() }

        b.title.alpha = 0f
        b.title.animate().alpha(1f).setStartDelay(560).setDuration(420).start()

        // jaring pengaman kalau animasi tidak memanggil balik
        Handler(Looper.getMainLooper()).postDelayed({ goNext() }, 2000)
    }

    private fun goNext() {
        if (moved) return
        moved = true
        startActivity(Intent(this, HomeActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
