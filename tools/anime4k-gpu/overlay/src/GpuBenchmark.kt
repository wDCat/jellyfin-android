package org.jellyfin.mobile.player.videoproxy

import android.content.Context
import android.opengl.EGL14 as EGL
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES20 as GL
import android.os.Build
import android.os.Process
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size as GlSize
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ceil

internal object GpuBenchmark {
    val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Process.is64Bit() &&
            "arm64-v8a" in Build.SUPPORTED_ABIS

    private val lock = Mutex()

    fun modelIds(context: Context): List<String> = loadMenuModelCatalog(context).keys().asSequence().sorted().toList()

    private fun loadPresets(context: Context): JSONObject =
        JSONObject(context.assets.open("models/presets.json").bufferedReader().use { it.readText() })

    fun presets(context: Context): List<NpuBenchmarkPreset> {
        val catalog = loadPresets(context)
        return catalog.keys().asSequence().flatMap { mode ->
            NpuBenchmarkTier.entries.asSequence()
                .filter { catalog.getJSONObject(mode).has(it.id) }
                .map { NpuBenchmarkPreset(mode, it) }
        }.toList()
    }

    fun presetEffects(context: Context, preset: NpuBenchmarkPreset): List<String> {
        val effects = loadPresets(context).optJSONObject(preset.mode)?.optJSONArray(preset.tier.id) ?: return emptyList()
        return List(effects.length(), effects::getString)
    }

