package org.jellyfin.mobile.player.videoproxy

import android.content.Context
import android.opengl.GLES20 as GL
import android.util.Log
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil

internal object PlaceboNative {
    external fun create(sources: Array<String>): Long
    external fun draw(handle: Long, texture: Int, width: Int, height: Int, fbo: Int, targetWidth: Int, targetHeight: Int): Boolean
    external fun release(handle: Long)
}

internal typealias EffectStatus = (String, String?, Int, JSONObject?) -> Unit

/** Only bundled GLSL is executable. Web supplies effect IDs, dimensions and numerical CRT settings. */
internal class MenuPlan(data: JSONObject) {
    private val effects = data.getJSONArray("effects")
    private val dimensions = data.getJSONObject("target")
    val width = dimensions.getInt("width")
    val height = dimensions.getInt("height")
    val monitorGpu = data.optString("monitor") == "gpu"
    val singleCnn = effects.length() == 1 && effects.getJSONObject(0).getString("id") == "anime4k/Upscale/CNNx2S"
    init {
        require(width in 1..32768 && height in 1..32768 && effects.length() in 1..64)
    }

    fun compile(context: Context, w: Int, h: Int, maxTexture: Int): Array<String> {
        fun asset(name: String) = context.assets.open("models/$name").bufferedReader().use { it.readText() }
        val catalog = JSONObject(asset("catalog.json"))
        val list = (0 until effects.length()).map { effects.getJSONObject(it) }
        val scales = list.map { catalog.optJSONObject(it.getString("id"))?.getInt("scale") ?: 1 }
        var currentWidth = w
        var currentHeight = h
        val shaders = mutableListOf<String>()
        fun checkSize() = require(currentWidth in 1..maxTexture && currentHeight in 1..maxTexture) { "EnhancementResolutionLimit" }
        fun resize(x: Int, y: Int) {
            currentWidth = x
            currentHeight = y
            checkSize()
            shaders += "//!HOOK MAIN\n//!BIND MAIN\n//!WIDTH $x\n//!HEIGHT $y\nvec4 hook(){return MAIN_tex(MAIN_pos);}\n"
        }
        for ((index, effect) in list.withIndex()) {
            val id = effect.getString("id")
            when (id) {
                "core/Resize/ToTarget", "core/internal/ResizeLinear" -> resize(width, height)
                "core/Filter/CRT", "core/Filter/Monochrome" -> {
                    currentWidth = width
                    currentHeight = height
                    checkSize()
                    val header = "//!HOOK MAIN\n//!BIND MAIN\n//!WIDTH $width\n//!HEIGHT $height\n"
                    shaders += if (id.endsWith("Monochrome")) {
                        header + "vec4 hook(){vec4 c=MAIN_tex(MAIN_pos); return vec4(vec3(dot(c.rgb,vec3(0.2126,0.7152,0.0722))),c.a);}\n"
                    } else {
                        val schema = JSONObject(asset("crt.json"))
                        val params = effect.optJSONObject("params")
                        var template = asset("crt.glsl").replace("@w@", width.toString()).replace("@h@", height.toString())
                        for (key in schema.keys()) {
                            val control = schema.getJSONObject(key)
                            val value = params?.optDouble(key)?.takeIf { it.isFinite() } ?: control.getDouble("defaultValue")
                            val bounded = value.coerceIn(control.getDouble("min"), control.getDouble("max"))
                            template = template.replace("@$key@", String.format(Locale.ROOT, "%.4f", bounded))
                        }
                        template
                    }
                }
                else -> {
                    val model = catalog.optJSONObject(id) ?: error("EnhancementModeInvalid")
                    currentWidth = Math.multiplyExact(currentWidth, scales[index])
                    currentHeight = Math.multiplyExact(currentHeight, scales[index])
                    checkSize()
                    shaders += asset(model.getString("file")).replace("NIJI_0_", "NIJI_${index}_")
                }
            }
            val remaining = scales.drop(index + 1).fold(1.0) { product, s -> product * s }
            if (scales[index] > 1 && remaining > 1 &&
                (currentWidth > width / remaining * 1.1 || currentHeight > height / remaining * 1.1)) {
                resize(ceil(width / remaining).toInt(), ceil(height / remaining).toInt())
            }
        }
        if (currentWidth != width || currentHeight != height) resize(width, height)
        // libplacebo names its RGB hook MAINPRESUB. Some Adreno preprocessors fail to
        // expand its MAIN_tex / HOOKED_tex aliases when followed by an argument list.
        return shaders.map {
            it.replace(Regex("""\b(?:MAIN|HOOKED)_"""), "MAINPRESUB_")
                // Desktop GLSL permits runtime const initializers and mixed integer types;
                // GLSL ES requires these ArtCNN workgroup locals to be ordinary signed values.
                .replace("const uvec2 local_xy = gl_LocalInvocationID.xy;", "ivec2 local_xy = ivec2(gl_LocalInvocationID.xy);")
                .replace("for (uint y = local_xy.y;", "for (int y = local_xy.y;")
                .replace("for (uint x = local_xy.x;", "for (int x = local_xy.x;")
                .replace(Regex("""(?m)^(\s+)const (?=(?:ivec2 (?:input_base|output_base)|F inp_|V4 inp_))"""), "$1")
        }.toTypedArray()
    }
}

