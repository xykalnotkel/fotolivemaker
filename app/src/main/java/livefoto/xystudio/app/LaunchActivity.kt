package livefoto.xystudio.app

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/** Splash modern dengan GIF pill putih animasi dari user. */
@SuppressLint("CustomSplashScreen")
class LaunchActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val startedAt = SystemClock.uptimeMillis()
        val showIntro = Settings.showSplash(this) && !Settings.isReduceMotion(this)

        // Tetap pakai system splash untuk Android 12+ biar tidak putih, tapi kita override kontennya
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition {
            showIntro && SystemClock.uptimeMillis() - startedAt < 80L
        }

        Settings.prepareActivity(this)
        super.onCreate(savedInstanceState)

        // Jika user matikan splash, langsung ke home
        if (!showIntro) {
            openHome()
            return
        }

        setContentView(R.layout.activity_splash)
        val img = findViewById<ImageView>(R.id.imgSplash)

        // Load GIF dari raw/splash_gif.gif pakai ImageDecoder (API 28+) agar animasi jalan
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = android.graphics.ImageDecoder.createSource(resources, R.raw.splash_gif)
                val drawable = android.graphics.ImageDecoder.decodeDrawable(source)
                img.setImageDrawable(drawable)
                if (drawable is AnimatedImageDrawable) {
                    drawable.start()
                }
            } else {
                // Fallback: tampilkan static dari drawable lama
                img.setImageResource(R.drawable.ic_splash_card)
            }
        } catch (_: Exception) {
            img.setImageResource(R.drawable.ic_splash_card)
        }

        // Durasi splash: biarkan GIF loop ~1.2 detik biar kelihatan modern
        val delay = 1200L
        Handler(Looper.getMainLooper()).postDelayed({
            openHome()
        }, delay)
    }

    private fun openHome() {
        val next = Intent(this, HomeActivity::class.java)
        if (Settings.isReduceMotion(this)) {
            startActivity(next, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
        } else {
            startActivity(next)
        }
        finish()
    }
}
