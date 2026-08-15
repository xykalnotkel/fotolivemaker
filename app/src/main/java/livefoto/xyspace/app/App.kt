package livefoto.xyspace.app

/**
 * XySpace Personal Use License v1.0
 * Copyright 2026 XySpace — Haekal Saputra (KALL)
 * 
 * This source is free for personal, educational, non-commercial use.
 * Commercial use requires separate written permission from XySpace.
 * See LICENSE file for full terms.
 */

import android.app.Application

/** Terapkan tema sebelum activity pertama dibuat. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.applyTheme(Settings.theme(this))
    }
}
