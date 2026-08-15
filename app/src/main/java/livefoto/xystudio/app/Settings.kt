package livefoto.xystudio.app

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton

/** Pengaturan yang tersimpan antar sesi. */
object Settings {

    private const val FILE = "settings"
    private const val K_SCHEMA = "schema"
    private const val CURRENT_SCHEMA = 2
    private const val K_RES = "res"
    private const val K_ASPECT_RATIO = "aspect_ratio"
    private const val K_SQUARE = "square"
    private const val K_ENHANCE = "enhance"
    private const val K_STAB = "stabilize"
    private const val K_QUALITY = "jpeg_quality"
    private const val K_THEME = "theme"
    private const val K_KEEP_SCREEN_ON = "keep_screen_on"
    private const val K_SHOW_SPLASH = "show_splash"
    private const val K_HIGH_CONTRAST = "high_contrast"
    private const val K_HAPTICS = "haptics"
    private const val K_REDUCE_MOTION = "reduce_motion"
    private const val K_EXPORT_FORMAT = "export_format"
    private const val K_UHD_BITRATE = "uhd_bitrate"
    private const val K_HW_ACCEL = "hw_accel"
    private const val K_NDK_ENABLED = "ndk_enabled"
    private const val K_LAYER_BLEND = "layer_blend"
    private const val K_CACHE_LIMIT = "cache_limit"

