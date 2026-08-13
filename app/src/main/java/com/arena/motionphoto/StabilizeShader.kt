package com.arena.motionphoto

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram

/**
 * Menerapkan hasil analisis Stabilizer ke video, frame demi frame.
 *
 * Perbedaan penting dari versi sebelumnya: dulu hasil analisis hanya
 * dipakai untuk menentukan besar zoom, sehingga guncangan tetap ada dan
 * gambar sekadar ter-crop. Sekarang setiap frame benar-benar digeser
 * berlawanan arah guncangannya, berdasarkan tabel koreksi yang dihitung
 * dari lintasan kamera.
 */
@UnstableApi
class StabilizeShader(
    context: Context,
    useHdr: Boolean,
    private val plan: Stabilizer.Plan
) : BaseGlShaderProgram(useHdr, 1) {

    private val program: GlProgram = try {
        GlProgram(
            context,
            "shaders/vertex_es2.glsl",
            "shaders/fragment_stabilize_es2.glsl"
        )
    } catch (e: Exception) {
        throw VideoFrameProcessingException(e)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size =
        Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            // ambil koreksi untuk waktu frame ini
            val (dx, dy) = plan.offsetAt(presentationTimeUs / 1000L)

            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatUniform("uZoom", plan.zoom)
            program.setFloatUniform("uOffsetX", dx)
            program.setFloatUniform("uOffsetY", dy)
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}
