package com.arena.motionphoto

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

/** Statistik penggunaan penyimpanan, dihitung dari berkas nyata. */
object Stats {

    data class Info(
        val projectCount: Int,
        val projectBytes: Long,
        val cacheBytes: Long,
        val appDataBytes: Long,
        val freeBytes: Long,
        val totalBytes: Long
    ) {
        val usedBytes: Long get() = totalBytes - freeBytes
        val usedPercent: Int
            get() = if (totalBytes <= 0) 0
                    else ((usedBytes * 100) / totalBytes).toInt()
    }

    fun collect(context: Context): Info {
        val items = ProjectStore.list(context, limit = 10_000)
        val projectBytes = items.sumOf { it.sizeBytes }

        val cache = dirSize(context.cacheDir) + dirSize(context.externalCacheDir)
        val data = dirSize(context.filesDir) +
            dirSize(File(context.applicationInfo.dataDir, "shared_prefs")) +
            dirSize(File(context.applicationInfo.dataDir, "databases"))

        var free = 0L
        var total = 0L
        runCatching {
            val fs = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            free = fs.availableBytes
            total = fs.totalBytes
        }

        return Info(items.size, projectBytes, cache, data, free, total)
    }

    fun clearCache(context: Context): Long {
        val before = dirSize(context.cacheDir) + dirSize(context.externalCacheDir)
        runCatching { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
        runCatching { context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() } }
        return before
    }

    private fun dirSize(dir: File?): Long {
        dir ?: return 0L
        if (!dir.exists()) return 0L
        return runCatching {
            dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    fun human(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
