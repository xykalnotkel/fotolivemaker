package com.arena.motionphoto

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** Pengaturan yang tersimpan antar sesi. */
object Settings {

    private const val FILE = "settings"
    private const val K_RES = "res"
    private const val K_ASPECT_RATIO = "aspect_ratio"
    private const val K_SQUARE = "square"
    private const val K_ENHANCE = "enhance"
    private const val K_STAB = "stabilize"
    private const val K_QUALITY = "jpeg_quality"
    private const val K_THEME = "theme"
    private const val K_KEEP_SCREEN_ON = "keep_screen_on"
    private const val K_SEEN_SPLASH = "seen_splash"
    private const val K_SHOW_SPLASH = "show_splash"

    // Pengaturan Aksesibilitas & Kontras
    private const val K_HIGH_CONTRAST = "high_contrast"
    private const val K_HAPTICS = "haptics"
    private const val K_REDUCE_MOTION = "reduce_motion"

    /** 0 = ikut sistem, 1 = terang, 2 = gelap */
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(c: Context): Converter.Options {
        val p = sp(c)
        val resName = p.getString(K_RES, Converter.Res.P1080.name)!!
        val res = runCatching { Converter.Res.valueOf(resName) }
            .getOrDefault(Converter.Res.P1080)

        val ratioName = p.getString(K_ASPECT_RATIO, null)
        val aspectRatio = if (ratioName != null) {
            runCatching { Converter.AspectRatio.valueOf(ratioName) }
                .getOrDefault(Converter.AspectRatio.ORIGINAL)
        } else if (p.getBoolean(K_SQUARE, false)) {
            Converter.AspectRatio.RATIO_1_1
        } else {
            Converter.AspectRatio.ORIGINAL
        }

        return Converter.Options(
            aspectRatio = aspectRatio,
            res = res,
            enhance = p.getBoolean(K_ENHANCE, false),
            stabilize = p.getBoolean(K_STAB, false),
            jpegQuality = p.getInt(K_QUALITY, 96)
        )
    }

    fun theme(c: Context): Int = sp(c).getInt(K_THEME, THEME_SYSTEM)

    fun keepScreenOn(c: Context): Boolean = sp(c).getBoolean(K_KEEP_SCREEN_ON, true)

    fun seenSplash(c: Context): Boolean = sp(c).getBoolean(K_SEEN_SPLASH, false)

    fun markSplashSeen(c: Context) {
        sp(c).edit().putBoolean(K_SEEN_SPLASH, true).apply()
    }

    fun showSplash(c: Context): Boolean = sp(c).getBoolean(K_SHOW_SPLASH, true)

    fun setShowSplash(c: Context, enabled: Boolean) {
        sp(c).edit().putBoolean(K_SHOW_SPLASH, enabled).apply()
    }

    fun setKeepScreenOn(c: Context, enabled: Boolean) {
        sp(c).edit().putBoolean(K_KEEP_SCREEN_ON, enabled).apply()
    }

    // Aksesibilitas: High Contrast, Haptics, Reduce Motion
    fun isHighContrast(c: Context): Boolean = sp(c).getBoolean(K_HIGH_CONTRAST, false)

    fun setHighContrast(c: Context, enabled: Boolean) {
        sp(c).edit().putBoolean(K_HIGH_CONTRAST, enabled).apply()
    }

    fun isHapticsEnabled(c: Context): Boolean = sp(c).getBoolean(K_HAPTICS, true)

    fun setHapticsEnabled(c: Context, enabled: Boolean) {
        sp(c).edit().putBoolean(K_HAPTICS, enabled).apply()
    }

    fun isReduceMotion(c: Context): Boolean = sp(c).getBoolean(K_REDUCE_MOTION, false)

    fun setReduceMotion(c: Context, enabled: Boolean) {
        sp(c).edit().putBoolean(K_REDUCE_MOTION, enabled).apply()
    }

    fun triggerHaptic(view: View) {
        if (isHapticsEnabled(view.context)) {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    HapticFeedbackConstants.CONTEXT_CLICK
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }
            )
        }
    }

    fun setTheme(c: Context, mode: Int) {
        sp(c).edit().putInt(K_THEME, mode).apply()
        applyTheme(mode)
    }

    fun applyTheme(mode: Int) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                THEME_LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun save(c: Context, o: Converter.Options) {
        sp(c).edit()
            .putString(K_RES, o.res.name)
            .putString(K_ASPECT_RATIO, o.aspectRatio.name)
            .putBoolean(K_SQUARE, o.aspectRatio == Converter.AspectRatio.RATIO_1_1)
            .putBoolean(K_ENHANCE, o.enhance)
            .putBoolean(K_STAB, o.stabilize)
            .putInt(K_QUALITY, o.jpegQuality)
            .apply()
    }
}
