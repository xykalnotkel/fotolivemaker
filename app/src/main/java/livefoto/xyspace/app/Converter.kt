package livefoto.xyspace.app

/**
 * XySpace Personal Use License v1.0
 * Copyright 2026 XySpace — Haekal Saputra (KALL)
 * 
 * This source is free for personal, educational, non-commercial use.
 * Commercial use requires separate written permission from XySpace.
 * See LICENSE file for full terms.
 */

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri

/**
 * Facade utama — semua fungsi delegate ke file specialist masing-masing:
 * VideoMath, BitmapProcessor, VideoEncoder, MediaStoreWriter.
 *
 * Data classes (Options, Plan, AspectRatio, Res, dll) tetap di sini
 * biar kode lain (HomeActivity, MainActivity, Settings, dll) gak perlu
 * impor ulang. Bertahap bisa dipindah ke VideoMath kalo semua referensi
 * udah di-update.
 */
object Converter {

    /** Durasi klip 3 detik — konstanta utama. */
    const val TARGET_CLIP_MS = VideoMath.TARGET_CLIP_MS

    // ── Data Types ──────────────────────────────────────────────

    enum class AspectRatio(val label: String, val ratioW: Int, val ratioH: Int) {
        ORIGINAL("Asli", 0, 0),
        RATIO_9_16("9:16", 9, 16),
        RATIO_3_4("3:4", 3, 4),
        RATIO_1_1("1:1", 1, 1),
        RATIO_4_3("4:3", 4, 3),
        RATIO_16_9("16:9", 16, 9);
        fun isOriginal(): Boolean = this == ORIGINAL
    }

    enum class Res(val label: String, val height: Int) {
        P720("720p", 720),
        P1080("1080p", 1080),
        P1440("2K", 1440),
        P2160("4K UHD", 2160),
        SOURCE("Asli", 0)
    }

    data class Options(
        val aspectRatio: AspectRatio = AspectRatio.RATIO_9_16,
        val res: Res = Res.P1080,
        val enhance: Boolean = false,
        val stabilize: Boolean = false,
        val jpegQuality: Int = 96
    ) {
        val square: Boolean get() = aspectRatio == AspectRatio.RATIO_1_1

        @Suppress("unused")
        constructor(
            square: Boolean,
            res: Res = Res.P1080,
            enhance: Boolean = false,
            stabilize: Boolean = false,
            jpegQuality: Int = 96
        ) : this(
            aspectRatio = if (square) AspectRatio.RATIO_1_1 else AspectRatio.RATIO_9_16,
            res = res, enhance = enhance, stabilize = stabilize, jpegQuality = jpegQuality
        )

        fun heightFor(sourceHeight: Int): Int =
            if (res == Res.SOURCE) sourceHeight.coerceAtLeast(2) else res.height
    }

    data class Plan(val totalMs: Long, val startMs: Long, val durationMs: Long, val keyframeOffsetMs: Long)

    data class ClipSliders(
        val start: Triple<Float, Float, Float>,
        val key: Triple<Float, Float, Float>,
        val clipSec: Float,
        val showStart: Boolean
    )

    data class Result(val uri: Uri, val plan: Plan, val verifyLog: String, val bytes: Int)
    data class VideoResult(val uri: Uri, val plan: Plan, val width: Int, val height: Int, val bytes: Long)

    // ── Delegasi ke VideoMath ───────────────────────────────────

    fun plan(totalMs: Long): Plan = VideoMath.plan(totalMs).let {
        Plan(it.totalMs, it.startMs, it.durationMs, it.keyframeOffsetMs)
    }
    fun sanitize(totalMs: Long, startMs: Long, durationMs: Long, keyframeOffsetMs: Long): Plan =
        VideoMath.sanitize(totalMs, startMs, durationMs, keyframeOffsetMs).let {
            Plan(it.totalMs, it.startMs, it.durationMs, it.keyframeOffsetMs)
        }
    fun sliderRound(v: Float) = VideoMath.sliderRound(v)
    fun sliderRange(from: Float, to: Float, value: Float) = VideoMath.sliderRange(from, to, value)
    fun clipSliders(totalMs: Long, startSec: Float? = null, keySec: Float? = null) =
        VideoMath.clipSliders(totalMs, startSec, keySec).let {
            ClipSliders(it.start, it.key, it.clipSec, it.showStart)
        }
    fun evenUp(v: Number) = VideoMath.evenUp(v)
    fun calculateDimensions(srcW: Int, srcH: Int, opts: Options) =
        VideoMath.calculateDimensions(srcW, srcH, toVideoOpts(opts))
    fun videoDurationMs(context: Context, uri: Uri) = VideoMath.videoDurationMs(context, uri)
    fun videoSize(context: Context, uri: Uri) = VideoMath.videoSize(context, uri)
    fun shouldApplyRotation(rotRaw: Int, encW: Int, encH: Int, bmpW: Int, bmpH: Int) =
        VideoMath.shouldApplyRotation(rotRaw, encW, encH, bmpW, bmpH)
    fun restoreSharpen(stabilize: Boolean) = VideoMath.restoreSharpen(stabilize)

    private fun toVideoOpts(o: Options) = VideoMath.Options(
        aspectRatio = VideoMath.AspectRatio.valueOf(o.aspectRatio.name),
        res = VideoMath.Res.valueOf(o.res.name),
        enhance = o.enhance, stabilize = o.stabilize, jpegQuality = o.jpegQuality
    )

    // ── Delegasi ke BitmapProcessor ──────────────────────────────

    fun extractFrame(context: Context, uri: Uri, atMs: Long, opts: Options,
                     targetW: Int, targetH: Int, applyLook: Boolean = true) =
        BitmapProcessor.extractFrame(context, uri, atMs, opts, targetW, targetH, applyLook)

    fun extractTimelineFrames(context: Context, uri: Uri, totalMs: Long, count: Int = 10) =
        BitmapProcessor.extractTimelineFrames(context, uri, totalMs, count)

    fun Bitmap.toJpeg(quality: Int = 96): ByteArray {
        val s = java.io.ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(70, 100), s)
        return s.toByteArray()
    }

    // ── Delegasi ke VideoEncoder ────────────────────────────────

    suspend fun convert(
        context: Context, uri: Uri, opts: Options,
        log: (String) -> Unit, progress: (Int) -> Unit, planHint: Plan? = null
    ): Result {
        val r = VideoEncoder.convert(context, uri, opts, log, progress,
            planHint?.let { VideoMath.Plan(it.totalMs, it.startMs, it.durationMs, it.keyframeOffsetMs) })
        return Result(r.uri, Plan(r.plan.totalMs, r.plan.startMs, r.plan.durationMs, r.plan.keyframeOffsetMs), r.verifyLog, r.bytes)
    }

    suspend fun convertVideo(
        context: Context, uri: Uri, opts: Options,
        log: (String) -> Unit, progress: (Int) -> Unit, planHint: Plan? = null
    ): VideoResult {
        val r = VideoEncoder.convertVideo(context, uri, opts, log, progress,
            planHint?.let { VideoMath.Plan(it.totalMs, it.startMs, it.durationMs, it.keyframeOffsetMs) })
        return VideoResult(r.uri, Plan(r.plan.totalMs, r.plan.startMs, r.plan.durationMs, r.plan.keyframeOffsetMs), r.width, r.height, r.bytes)
    }
}