package livefoto.xystudio.app

import java.io.ByteArrayOutputStream

/**
 * Menulis berkas Motion Photo yang dikenali DUA sistem sekaligus:
 *
 *  1. Google / Pixel      -> XMP GCamera + Container di dalam JPEG
 *  2. Samsung (One UI)    -> trailer SEF berisi field "MotionPhoto_Data"
 *
 * Kenapa dua-duanya: galeri Samsung TIDAK membaca XMP GCamera. Ia mencari
 * trailer SEF di ujung berkas. Berkas yang hanya punya XMP akan tampil
 * sebagai foto biasa di HP Samsung — inilah sebab umum "label Live tidak
 * muncul" padahal strukturnya sudah benar menurut spesifikasi Google.
 *
 * Susunan berkas hasil:
 *
 *   [JPEG + APP1 XMP ... FFD9]
 *   [00 00][marker 0x0A30][len nama][MotionPhoto_Data][ MP4 ]
 *   [SEFH][versi][jumlah][entri...][sef_size][SEFT]
 *
 * Kedua pembaca tetap menemukan videonya:
 *   - Samsung : cari penanda "MotionPhoto_Data", video ada tepat sesudahnya
 *   - Google  : hitung mundur Item:Length byte dari akhir berkas
 * Karena itu Item:Length harus mencakup MP4 + trailer SEF, sebab SEF
 * berada SETELAH video.
 */
object MotionPhotoWriter {

    private const val XMP_NS = "http://ns.adobe.com/xap/1.0/\u0000"