    /** 0 = ikut sistem, 1 = terang, 2 = gelap. */
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(c: Context): Converter.Options {
        val p = sp(c)
        val resName = p.getString(K_RES, Converter.Res.P1080.name)!!
        val res = runCatching { Converter.Res.valueOf(resName) }
            .getOrDefault(Converter.Res.P1080)

        val storedRatio = p.getString(K_ASPECT_RATIO, null)?.let {
            runCatching { Converter.AspectRatio.valueOf(it) }.getOrNull()
        }
        val legacySquare = p.getBoolean(K_SQUARE, false)
        val oldSchema = p.getInt(K_SCHEMA, 0)
        val aspectRatio = when {
            legacySquare -> Converter.AspectRatio.RATIO_1_1
            oldSchema < CURRENT_SCHEMA &&
                (storedRatio == null || storedRatio == Converter.AspectRatio.ORIGINAL) ->
                Converter.AspectRatio.RATIO_9_16
            else -> storedRatio ?: Converter.AspectRatio.RATIO_9_16
        }

        if (oldSchema < CURRENT_SCHEMA) {
            p.edit {
                putInt(K_SCHEMA, CURRENT_SCHEMA)
                putString(K_ASPECT_RATIO, aspectRatio.name)
            }
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
    fun showSplash(c: Context): Boolean = sp(c).getBoolean(K_SHOW_SPLASH, true)

    fun setShowSplash(c: Context, enabled: Boolean) {
        sp(c).edit { putBoolean(K_SHOW_SPLASH, enabled) }
    }

    fun setKeepScreenOn(c: Context, enabled: Boolean) {
        sp(c).edit { putBoolean(K_KEEP_SCREEN_ON, enabled) }
    }

    fun isHighContrast(c: Context): Boolean = sp(c).getBoolean(K_HIGH_CONTRAST, false)

    fun setHighContrast(c: Context, enabled: Boolean) {
        sp(c).edit { putBoolean(K_HIGH_CONTRAST, enabled) }
    }

    fun isHapticsEnabled(c: Context): Boolean = sp(c).getBoolean(K_HAPTICS, true)

    fun setHapticsEnabled(c: Context, enabled: Boolean) {
        sp(c).edit { putBoolean(K_HAPTICS, enabled) }
    }

    fun isReduceMotion(c: Context): Boolean = sp(c).getBoolean(K_REDUCE_MOTION, false)

    fun setReduceMotion(c: Context, enabled: Boolean) {
        sp(c).edit { putBoolean(K_REDUCE_MOTION, enabled) }
    }

    /** Harus dipanggil sebelum super.onCreate agar theme ikut saat inflate. */
    fun prepareActivity(activity: Activity) {
        if (isHighContrast(activity)) {
            activity.setTheme(R.style.Theme_MotionPhoto_HighContrast)
        }
        if (isReduceMotion(activity)) activity.window.setWindowAnimations(0)
    }

    /**
     * High Contrast mengubah token theme seluruh layar, menebalkan border,
     * menghilangkan alpha redup, dan memperjelas target interaksi. Bukan mode
     * pembesaran teks.
     */
    fun applyAccessibility(activity: Activity, root: View) {
        applySystemBars(activity, root)
        if (isReduceMotion(activity)) activity.window.setWindowAnimations(0)
        if (!isHighContrast(activity)) return

        val density = activity.resources.displayMetrics.density
        val border = color(activity, R.attr.appLineStrong)
        val minTouch = (48 * density).toInt()
        val stroke = (2 * density).toInt().coerceAtLeast(2)

        fun visit(view: View) {
            if (view.alpha in 0.25f..0.99f) view.alpha = 1f
            if (view.isClickable) {
                view.isFocusable = true
                view.minimumWidth = maxOf(view.minimumWidth, minTouch)
                view.minimumHeight = maxOf(view.minimumHeight, minTouch)
            }
            (view.background?.mutate() as? GradientDrawable)?.setStroke(stroke, border)
            if (view is MaterialButton) view.strokeWidth = stroke
            if (view is TextView && view.textSize / density <= 12f) {
                view.setTypeface(view.typeface, Typeface.BOLD)
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
        }
        visit(root)
    }

    /** Edge-to-edge aman untuk Android 7 sampai 17, termasuk cutout. */
    private fun applySystemBars(activity: Activity, root: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)

        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = initialLeft + bars.left,
                top = initialTop + bars.top,
                right = initialRight + bars.right,
                bottom = initialBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)

        val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(activity.window, root).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    @ColorInt
    fun color(context: Context, @AttrRes attr: Int): Int {
        val value = TypedValue()
        require(context.theme.resolveAttribute(attr, value, true)) { "Theme attr $attr tidak ada" }
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId)
        else value.data
    }

    fun triggerHaptic(view: View) {
        if (isHapticsEnabled(view.context)) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    fun setTheme(c: Context, mode: Int) {
        sp(c).edit { putInt(K_THEME, mode) }
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


    // ====== Video Editor Extended Settings ======
    fun exportFormat(c: Context): String = sp(c).getString(K_EXPORT_FORMAT, "MP4") ?: "MP4"
    fun setExportFormat(c: Context, fmt: String) { sp(c).edit { putString(K_EXPORT_FORMAT, fmt) } }

    fun uhdBitrate(c: Context): Int = sp(c).getInt(K_UHD_BITRATE, 20) // Mbps
    fun setUhdBitrate(c: Context, mbps: Int) { sp(c).edit { putInt(K_UHD_BITRATE, mbps) } }

    fun hwAccel(c: Context): Boolean = sp(c).getBoolean(K_HW_ACCEL, true)
    fun setHwAccel(c: Context, v: Boolean) { sp(c).edit { putBoolean(K_HW_ACCEL, v) } }

    fun ndkEnabled(c: Context): Boolean = sp(c).getBoolean(K_NDK_ENABLED, true)
    fun setNdkEnabled(c: Context, v: Boolean) { sp(c).edit { putBoolean(K_NDK_ENABLED, v) } }

    fun layerBlend(c: Context): String = sp(c).getString(K_LAYER_BLEND, "Normal") ?: "Normal"
    fun setLayerBlend(c: Context, blend: String) { sp(c).edit { putString(K_LAYER_BLEND, blend) } }

    fun cacheLimitMB(c: Context): Int = sp(c).getInt(K_CACHE_LIMIT, 500)
    fun setCacheLimitMB(c: Context, mb: Int) { sp(c).edit { putInt(K_CACHE_LIMIT, mb) } }


    fun save(c: Context, o: Converter.Options) {
        sp(c).edit {
            putInt(K_SCHEMA, CURRENT_SCHEMA)
            putString(K_RES, o.res.name)
            putString(K_ASPECT_RATIO, o.aspectRatio.name)
            putBoolean(K_SQUARE, o.aspectRatio == Converter.AspectRatio.RATIO_1_1)
            putBoolean(K_ENHANCE, o.enhance)
            putBoolean(K_STAB, o.stabilize)
            putInt(K_QUALITY, o.jpegQuality)
        }
    }
}
