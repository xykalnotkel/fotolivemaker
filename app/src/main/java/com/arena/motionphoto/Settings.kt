package com.arena.motionphoto

import android.content.Context

/** Pengaturan yang tersimpan antar sesi. */
object Settings {

    private const val FILE = "settings"
    private const val K_RES = "res"
    private const val K_SQUARE = "square"
    private const val K_ENHANCE = "enhance"
    private const val K_STAB = "stabilize"
    private const val K_QUALITY = "jpeg_quality"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(c: Context): Converter.Options {
        val p = sp(c)
        val resName = p.getString(K_RES, Converter.Res.P1080.name)!!
        val res = runCatching { Converter.Res.valueOf(resName) }
            .getOrDefault(Converter.Res.P1080)
        return Converter.Options(
            square = p.getBoolean(K_SQUARE, false),
            res = res,
            enhance = p.getBoolean(K_ENHANCE, false),
            stabilize = p.getBoolean(K_STAB, false),
            jpegQuality = p.getInt(K_QUALITY, 95)
        )
    }

    fun save(c: Context, o: Converter.Options) {
        sp(c).edit()
            .putString(K_RES, o.res.name)
            .putBoolean(K_SQUARE, o.square)
            .putBoolean(K_ENHANCE, o.enhance)
            .putBoolean(K_STAB, o.stabilize)
            .putInt(K_QUALITY, o.jpegQuality)
            .apply()
    }
}
