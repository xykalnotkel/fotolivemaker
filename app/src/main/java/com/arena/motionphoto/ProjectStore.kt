package com.arena.motionphoto

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * Daftar hasil yang pernah dibuat.
 *
 * Tidak memakai database: berkas dibaca langsung dari MediaStore dengan
 * menyaring nama yang berawalan "MP_". Konsekuensinya jujur — kalau
 * berkasnya dihapus dari galeri, ia hilang juga dari daftar. Itu justru
 * diinginkan supaya daftar tidak pernah menunjuk berkas yang tak ada.
 */
object ProjectStore {

    const val PREFIX = "MP_"

    data class Item(
        val uri: Uri,
        val name: String,
        val dateMs: Long,
        val sizeBytes: Long,
        val width: Int,
        val height: Int
    )

    fun list(context: Context, limit: Int = 60): List<Item> {
        val out = ArrayList<Item>()
        val cols = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )

        val sel = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("$PREFIX%")
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, sel, args, order
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val iW = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val iH = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(iId)
                    out += Item(
                        uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                        ),
                        name = c.getString(iName) ?: "?",
                        dateMs = c.getLong(iDate) * 1000L,
                        sizeBytes = c.getLong(iSize),
                        width = c.getInt(iW),
                        height = c.getInt(iH)
                    )
                }
            }
        }
        return out
    }

    fun delete(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)

    // ---------- daftar album video, untuk dropdown di pemilih ----------

    data class Album(val id: String, val name: String, val count: Int)

    fun videoAlbums(context: Context): List<Album> {
        val map = LinkedHashMap<String, Pair<String, Int>>()
        val cols = arrayOf(
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val iB = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
                val iN = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getString(iB) ?: continue
                    val nm = c.getString(iN) ?: "Lainnya"
                    val cur = map[id]
                    map[id] = if (cur == null) nm to 1 else cur.first to (cur.second + 1)
                }
            }
        }
        return map.map { (id, v) -> Album(id, v.first, v.second) }
            .sortedByDescending { it.count }
    }

    data class Video(
        val uri: Uri,
        val name: String,
        val durationMs: Long,
        val sizeBytes: Long
    )

    /** Video dalam satu album; bucketId null berarti semua album. */
    fun videos(context: Context, bucketId: String?, limit: Int = 500): List<Video> {
        val out = ArrayList<Video>()
        val cols = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )
        val sel = if (bucketId == null) null else "${MediaStore.Video.Media.BUCKET_ID} = ?"
        val args = if (bucketId == null) null else arrayOf(bucketId)

        runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, sel, args,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val iN = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val iD = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val iS = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(iId)
                    out += Video(
                        uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                        ),
                        name = c.getString(iN) ?: "?",
                        durationMs = c.getLong(iD),
                        sizeBytes = c.getLong(iS)
                    )
                }
            }
        }
        return out
    }
}
