# XYStudio / Foto Live - ProGuard Rules (R8)
# Minify diaktifkan di build.gradle.kts, rules ini jaga fungsi penting.

# Keep JNI methods - dipanggil dari Native C++
-keep class livefoto.xystudio.app.NativeHD { *; }

# Keep MotionPhotoWriter utility methods (dipanggil via reflection?)
-keep class livefoto.xystudio.app.MotionPhotoWriter { *; }

# Keep BuildConfig
-keep class livefoto.xystudio.app.BuildConfig { *; }

# Media3 Transformer - UnstableApi classes
-dontwarn androidx.media3.common.util.UnstableApi
-dontwarn androidx.media3.common.VideoFrameProcessingException

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}