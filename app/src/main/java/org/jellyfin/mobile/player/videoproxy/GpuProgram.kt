package org.jellyfin.mobile.player.videoproxy

import android.content.Context
import android.opengl.GLES20 as GL
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram

/** Four source-resolution fragment passes followed by 2x pixel shuffle and base-image composition. */
@UnstableApi
internal class GpuProgram(private val context: Context, private val hdr: Boolean) : BaseGlShaderProgram(hdr, 1) {
    private companion object {
        const val VERTEX = "attribute vec4 pos; void main() { gl_Position = pos; }"
        const val COMPOSE = """
            precision highp float;
            uniform highp sampler2D image, residual;
            uniform vec2 outputSize;
            uniform float strength;
            void main() {
                vec3 rgb = texture2D(image, gl_FragCoord.xy / outputSize).rgb;
                if (strength > 0.0) {
                    vec2 pixel = floor(gl_FragCoord.xy);
                    vec4 lanes = texture2D(residual, (floor(pixel * 0.5) + 0.5) * 2.0 / outputSize);
                    vec2 phase = mod(pixel, 2.0);
                    // GL bottom row is the model's bottom subpixel (B/A lanes).
                    float delta = mix(mix(lanes.b, lanes.a, phase.x), mix(lanes.r, lanes.g, phase.x), phase.y);
                    rgb += delta;
                }
                gl_FragColor = vec4(rgb, 1.0);
            }"""
    }

    private val bench = context as? OverlayBenchmark
    private val passes = mutableListOf<GlProgram>()
    private val compose = program(COMPOSE)
    private val textures = IntArray(2)
    private val fbos = IntArray(2)
    private val destination = IntArray(1)
    private val outputSize = FloatArray(2)
    private var width = 0
    private var height = 0
    private var active = false
    private var frames = 0

    private fun program(fragment: String) = GlProgram(VERTEX, fragment).apply {
        setBufferAttribute("pos", GlUtil.getNormalizedCoordinateBounds(), 4)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (width != inputWidth || height != inputHeight) freeTargets()
        width = inputWidth
        height = inputHeight
        active = !hdr && OverlayEnhancement.supportsSize(width, height)
        if (active && textures[0] == 0) {
            try {
                val started = System.nanoTime()
                if (passes.isEmpty()) {
                    for (i in 0 until 4) {
                        passes += program(context.assets.open("anime4k/pass$i.frag").bufferedReader().use { it.readText() })
                    }
                }
                for (pass in passes) pass.setFloatsUniform("texel", floatArrayOf(1f / width, 1f / height))
                for (i in textures.indices) {
                    textures[i] = GlUtil.createTexture(width, height, true) // signed RGBA16F features
                    fbos[i] = GlUtil.createFboForTexture(textures[i])
                    check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE)
                }
                Log.i(OverlayEnhancement.TAG, "active GPU CNNx2S ${width}x$height -> ${width*2}x${height*2} " +
                    "renderer=${GL.glGetString(GL.GL_RENDERER)} setupMs=${(System.nanoTime()-started)/1e6}")
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e)
            }
        }
        val factor = if (active) 2 else 1
        outputSize[0] = (width * factor).toFloat()
        outputSize[1] = (height * factor).toFloat()
        return Size(width * factor, height * factor)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val start = System.nanoTime()
            var features = inputTexId
            if (active) {
                GL.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, destination, 0)
                for ((i, pass) in passes.withIndex()) {
                    val slot = i % 2
                    GlUtil.focusFramebufferUsingCurrentContext(fbos[slot], width, height)
                    pass.use()
                    pass.setSamplerTexIdUniform("features", features, 0, GL.GL_NEAREST)
                    pass.bindAttributesAndUniforms()
                    GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
                    features = textures[slot]
                }
                GlUtil.focusFramebufferUsingCurrentContext(destination[0], width * 2, height * 2)
            }
            drawOutput(inputTexId, features, active)
            GlUtil.checkGlError()
            if (bench != null && active) {
                bench.onCnnFrame(++frames, presentationTimeUs, start, width, height) { enhanced ->
                    drawOutput(inputTexId, features, enhanced)
                }
            }
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun drawOutput(image: Int, features: Int, enhanced: Boolean) {
        compose.use()
        compose.setSamplerTexIdUniform("image", image, 0, GL.GL_LINEAR)
        compose.setSamplerTexIdUniform("residual", features, 1, if (active) GL.GL_NEAREST else GL.GL_LINEAR)
        compose.setFloatsUniform("outputSize", outputSize)
        compose.setFloatUniform("strength", if (enhanced) 1f else 0f)
        compose.bindAttributesAndUniforms()
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun freeTargets() {
        GL.glDeleteFramebuffers(2, fbos, 0)
        GL.glDeleteTextures(2, textures, 0)
        fbos.fill(0)
        textures.fill(0)
    }

    override fun release() {
        freeTargets()
        passes.forEach { it.delete() }
        compose.delete()
        super.release()
    }
}
