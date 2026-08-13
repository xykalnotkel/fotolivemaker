package com.arena.motionphoto

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Banner tidak butuh jaringan. Internet dipakai HANYA saat pengguna
 * mengetuk update (cek tag + unduh APK). Video tidak pernah diunggah.
 */
object UpdateCheck {

    private const val API =
        "https://api.github.com/repos/xykalnotkel/fotolivemaker/releases/latest"
    const val WEB = "https://github.com/xykalnotkel/fotolivemaker/releases/latest"
    private const val PREF = "update_check"
    private const val K_TAG = "tag"
    private const val K_URL = "url"
    private const val K_APK = "apk"
    private const val K_AT = "checked_at"

    data class Info(
        val tag: String,
        val pageUrl: String,
        val apkUrl: String?,
        val newer: Boolean
    )

    fun online(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun cached(context: Context): Info? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val tag = p.getString(K_TAG, null) ?: return null
        val url = p.getString(K_URL, null) ?: WEB
        val apk = p.getString(K_APK, null)
        return Info(tag, url, apk, isNewer(BuildConfig.VERSION_NAME, tag))
    }

    fun fetch(context: Context): Info? = runCatching {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "LivePhotoMaker")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val json = JSONObject(body)
        val tag = json.optString("tag_name").ifBlank { return null }
        val url = json.optString("html_url").ifBlank { WEB }
        var apk: String? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apk = a.optString("browser_download_url").ifBlank { null }
                    break
                }
            }
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(K_TAG, tag)
            .putString(K_URL, url)
            .putString(K_APK, apk)
            .putLong(K_AT, System.currentTimeMillis())
            .apply()
        Info(tag, url, apk, isNewer(BuildConfig.VERSION_NAME, tag))
    }.getOrNull()

    fun downloadApk(apkUrl: String, dest: File, progress: (Int) -> Unit) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".part")
        val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LivePhotoMaker")
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(16 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    read += n
                    if (total > 0) progress(((read * 100) / total).toInt().coerceIn(0, 99))
                }
            }
        }
        conn.disconnect()
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) tmp.copyTo(dest, overwrite = true)
        progress(100)
    }

    fun apkFile(context: Context): File =
        File(File(context.cacheDir, "update"), "LivePhotoMaker.apk")

    fun isNewer(installed: String, remoteTag: String): Boolean {
        val a = nums(installed)
        val b = nums(remoteTag)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (bv > av) return true
            if (bv < av) return false
        }
        return false
    }

    private fun nums(raw: String): List<Int> {
        val core = raw.trim().removePrefix("v").substringBefore("-")
        return core.split('.').mapNotNull { it.toIntOrNull() }
    }
}
