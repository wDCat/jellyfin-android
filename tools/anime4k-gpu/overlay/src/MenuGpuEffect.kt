package org.jellyfin.mobile.player.videoproxy

import android.app.Activity
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
    init { System.loadLibrary("overlay_gpu") }
    external fun create(sources: Array<String>): Long
    external fun draw(handle: Long, texture: Int, width: Int, height: Int, fbo: Int, targetWidth: Int, targetHeight: Int): Boolean
    external fun release(handle: Long)
}

internal typealias EffectStatus = (String, String?, Int, JSONObject?) -> Unit

internal fun loadMenuModelCatalog(context: Context) =
    JSONObject(context.assets.open("models/catalog.json").bufferedReader().use { it.readText() })

internal data class MenuStage(val effectIndex: Int?, val size: Size)

internal data class HybridStages(
    val before: List<MenuStage>,
    val models: List<String>,
    val after: List<MenuStage>,
    val inputSize: Size,
)

/** Only bundled GLSL is executable. Web supplies effect IDs, dimensions and numerical CRT settings. */
internal class MenuPlan(data: JSONObject) {
    private val effects = data.getJSONArray("effects")
    private val dimensions = data.getJSONObject("target")
    val width = dimensions.getInt("width")
    val height = dimensions.getInt("height")
    val monitorGpu = data.optString("monitor") == "gpu"
    val useNpu = data.optString("accelerator") == "npu"
    private val effectIds = List(effects.length()) { effects.getJSONObject(it).getString("id") }
    val singleCnn = effectIds.singleOrNull() == "anime4k/Upscale/CNNx2S"
    init {
        require(width in 1..32768 && height in 1..32768 && effects.length() in 1..64)
    }

    private fun asset(context: Context, name: String) =
        context.assets.open("models/$name").bufferedReader().use { it.readText() }

    fun layout(context: Context, input: Size, maxTexture: Int): List<MenuStage> {
        val catalog = loadMenuModelCatalog(context)
        val scales = effectIds.map { catalog.optJSONObject(it)?.getInt("scale") ?: 1 }
        val target = Size(width, height)
        val stages = mutableListOf<MenuStage>()
        var current = input
        fun append(index: Int?, size: Size) {
            require(size.width in 1..maxTexture && size.height in 1..maxTexture) { "EnhancementResolutionLimit" }
            current = size
            stages += MenuStage(index, size)
        }
        for ((index, id) in effectIds.withIndex()) {
            val scale = scales[index]
            val size = when (id) {
                "core/Resize/ToTarget", "core/internal/ResizeLinear", "core/Filter/CRT", "core/Filter/Monochrome" -> target
                else -> Size(Math.multiplyExact(current.width, scale), Math.multiplyExact(current.height, scale))
            }
            append(index, size)
            val remaining = scales.drop(index + 1).fold(1.0) { product, factor -> product * factor }
            if (scale > 1 && remaining > 1 &&
                (current.width > width / remaining * 1.1 || current.height > height / remaining * 1.1)) {
                append(null, Size(ceil(width / remaining).toInt(), ceil(height / remaining).toInt()))
            }
        }
        if (current != target) append(null, target)
        return stages
    }

    fun hybrid(context: Context, input: Size, stages: List<MenuStage>): HybridStages? {
        val candidates = stages.map { stage ->
            stage.effectIndex?.let(effectIds::get)?.takeIf { NpuRuntime.hasGraph(context, it) }
        }
        val first = candidates.indexOfFirst { it != null }
        if (first == -1) return null
        // Keep intermediate resizes and GPU-only effects in their original order.
        val models = candidates.drop(first).takeWhile { it != null }.filterNotNull()
        return HybridStages(stages.take(first), models, stages.drop(first + models.size),
            stages.getOrNull(first - 1)?.size ?: input)
    }

    fun effectId(stage: MenuStage): String? = stage.effectIndex?.let(effectIds::get)

