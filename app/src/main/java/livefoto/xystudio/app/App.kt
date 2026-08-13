package livefoto.xystudio.app

import android.app.Application

/** Terapkan tema sebelum activity pertama dibuat. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.applyTheme(Settings.theme(this))
    }
}
