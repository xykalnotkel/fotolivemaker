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
 * Efek "HD": membersihkan bintik/noise lalu mempertegas tepi.
 *
 * Berjalan di GPU sebagai bagian dari pipeline Media3, jadi ikut ter-encode
 * ke video hasil — bukan sekadar filter tampilan.
 *
 * Jujur soal batasannya: ini tidak menciptakan detail baru. Yang dikerjakan
 * adalah meredam noise dengan bilateral filter (menjaga tepi) lalu unsharp
 * mask untuk mengembalikan ketajaman. Cocok untuk video agak buram/berbintik.
 */
@UnstableApi
class EnhanceShader(
    context: Context,
    useHdr: Boolean,
    private val denoise: Float,
    private val sharpen: Float
) : BaseGlShaderProgram(useHdr, 1) {

    private val program: GlProgram = try {
        GlProgram(
            context,
            "shaders/vertex_es2.glsl",
            "shaders/fragment_enhance_es2.glsl"
        )
    } catch (e: Exception) {
        throw VideoFrameProcessingException(e)
    }

    private var texelW = 1f / 1920f
    private var texelH = 1f / 1080f

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texelW = 1f / inputWidth.coerceAtLeast(1)
        texelH = 1f / inputHeight.coerceAtLeast(1)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatUniform("uTexelW", texelW)
            program.setFloatUniform("uTexelH", texelH)
            program.setFloatUniform("uDenoise", denoise)
            program.setFloatUniform("uSharpen", sharpen)
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
