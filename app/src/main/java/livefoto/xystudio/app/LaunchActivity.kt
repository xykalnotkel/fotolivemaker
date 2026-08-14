package livefoto.xystudio.app

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/** System SplashScreen yang konsisten dari Android 7 sampai 17. */
@SuppressLint("CustomSplashScreen")
class LaunchActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val startedAt = SystemClock.uptimeMillis()
        val showIntro = Settings.showSplash(this) && !Settings.isReduceMotion(this)
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition {
            showIntro && SystemClock.uptimeMillis() - startedAt < 420L
        }

        Settings.prepareActivity(this)
        super.onCreate(savedInstanceState)
        val openHome = Runnable {
            val next = Intent(this, HomeActivity::class.java)
            if (Settings.isReduceMotion(this)) {
                startActivity(next, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
            } else {
                startActivity(next)
            }
            finish()
        }
        if (showIntro) Handler(Looper.getMainLooper()).postDelayed(openHome, 420L)
        else openHome.run()
    }
}
