package com.arena.motionphoto

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