    suspend fun benchmark(
        context: Context, modelId: String?, width: Int, height: Int, sampleCount: Int, fps: Float,
        preset: NpuBenchmarkPreset?, presetOutput: Size?,
        onProgress: suspend (Int?, Int) -> Unit,
    ): String = lock.withLock {
        if (!available) return@withLock context.getString(R.string.gpu_bench_unavailable)
        val model = modelId ?: "anime4k/Upscale/CNNx2S"
        try {
            // EGL is bound to the worker thread even when progress callbacks suspend.
            Executors.newSingleThreadExecutor { Thread(it, "GpuBenchmark") }.asCoroutineDispatcher().use { worker ->
                withContext(worker) {
                    measure(context, model, Size(width, height), sampleCount, fps, preset, presetOutput, onProgress)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(OverlayTrial.TAG, "GPU benchmark failed", error)
            context.getString(R.string.gpu_bench_failed, preset?.displayName(context) ?: model)
        } catch (error: LinkageError) {
            Log.w(OverlayTrial.TAG, "GPU benchmark failed", error)
            context.getString(R.string.gpu_bench_unavailable)
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun measure(
        context: Context, modelId: String, input: Size, warmSamples: Int, fps: Float, preset: NpuBenchmarkPreset?,
        presetOutput: Size?, onProgress: suspend (Int?, Int) -> Unit,
    ): String {
        require(input.width in 16..1920 && input.height in 16..1080 && warmSamples in 1..10 && fps in 1f..240f)
        val effects = preset?.let { presetEffects(context, it) } ?: listOf(modelId)
        require(effects.isNotEmpty())
        val scale = if (preset == null) loadMenuModelCatalog(context).getJSONObject(modelId).getInt("scale") else 2
        val output = presetOutput ?: Size(input.width * scale, input.height * scale)
        val plan = MenuPlan(JSONObject()
            .put("accelerator", "gpu")
            .put("effects", JSONArray(effects.map { JSONObject().put("id", it) }))
            .put("target", JSONObject().put("width", output.width).put("height", output.height)))
        val total = warmSamples + 1
        onProgress(null, total)
        return withOffscreenContext {
            val coroutine = currentCoroutineContext()
            val renderer = checkNotNull(GL.glGetString(GL.GL_RENDERER))
            val softwareRenderers = listOf("swiftshader", "llvmpipe", "softpipe")
            require(softwareRenderers.none { renderer.contains(it, ignoreCase = true) }) { renderer }
            val maxTexture = IntArray(1).also { GL.glGetIntegerv(GL.GL_MAX_TEXTURE_SIZE, it, 0) }.single()
            val stages = plan.layout(context, GlSize(input.width, input.height), maxTexture)
            val source = GlUtil.createTexture(input.width, input.height, false)
            val sourceFbo = GlUtil.createFboForTexture(source)
            GlUtil.focusFramebufferUsingCurrentContext(sourceFbo, input.width, input.height)
            GL.glClearColor(0.15f, 0.3f, 0.45f, 1f)
            GL.glClear(GL.GL_COLOR_BUFFER_BIT)
            GL.glEnable(GL.GL_SCISSOR_TEST)
            GL.glScissor(input.width / 4, input.height / 4, input.width / 2, input.height / 2)
            GL.glClearColor(0.8f, 0.6f, 0.4f, 1f)
            GL.glClear(GL.GL_COLOR_BUFFER_BIT)
            GL.glDisable(GL.GL_SCISSOR_TEST)
            GL.glClearColor(0f, 0f, 0f, 0f)
            val outputFbo = GlUtil.createFboForTexture(GlUtil.createTexture(output.width, output.height, false))
            GL.glFinish()
            GlUtil.checkGlError()
            var failure: String? = null
            val started = System.nanoTime()
            val program = MenuGpuEffect(plan, fps, fixture = false, ignoreNpuBudget = true, compilationActivity = null) { state, reason, _, _ ->
                if (state == "error") failure = reason ?: "GPU rendering failed"
            }.toGlShaderProgram(context, useHdr = false)
            val times = try {
                val configured = program.configure(input.width, input.height)
                check(failure == null && configured.width == output.width && configured.height == output.height) {
                    failure ?: "Unexpected GPU output size $configured"
                }
                GlUtil.focusFramebufferUsingCurrentContext(outputFbo, output.width, output.height)
                GL.glFinish()
                val setup = System.nanoTime() - started
                onProgress(0, total)
                val frames = List(total) { index ->
                    coroutine.ensureActive()
                    val frameStart = System.nanoTime()
                    GL.glClear(GL.GL_COLOR_BUFFER_BIT)
                    program.drawFrame(source, (index * 1_000_000.0 / fps).toLong())
                    GL.glFinish()
                    val elapsed = System.nanoTime() - frameStart
                    check(failure == null) { checkNotNull(failure) }
                    GlUtil.checkGlError()
                    onProgress(index + 1, total)
                    elapsed
                }
                setup to frames
            } finally {
                program.release()
            }
            val (setup, frames) = times
            val samples = frames.drop(1).sorted()
            val budget = playbackFrameBudgetNs(fps)
            val p50 = (samples[(warmSamples - 1) / 2] + samples[warmSamples / 2]) / 2
            val inBudget = samples.count { it <= budget }
            val verdict = if (frames.first() <= budget && inBudget == warmSamples) R.string.npu_bench_passed
                else R.string.npu_bench_over_budget
            val name = preset?.displayName(context) ?: modelId
            buildString {
                appendLine(context.getString(R.string.gpu_bench_device, renderer))
                appendLine(context.getString(R.string.npu_bench_preset_target, name, output.width, output.height))
                appendLine(context.getString(
                    R.string.npu_bench_report_budget, input.width, input.height, fps.toString().removeSuffix(".0"), millis(budget), warmSamples,
                ))
                appendLine()
                if (preset != null) {
                    appendLine(stages.joinToString(" → ") { stage ->
                        plan.effectId(stage) ?: context.getString(R.string.npu_bench_linear_resize, stage.size.width, stage.size.height)
                    })
                }
                appendLine(context.getString(verdict))
                appendLine(context.getString(R.string.gpu_bench_setup, millis(setup)))
                appendLine(context.getString(
                    R.string.npu_bench_frame_times, millis(frames.first()), millis(p50),
                    millis(samples[ceil(warmSamples * 0.95).toInt() - 1]), inBudget, warmSamples,
                ))
                appendLine()
                append(context.getString(R.string.gpu_bench_scope))
            }
        }
    }

    private fun millis(nanos: Long) = String.format(Locale.US, "%.2f ms", nanos / 1e6)

    private suspend fun <T> withOffscreenContext(render: suspend () -> T): T {
        val display = GlUtil.getDefaultEglDisplay()
        val configAttributes = intArrayOf(
            EGL.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL.EGL_SURFACE_TYPE, EGL.EGL_PBUFFER_BIT,
            EGL.EGL_RED_SIZE, 8, EGL.EGL_GREEN_SIZE, 8, EGL.EGL_BLUE_SIZE, 8, EGL.EGL_ALPHA_SIZE, 8, EGL.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No GLES 3 EGL config" }
        val config = checkNotNull(configs[0])
        val context = EGL.eglCreateContext(display, config, EGL.EGL_NO_CONTEXT, intArrayOf(EGL.EGL_CONTEXT_CLIENT_VERSION, 3, EGL.EGL_NONE), 0)
        check(context != EGL.EGL_NO_CONTEXT) { "Could not create GLES 3 context" }
        try {
            val surface = EGL.eglCreatePbufferSurface(display, config, intArrayOf(EGL.EGL_WIDTH, 1, EGL.EGL_HEIGHT, 1, EGL.EGL_NONE), 0)
            check(surface != EGL.EGL_NO_SURFACE) { "Could not create EGL pbuffer" }
            try {
                check(EGL.eglMakeCurrent(display, surface, surface, context)) { "Could not bind benchmark EGL context" }
                return render()
            } finally {
                EGL.eglMakeCurrent(display, EGL.EGL_NO_SURFACE, EGL.EGL_NO_SURFACE, EGL.EGL_NO_CONTEXT)
                EGL.eglDestroySurface(display, surface)
            }
        } finally {
            // Destroy this test's unshared context and textures while keeping the playback display alive.
            EGL.eglDestroyContext(display, context)
            EGL.eglReleaseThread()
        }
    }
}
