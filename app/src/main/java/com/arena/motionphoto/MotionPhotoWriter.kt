package com.arena.motionphoto

import java.io.ByteArrayOutputStream

/**
 * Menulis file Google Motion Photo.
 *
 * Struktur: [JPEG + XMP APP1] diikuti [MP4 mentah] di-append persis di belakang.
 * Galeri Android mencari awal video dengan menghitung mundur `Length` byte
 * dari akhir file, jadi nilai Length harus PERSIS sama dengan ukuran MP4.
 * Salah satu byte saja -> tidak terdeteksi sebagai motion photo.
 */
object MotionPhotoWriter {

    private const val XMP_NS = "http://ns.adobe.com/xap/1.0/\u0000"

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
            append(" GCamera:MotionPhotoPresentationTimestampUs=\"$keyframeUs\">")
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

    /** Sisipkan segment APP1 XMP setelah SOI dan setelah APP0/APP1 yang sudah ada. */
    fun injectXmp(jpeg: ByteArray, xmp: ByteArray): ByteArray {
        val payload = XMP_NS.toByteArray(Charsets.UTF_8) + xmp
        require(payload.size + 2 <= 0xFFFF) { "XMP terlalu besar untuk satu segment APP1" }

        val segLen = payload.size + 2
        val header = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(),
            ((segLen shr 8) and 0xFF).toByte(),
            (segLen and 0xFF).toByte()
        )

        // cari titik sisip: lewati SOI lalu segment APP0/APP1 yang ada
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

    /** Gabungkan foto + video jadi satu buffer Motion Photo siap tulis. */
    fun build(jpegBytes: ByteArray, mp4Bytes: ByteArray, keyframeUs: Long): ByteArray {
        val xmp = buildXmp(mp4Bytes.size, keyframeUs)
        val jpegWithXmp = injectXmp(jpegBytes, xmp)
        val out = ByteArrayOutputStream(jpegWithXmp.size + mp4Bytes.size)
        out.write(jpegWithXmp)
        out.write(mp4Bytes)
        return out.toByteArray()
    }

    data class VerifyResult(val ok: Boolean, val log: String)

    /** Self-test: berguna banget saat debugging di device yang tidak mau mendeteksi. */
    fun verify(data: ByteArray): VerifyResult {
        val sb = StringBuilder()
        var ok = true
        val text = String(data, 0, minOf(data.size, 65536), Charsets.ISO_8859_1)

        if (text.contains("GCamera:MotionPhoto=\"1\"")) {
            sb.append("✓ tag GCamera:MotionPhoto ada\n")
        } else {
            sb.append("✗ tag MotionPhoto TIDAK ada\n"); ok = false
        }

        val ftyp = indexOf(data, "ftyp".toByteArray())
        if (ftyp > 0) {
            sb.append("✓ MP4 (ftyp) di offset ${ftyp - 4}\n")
            val marker = "Item:Semantic=\"MotionPhoto\" Item:Length=\""
            val mi = text.indexOf(marker)
            if (mi >= 0) {
                val declared = text.substring(mi + marker.length)
                    .substringBefore('"').toIntOrNull() ?: -1
                val actual = data.size - (ftyp - 4)
                if (declared == actual) {
                    sb.append("✓ Length cocok ($declared byte)\n")
                } else {
                    sb.append("✗ Length XMP=$declared vs nyata=$actual\n"); ok = false
                }
            }
        } else {
            sb.append("✗ MP4 tidak ditemukan\n"); ok = false
        }
        sb.append(if (ok) "=> VALID" else "=> BERMASALAH")
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