    fun compile(context: Context, stages: List<MenuStage>): Array<String> {
        val catalog = loadMenuModelCatalog(context)
        val shaders = stages.map { stage ->
            val index = stage.effectIndex
            val id = index?.let(effectIds::get)
            val w = stage.size.width
            val h = stage.size.height
            val header = "//!HOOK MAIN\n//!BIND MAIN\n//!WIDTH $w\n//!HEIGHT $h\n"
            when (id) {
                null, "core/Resize/ToTarget", "core/internal/ResizeLinear" ->
                    header + "vec4 hook(){return MAIN_tex(MAIN_pos);}\n"
                "core/Filter/CRT", "core/Filter/Monochrome" -> {
                    if (id.endsWith("Monochrome")) {
                        header + "vec4 hook(){vec4 c=MAIN_tex(MAIN_pos); return vec4(vec3(dot(c.rgb,vec3(0.2126,0.7152,0.0722))),c.a);}\n"
                    } else {
                        val schema = JSONObject(asset(context, "crt.json"))
                        val params = effects.getJSONObject(requireNotNull(index)).optJSONObject("params")
                        var template = asset(context, "crt.glsl").replace("@w@", w.toString()).replace("@h@", h.toString())
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
                    asset(context, model.getString("file")).replace("NIJI_0_", "NIJI_${index}_")
                }
            }
        }
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
internal class MenuGpuEffect(
    private val plan: MenuPlan,
    private val sourceFps: Float?,
    private val fixture: Boolean,
    private val ignoreNpuBudget: Boolean,
    private val compilationActivity: Activity?,
    private val status: EffectStatus,
) : GlEffect {
    fun outputFailed() = status("error", "EnhancementFrameFailed", 0, null)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        Program(context, useHdr, plan, sourceFps, fixture, ignoreNpuBudget, compilationActivity, status)

    private class Program(
        val context: Context, val hdr: Boolean, val plan: MenuPlan, val sourceFps: Float?,
        val fixture: Boolean, ignoreNpuBudget: Boolean,
        val compilationActivity: Activity?, val status: EffectStatus,
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
        private var npu: NpuChain? = null
        private var transfer: NpuFrameTransfer? = null
        private var npuPreparation: NpuPreparation? = null
        private var npuFallback: NpuStatus? = null
        private var lastNpuStatus: NpuStatus? = null
        private var layout = emptyList<MenuStage>()
        private var hybrid: HybridStages? = null
        private var npuPre = 0L
        private val npuPreTarget = IntArray(2)
        private var npuPost = 0L
        private var stats: GpuFrameStats? = null
        private var gpuStats: GpuFrameStats? = null
        private val waitsForGpu = fixture || plan.monitorGpu
        private val budgetNs = if (ignoreNpuBudget) null else playbackFrameBudgetNs(sourceFps)

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
                val max = IntArray(1)
                GL.glGetIntegerv(GL.GL_MAX_TEXTURE_SIZE, max, 0)
                require(plan.width <= max[0] && plan.height <= max[0]) { "EnhancementResolutionLimit" }
                val input = Size(width, height)
                layout = plan.layout(context, input, max[0])
                val selection = if (plan.useNpu && (compilationActivity != null || fixture)) {
                    plan.hybrid(context, input, layout)
                } else null
                hybrid = selection
                if (plan.useNpu && selection == null) npuFallback = NpuStatus.UNAVAILABLE
                configureGpu(started)
                if (selection != null) {
                    npuPreparation = NpuRuntime.prepare(
                        context, selection.models, selection.inputSize.width, selection.inputSize.height, max[0], budgetNs,
                        compilationActivity,
                    )
                }
                return Size(plan.width, plan.height)
            } catch (e: Exception) {
                fail(e)
            } catch (e: LinkageError) {
                fail(e)
            }
            return Size(width, height)
        }

        private fun activateNpu(chain: NpuChain, selection: HybridStages, started: Long): Boolean {
            npu = chain
            val input = selection.inputSize
            try {
                transfer = NpuFrameTransfer(input.width, input.height, chain.outWidth, chain.outHeight)
                if (selection.before.isNotEmpty()) {
                    npuPre = PlaceboNative.create(plan.compile(context, selection.before))
                    check(npuPre != 0L) { "EnhancementCompileFailed" }
                }
                if (selection.after.isNotEmpty()) {
                    npuPost = PlaceboNative.create(plan.compile(context, selection.after))
                    check(npuPost != 0L) { "EnhancementCompileFailed" }
                }
                if (npuPre != 0L) {
                    GL.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, framebuffer, 0)
                    try {
                        npuPreTarget[0] = GlUtil.createTexture(input.width, input.height, false)
                        npuPreTarget[1] = GlUtil.createFboForTexture(npuPreTarget[0])
                        check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE)
                    } finally {
                        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, framebuffer[0])
                    }
                }
                stats = GpuFrameStats(
                    if (npuPre == 0L && npuPost == 0L) "NNAPI qti-dsp" else "NNAPI qti-dsp + libplacebo · OpenGL ES",
                    NpuRuntime.probe().optString("device"),
                    "${width}×$height → ${plan.width}×${plan.height}", layout.size, waitsForGpu, started, sourceFps,
                )
                return true
            } catch (e: Exception) {
                Log.w(OverlayTrial.TAG, "NPU output initialization failed", e)
                npuFallback = NpuStatus.FAILED
                releaseNpu()
                return false
            }
        }

        private fun configureGpu(started: Long) {
            val stages: Int
            if (plan.singleCnn && OverlayTrial.supportsSize(width, height) && plan.width == width * 2 && plan.height == height * 2) {
                fast = GpuProgram(context, false).also { it.configure(width, height) }
                stages = 5
            } else {
                val sources = plan.compile(context, layout)
                stages = sources.sumOf { shader -> shader.lineSequence().count { it.startsWith("//!HOOK ") } }
                handle = PlaceboNative.create(sources)
                check(handle != 0L) { "EnhancementCompileFailed" }
            }
            stats = GpuFrameStats(
                if (fast != null) "CNNx2S · OpenGL ES" else "libplacebo · OpenGL ES",
                GL.glGetString(GL.GL_RENDERER), "${width}×$height → ${plan.width}×${plan.height}",
                stages, waitsForGpu, started, sourceFps,
            )
            gpuStats = stats
        }

        private fun fail(e: Throwable) {
            Log.w(OverlayTrial.TAG, "GPU plan failed", e)
            reset()
            val reason = e.message?.takeIf { it == "EnhancementResolutionLimit" || it == "EnhancementModeInvalid" }
            status("error", reason ?: "EnhancementCompileFailed", 0, null)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val selection = hybrid
            if (selection != null) npuPreparation?.take()?.let { prepared ->
                GL.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, framebuffer, 0)
                val outputFramebuffer = framebuffer[0]
                try {
                    activateNpu(prepared, selection, System.nanoTime())
                } finally {
                    GlUtil.focusFramebufferUsingCurrentContext(outputFramebuffer, plan.width, plan.height)
                }
            }
            val start = System.nanoTime()
            val chain = npu
            val frameTransfer = transfer
            if (chain != null && frameTransfer != null && selection != null) {
                try {
                    val input = selection.inputSize
                    val sourceTexture = if (npuPre == 0L) inputTexId else {
                        GL.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, framebuffer, 0)
                        try {
                            check(PlaceboNative.draw(npuPre, inputTexId, width, height, npuPreTarget[1], input.width, input.height))
                        } finally {
                            GlUtil.focusFramebufferUsingCurrentContext(framebuffer[0], plan.width, plan.height)
                        }
                        npuPreTarget[0]
                    }
                    val rgba = frameTransfer.read(sourceTexture)
                    val output = chain.run(rgba, input.width, input.height)
                    frameTransfer.upload(output)
                    if (npuPost == 0L) {
                        frameTransfer.draw()
                    } else {
                        GL.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, framebuffer, 0)
                        check(PlaceboNative.draw(npuPost, frameTransfer.texture, chain.outWidth, chain.outHeight,
                            framebuffer[0], plan.width, plan.height))
                    }
                    if (waitsForGpu) GL.glFinish()
                    val end = System.nanoTime()
                    if (fixture) {
                        Log.i(OverlayTrial.TAG, "npuFrame pts=$presentationTimeUs execMs=${chain.executeNs / 1e6} totalNs=${end - start}")
                    }
                    recordFrame(start, end)
                    if (budgetNs != null && end - start > budgetNs) {
                        npuFallback = NpuStatus.OVER_BUDGET
                        releaseNpu()
                    }
                    return
                } catch (e: Exception) {
                    Log.w(OverlayTrial.TAG, "NPU frame failed, falling back to GPU", e)
                    npuFallback = NpuStatus.FAILED
                    releaseNpu()
                    status("suspended", "NpuUnavailable", 0, null)
                    try {
                        if (gpuStats == null) configureGpu(start)
                    } catch (error: Exception) {
                        fail(error)
                    } catch (error: LinkageError) {
                        fail(error)
                    }
                }
            }
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
                    if (fixture) Log.i(OverlayTrial.TAG, "menuFrame pts=$presentationTimeUs totalNs=${end - start}")
                    recordFrame(start, end)
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

        private fun recordFrame(start: Long, end: Long) {
            val chain = npu
            val npuStatus = if (!plan.useNpu) null else if (chain != null) NpuStatus.ACTIVE
                else npuFallback ?: npuPreparation?.rejection ?: npuPreparation?.phase ?: NpuStatus.CHECKING
            stats?.record(start, end, refreshMetadata = npuStatus != lastNpuStatus) { state, reason, fps, details ->
                if (npuStatus != null && details != null) {
                    details.put("npu", JSONObject().put("status", npuStatus.id).apply {
                        if (budgetNs == null) put("ignoreBudget", true)
                        if (chain != null) put("models", chain.models)
                    })
                }
                lastNpuStatus = npuStatus
                status(state, reason, fps, details)
            }
        }

        private fun reset() {
            releaseNpu()
            npuFallback = null
            lastNpuStatus = null
            hybrid = null
            fast?.release()
            fast = null
            if (handle != 0L) PlaceboNative.release(handle)
            handle = 0L
            stats = null
            gpuStats = null
        }

        private fun releaseNpu() {
            npuPreparation?.close()
            npuPreparation = null
            transfer?.release()
            transfer = null
            npu?.close()
            npu = null
            if (npuPre != 0L) PlaceboNative.release(npuPre)
            npuPre = 0
            if (npuPreTarget[0] != 0) GL.glDeleteTextures(1, npuPreTarget, 0)
            if (npuPreTarget[1] != 0) GL.glDeleteFramebuffers(1, npuPreTarget, 1)
            npuPreTarget.fill(0)
            if (npuPost != 0L) PlaceboNative.release(npuPost)
            npuPost = 0
            stats = gpuStats
        }

        override fun release() {
            reset()
            copy.delete()
            super.release()
        }
    }
}