    // --- konstanta format Samsung SEF ---
    private val SEF_NAME = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)
    private const val SEF_MARKER = 0x0A30
    private const val SEF_VERSION = 106
    private const val SEF_HEAD = "SEFH"
    private const val SEF_TAIL = "SEFT"
    /** Panjang blok SEF: SEFH+ver+count + entri(12) + sef_size + SEFT */
    private const val SEF_BLOCK_SIZE = 32
    /** Nilai sef_size = seluruh blok tanpa penanda SEFH & SEFT */
    private const val SEF_SIZE_VALUE = 24

    private fun le16(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()
    )

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )

    fun buildXmp(videoLength: Int, keyframeUs: Long): ByteArray {
        val xml = buildString {
            append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">")
            append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
            append("<rdf:Description rdf:about=\"\"")
            append(" xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"")
            append(" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"")
            append(" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"")
            append(" GCamera:MotionPhoto=\"1\"")
            append(" GCamera:MotionPhotoVersion=\"1\"")
            append(" GCamera:MotionPhotoPresentationTimestampUs=\"$keyframeUs\"")
            // MicroVideo: skema lama, sebagian galeri masih memakainya
            append(" GCamera:MicroVideo=\"1\"")
            append(" GCamera:MicroVideoVersion=\"1\"")
            append(" GCamera:MicroVideoOffset=\"$videoLength\"")
            append(" GCamera:MicroVideoPresentationTimestampUs=\"$keyframeUs\">")
            append("<Container:Directory><rdf:Seq>")
            append("<rdf:li rdf:parseType=\"Resource\">")
            append("<Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/>")
            append("</rdf:li>")
            append("<rdf:li rdf:parseType=\"Resource\">")
            append("<Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"$videoLength\" Item:Padding=\"0\"/>")
            append("</rdf:li>")
            append("</rdf:Seq></Container:Directory>")
            append("</rdf:Description></rdf:RDF></x:xmpmeta>")
            append("<?xpacket end=\"w\"?>")
        }
        return xml.toByteArray(Charsets.UTF_8)
    }

    fun injectXmp(jpeg: ByteArray, xmp: ByteArray): ByteArray {
        val payload = XMP_NS.toByteArray(Charsets.UTF_8) + xmp
        require(payload.size + 2 <= 0xFFFF) { "XMP terlalu besar untuk satu segment APP1" }

        val segLen = payload.size + 2
        val header = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(),
            ((segLen shr 8) and 0xFF).toByte(),
            (segLen and 0xFF).toByte()
        )

        var i = 2
        while (i + 4 <= jpeg.size &&
            (jpeg[i].toInt() and 0xFF) == 0xFF &&
            ((jpeg[i + 1].toInt() and 0xFF) == 0xE0 || (jpeg[i + 1].toInt() and 0xFF) == 0xE1)
        ) {
            val l = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            i += 2 + l
        }
        if (i > jpeg.size) i = 2

        val out = ByteArrayOutputStream(jpeg.size + payload.size + 4)
        out.write(jpeg, 0, i)
        out.write(header)
        out.write(payload)
        out.write(jpeg, i, jpeg.size - i)
        return out.toByteArray()
    }

    /** Field SEF: [00 00][marker][panjang nama][nama][video] */
    fun buildSefField(mp4: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(mp4.size + 32)
        out.write(byteArrayOf(0, 0))
        out.write(le16(SEF_MARKER))
        out.write(le32(SEF_NAME.size))
        out.write(SEF_NAME)
        out.write(mp4)
        return out.toByteArray()
    }

    /** Blok indeks SEF di ujung berkas. */
    fun buildSefIndex(fieldSize: Int): ByteArray {
        val out = ByteArrayOutputStream(SEF_BLOCK_SIZE)
        out.write(SEF_HEAD.toByteArray(Charsets.US_ASCII))
        out.write(le32(SEF_VERSION))
        out.write(le32(1))                 // jumlah field
        out.write(byteArrayOf(0, 0))
        out.write(le16(SEF_MARKER))
        out.write(le32(fieldSize))         // offset dihitung mundur dari awal SEF
        out.write(le32(fieldSize))         // ukuran field
        out.write(le32(SEF_SIZE_VALUE))
        out.write(SEF_TAIL.toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    /**
     * Gabungkan jadi satu berkas Motion Photo untuk Google DAN Samsung.
     */
    fun build(jpegBytes: ByteArray, mp4Bytes: ByteArray, keyframeUs: Long): ByteArray {
        val field = buildSefField(mp4Bytes)
        val index = buildSefIndex(field.size)

        // Google menghitung mundur dari akhir berkas, dan SEF ada setelah
        // video, jadi panjangnya harus ikut dihitung.
        val googleLength = mp4Bytes.size + index.size

        val xmp = buildXmp(googleLength, keyframeUs)
        val jpegWithXmp = injectXmp(jpegBytes, xmp)

        val out = ByteArrayOutputStream(jpegWithXmp.size + field.size + index.size)
        out.write(jpegWithXmp)
        out.write(field)
        out.write(index)
        return out.toByteArray()
    }

    /** Offset atom `ftyp` (bukan awal box). -1 kalau tidak ada. */
    fun indexOfFtyp(data: ByteArray): Int =
        indexOf(data, "ftyp".toByteArray(Charsets.US_ASCII))

    /**
     * Ambil payload MP4 dari ekor Motion Photo, tanpa trailer SEF.
     * Dipakai preview / verifikasi decode — bukan spek Google (itu
     * menghitung mundur Item:Length, yang memang mencakup SEF).
     */
    fun extractMp4(data: ByteArray): ByteArray? {
        val ftyp = indexOfFtyp(data)
        if (ftyp < 4) return null
        val start = ftyp - 4
        var end = data.size
        val tailOk = end >= 4 &&
            String(data, end - 4, 4, Charsets.ISO_8859_1) == SEF_TAIL
        if (tailOk) end = (end - SEF_BLOCK_SIZE).coerceAtLeast(start)
        if (end <= start) return null
        return data.copyOfRange(start, end)
    }

    data class VerifyResult(val ok: Boolean, val log: String)

    fun verify(data: ByteArray): VerifyResult {
        val sb = StringBuilder()
        var ok = true
        val text = String(data, 0, minOf(data.size, 65536), Charsets.ISO_8859_1)

        if (text.contains("GCamera:MotionPhoto=\"1\"")) {
            sb.append("✓ XMP GCamera ada\n")
        } else {
            sb.append("✗ XMP GCamera TIDAK ada\n"); ok = false
        }

        // --- jalur Samsung ---
        val nameIdx = indexOf(data, SEF_NAME)
        val tailOk = data.size >= 4 &&
            String(data, data.size - 4, 4, Charsets.ISO_8859_1) == SEF_TAIL
        if (nameIdx > 0 && tailOk) {
            sb.append("✓ Trailer Samsung SEF ada (MotionPhoto_Data)\n")
        } else {
            sb.append("✗ Trailer Samsung SEF tidak lengkap\n"); ok = false
        }

        // --- jalur Google ---
        val ftyp = indexOfFtyp(data)
        if (ftyp > 0) {
            val marker = "Item:Semantic=\"MotionPhoto\" Item:Length=\""
            val mi = text.indexOf(marker)
            if (mi >= 0) {
                val declared = text.substring(mi + marker.length)
                    .substringBefore('"').toIntOrNull() ?: -1
                val actual = data.size - (ftyp - 4)
                if (declared == actual) {
                    sb.append("✓ Item:Length cocok ($declared byte)\n")
                } else {
                    sb.append("✗ Item:Length=$declared, nyata=$actual\n"); ok = false
                }
            }
            sb.append("✓ MP4 (ftyp) di offset ${ftyp - 4}\n")
        } else {
            sb.append("✗ MP4 tidak ditemukan\n"); ok = false
        }

        sb.append(if (ok) "=> VALID (Google + Samsung)" else "=> BERMASALAH")
        return VerifyResult(ok, sb.toString())
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }
}
