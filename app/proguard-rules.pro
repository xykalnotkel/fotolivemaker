# XySpace / Foto Live - ProGuard Rules (R8)
# Minify diaktifkan di build.gradle.kts.

# --- Keep JNI ---
-keep class livefoto.xyspace.app.NativeHD { *; }

# --- Keep semua class di package app (R8 suka optimize databinding/viewbinding) ---
-keep class livefoto.xyspace.app.** { *; }
-keep class livefoto.xyspace.app.databinding.** { *; }
-keep class livefoto.xyspace.app.** { public *; }

# --- Keep BuildConfig ---
-keep class livefoto.xyspace.app.BuildConfig { *; }

# --- Keep R (resources) ---
-keep class **.R$* { *; }

# --- Media3 Transformer ---
-dontwarn androidx.media3.common.util.UnstableApi
-dontwarn androidx.media3.common.VideoFrameProcessingException
-keep class androidx.media3.** { *; }

# --- Kotlin Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# --- Material Components ---
-keep class com.google.android.material.** { *; }

# --- AndroidX ---
-keep class androidx.core.** { *; }
-keep class androidx.appcompat.** { *; }

# --- Gson / JSON (UpdateCheck uses JSONObject) ---
-keep class org.json.** { *; }