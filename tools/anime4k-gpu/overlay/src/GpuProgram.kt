package org.jellyfin.mobile.player.videoproxy

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20 as GL
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import java.nio.ByteBuffer

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

    private val bench = context as? OverlayTrialActivity
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
        active = !hdr && OverlayTrial.supportsSize(width, height)
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
                Log.i(OverlayTrial.TAG, "active GPU CNNx2S ${width}x$height -> ${width*2}x${height*2} " +
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
                GL.glFinish()
                Log.i(OverlayTrial.TAG, "gpuFrame pts=$presentationTimeUs total=${System.nanoTime()-start}")
                if (++frames == 72 && bench.intent.getBooleanExtra("capture", false)) {
                    saveCrop("enhanced")
                    drawOutput(inputTexId, features, false)
                    saveCrop("bilinear")
                    drawOutput(inputTexId, features, true)
                    Log.i(OverlayTrial.TAG, "captured paired crops pts=$presentationTimeUs")
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

    /** Capture the same central patch twice from one decoded frame, only in a separate visual trial. */
    private fun saveCrop(label: String) {
        val side = 512
        val bytes = ByteBuffer.allocateDirect(side * side * 4)
        GL.glReadPixels(width - side / 2, height - side / 2, side, side, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, bytes)
        GlUtil.checkGlError()
        val topDown = ByteBuffer.allocateDirect(bytes.capacity())
        for (y in side - 1 downTo 0) {
            bytes.limit((y + 1) * side * 4)
            bytes.position(y * side * 4)
            topDown.put(bytes)
        }
        topDown.flip()
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(topDown)
        context.cacheDir.resolve("anime4k-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
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
