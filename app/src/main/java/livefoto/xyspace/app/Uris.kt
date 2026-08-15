package livefoto.xyspace.app

/**
 * XySpace Personal Use License v1.0
 * Copyright 2026 XySpace — Haekal Saputra (KALL)
 * 
 * This source is free for personal, educational, non-commercial use.
 * Commercial use requires separate written permission from XySpace.
 * See LICENSE file for full terms.
 */

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Looper

/** Teruskan hak baca URI ke activity berikutnya (Share / Photo Picker). */
fun Intent.withReadGrant(uri: Uri): Intent {
    data = uri
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    clipData = ClipData.newRawUri("media", uri)
    return this
}

fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block()
    else android.os.Handler(Looper.getMainLooper()).post(block)
}