@UnstableApi
internal class MenuGpuEffect(private val plan: MenuPlan, private val sourceFps: Float?, private val status: EffectStatus) : GlEffect {
    fun outputFailed() = status("error", "EnhancementFrameFailed", 0, null)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        Program(context, useHdr, plan, sourceFps, status)

    private class Program(
        val context: Context, val hdr: Boolean, val plan: MenuPlan, val sourceFps: Float?, val status: EffectStatus,
    ) : BaseGlShaderProgram(hdr, 1) {
        private val copy = GlProgram("attribute vec4 pos; varying vec2 uv; void main(){gl_Position=pos;uv=pos.xy*.5+.5;}",
            "precision highp float; varying vec2 uv; uniform sampler2D src; void main(){gl_FragColor=texture2D(src,uv);}").apply {
            setBufferAttribute("pos", GlUtil.getNormalizedCoordinateBounds(), 4)
        }
        private val framebuffer = IntArray(1)
        private var width = 0
        private var height = 0
        private var handle = 0L
        private var fast: GpuProgram? = null
        private var stats: GpuFrameStats? = null
        private val waitsForGpu = context is OverlayBenchmark || plan.monitorGpu

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            reset()
            width = inputWidth
            height = inputHeight
            if (hdr) {
                status("suspended", "EnhancementHdr", 0, null)
                return Size(width, height)
            }
            try {
                val started = System.nanoTime()
                val stages: Int
                val max = IntArray(1)
                GL.glGetIntegerv(GL.GL_MAX_TEXTURE_SIZE, max, 0)
                require(plan.width <= max[0] && plan.height <= max[0]) { "EnhancementResolutionLimit" }
                if (plan.singleCnn && OverlayEnhancement.supportsSize(width, height) && plan.width == width*2 && plan.height == height*2) {
                    fast = GpuProgram(context, false)
                    fast!!.configure(width, height)
                    stages = 5 // Four CNN convolutions and the 2x composition pass.
                } else {
                    val sources = plan.compile(context, width, height, max[0])
                    stages = sources.sumOf { shader -> shader.lineSequence().count { it.startsWith("//!HOOK ") } }
                    handle = PlaceboNative.create(sources)
                    check(handle != 0L) { "EnhancementCompileFailed" }
                }
                stats = GpuFrameStats(
                    if (fast != null) "CNNx2S · OpenGL ES" else "libplacebo · OpenGL ES",
                    GL.glGetString(GL.GL_RENDERER), "${width}×$height → ${plan.width}×${plan.height}",
                    stages, waitsForGpu, started, sourceFps,
                )
                return Size(plan.width, plan.height)
            } catch (e: Exception) {
                fail(e)
            } catch (e: LinkageError) {
                fail(e)
            }
            return Size(width, height)
        }

        private fun fail(e: Throwable) {
            Log.w(OverlayEnhancement.TAG, "GPU plan failed", e)
            reset()
            val reason = e.message?.takeIf { it == "EnhancementResolutionLimit" || it == "EnhancementModeInvalid" }
            status("error", reason ?: "EnhancementCompileFailed", 0, null)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val start = System.nanoTime()
            if (fast != null || handle != 0L) {
                try {
                    val program = fast
                    if (program != null) program.drawFrame(inputTexId, presentationTimeUs)
                    else {
                        GL.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, framebuffer, 0)
                        check(PlaceboNative.draw(handle, inputTexId, width, height, framebuffer[0], plan.width, plan.height))
                    }
                    if (waitsForGpu) GL.glFinish()
                    val end = System.nanoTime()
                    if (context is OverlayBenchmark) Log.i(OverlayEnhancement.TAG, "menuFrame pts=$presentationTimeUs total=${end-start}")
                    stats?.record(start, end, status)
                    return
                } catch (e: Exception) {
                    fail(e)
                }
            }
            copy.use()
            copy.setSamplerTexIdUniform("src", inputTexId, 0)
            copy.bindAttributesAndUniforms()
            GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun reset() {
            fast?.release()
            fast = null
            if (handle != 0L) PlaceboNative.release(handle)
            handle = 0L
            stats = null
        }

        override fun release() {
            reset()
            copy.delete()
            super.release()
        }
    }
}
