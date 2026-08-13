package com.arena.motionphoto

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cek rilis terbaru di GitHub. Satu-satunya alasan app ini punya
 * izin INTERNET — tidak mengunggah video, tidak analytics.
 */
object UpdateCheck {

    private const val API =
        "https://api.github.com/repos/xykalnotkel/fotolivemaker/releases/latest"
    private const val PREF = "update_check"
    private const val K_TAG = "tag"
    private const val K_URL = "url"
    private const val K_AT = "checked_at"
    private const val TTL_MS = 3 * 60 * 60 * 1000L

    data class Info(val tag: String, val pageUrl: String, val newer: Boolean)

    fun cached(context: Context): Info? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val tag = p.getString(K_TAG, null) ?: return null
        val url = p.getString(K_URL, null) ?: return null
        return Info(tag, url, isNewer(BuildConfig.VERSION_NAME, tag))
    }

    fun fetch(context: Context, force: Boolean = false): Info? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val age = System.currentTimeMillis() - p.getLong(K_AT, 0L)
        if (!force && age in 1 until TTL_MS) return cached(context)

        return runCatching {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "LivePhotoMaker")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val tag = json.optString("tag_name").ifBlank { return null }
            val url = json.optString("html_url").ifBlank {
                "https://github.com/xykalnotkel/fotolivemaker/releases/latest"
            }
            p.edit().putString(K_TAG, tag).putString(K_URL, url)
                .putLong(K_AT, System.currentTimeMillis()).apply()
            Info(tag, url, isNewer(BuildConfig.VERSION_NAME, tag))
        }.getOrElse { cached(context) }
    }

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
