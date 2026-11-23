package org.jellyfin.mobile.player.videoproxy

import android.app.Activity
import android.content.Context
import android.opengl.GLES20 as GL
import android.os.Build
import android.util.Log
import android.util.Size as AndroidSize
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jellyfin.mobile.R
import org.jellyfin.mobile.settings.confirmNpuCompilation
import org.jellyfin.mobile.settings.npuDialogBuilder
import org.jellyfin.mobile.settings.runWithNpuProgress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.roundToInt

internal fun playbackFrameBudgetNs(fps: Float?) =
    (750_000_000.0 / (fps?.takeIf { it.isFinite() && it > 0f } ?: 24f)).toLong()

/** Snapdragon Hexagon execution through the Qualcomm NNAPI qti-dsp device. */
internal object NpuRuntime {
    private val libraryLoaded by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) false
        else try {
            System.loadLibrary("overlay_npu")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
    val available: Boolean get() = probe().optBoolean("available")
    private val cache = ConcurrentHashMap<String, ByteArray>()
    private val rejected = ConcurrentHashMap<PlaybackShape, NpuStatus>()
    private val benchmarkLock = Mutex()
    private val preparationLock = Mutex()
    private val compiler by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "NpuPlaybackQualification").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    }
    @Volatile private var catalog: JSONObject? = null

    private data class PlaybackShape(val ids: List<String>, val width: Int, val height: Int, val budgetNs: Long?)
    private data class BenchmarkRun(val name: String, val ids: List<String>, val input: Size)

    external fun nativeProbe(): String
    @Synchronized external fun nativeLoad(graph: ByteArray, width: Int, height: Int): Long
    external fun nativeExecute(handle: Long, input: ByteBuffer, output: ByteBuffer, timing: LongArray): Boolean
    external fun nativeInfo(handle: Long): IntArray
    private external fun nativeOperations(handle: Long): Array<String>
    external fun nativeRelease(handle: Long)

    fun probe(): JSONObject = if (libraryLoaded) JSONObject(nativeProbe()) else JSONObject()

    fun summary(context: Context): String {
        val probe = probe()
        val device = probe.optString("device", "none")
        val count = loadCatalog(context).length()
        return if (probe.optBoolean("available")) {
            context.resources.getQuantityString(R.plurals.npu_bench_device_summary, count, device, count)
        } else {
            context.getString(R.string.npu_bench_nnapi_unavailable)
        }
    }

    private suspend fun open(
        context: Context, ids: List<String>, width: Int, height: Int, maxSize: Int,
        onCompiled: suspend (Int) -> Unit = {},
    ): NpuChain? {
        if (!available || ids.isEmpty()) return null
        val graphs = ids.map { graph(context, it) ?: return null }
        var outputWidth = width
        var outputHeight = height
        val catalog = loadCatalog(context)
        for (id in ids) {
            val scale = catalog.getJSONObject(id).getInt("scale")
            outputWidth = Math.multiplyExact(outputWidth, scale)
            outputHeight = Math.multiplyExact(outputHeight, scale)
            if (outputWidth !in 1..maxSize || outputHeight !in 1..maxSize) return null
        }
        val steps = ArrayList<NpuStep>()
        var w = width
        var h = height
        var opened = false
        try {
            for ((index, bytes) in graphs.withIndex()) {
                currentCoroutineContext().ensureActive()
                val handle = nativeLoad(bytes, w, h)
                if (handle == 0L) return null
                val step = try {
                    val info = nativeInfo(handle)
                    NpuStep(ids[index], nativeOperations(handle), handle, info[1], info[2], info[3], info[4])
                } catch (error: Throwable) {
                    nativeRelease(handle)
                    throw error
                }
                steps += step
                w = step.outW
                h = step.outH
                currentCoroutineContext().ensureActive()
                onCompiled(index + 1)
            }
            return NpuChain(steps).also { opened = true }
        } finally {
            if (!opened) steps.forEach { nativeRelease(it.handle) }
        }
    }

    fun prepare(
        context: Context, ids: List<String>, width: Int, height: Int, maxSize: Int, budgetNs: Long?,
        activity: Activity?,
    ): NpuPreparation {
        val result = NpuPreparation()
        if (!available || ids.isEmpty() || ids.any { !loadCatalog(context).has(it) }) {
            result.reject(NpuStatus.UNAVAILABLE)
            return result
        }
        val shape = PlaybackShape(ids, width, height, budgetNs)
        rejected[shape]?.let {
            result.reject(it)
            return result
        }
        val application = context.applicationContext
        result.phase = if (activity == null) NpuStatus.COMPILING else NpuStatus.AWAITING_CONFIRMATION
        val task = CoroutineScope(Dispatchers.Main).launch {
            try {
                if (activity != null && !confirmNpuCompilation(activity, ids, width, height)) {
                    result.cancelByUser()
                    return@launch
                }
                suspend fun compile(progress: suspend (Int?, Int) -> Unit) = withContext(compiler) {
                    preparationLock.withLock {
                        prepareChain(application, shape, maxSize, result, progress)
                    }
                }
                if (activity == null) {
                    compile { _, _ -> }
                } else {
                    runWithNpuProgress(
                        npuDialogBuilder(activity).setTitle(R.string.npu_playback_compiling_title),
                        onCancel = result::cancelByUser,
                        progressLabel = R.string.npu_playback_compile_progress,
                        finalPhase = R.string.npu_playback_budget_check,
                    ) { progress -> compile(progress) }
                }
                result.allowPlayback()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(OverlayTrial.TAG, "NPU preparation failed for $ids", error)
                result.reject(NpuStatus.FAILED)
            }
        }
        result.attach(task)
        return result
    }

    private suspend fun prepareChain(
        context: Context, shape: PlaybackShape, maxSize: Int, result: NpuPreparation,
        progress: suspend (Int?, Int) -> Unit,
    ) {
        rejected[shape]?.let {
            result.reject(it)
            return
        }
        val (ids, width, height, budgetNs) = shape
        result.phase = NpuStatus.COMPILING
        progress(0, ids.size)
        val chain = open(context, ids, width, height, maxSize) { progress(it, ids.size) }
        if (chain == null) {
            currentCoroutineContext().ensureActive()
            rejected[shape] = NpuStatus.UNAVAILABLE
            result.reject(NpuStatus.UNAVAILABLE)
            return
        }
        var accepted = false
        try {
            currentCoroutineContext().ensureActive()
            if (budgetNs != null) {
                result.phase = NpuStatus.CHECKING
                progress(null, 0)
                val input = FloatArray(width * height * 4)
                val duration = timedFrame(chain, input, width, height)
                currentCoroutineContext().ensureActive()
                if (duration > budgetNs) {
                    rejected[shape] = NpuStatus.OVER_BUDGET
                    result.reject(NpuStatus.OVER_BUDGET)
                    Log.i(OverlayTrial.TAG, "NPU over frame budget ids=$ids qualificationMs=${duration / 1e6} budgetMs=${budgetNs / 1e6}")
                    return
                }
            }
            accepted = result.publish(chain)
        } finally {
            if (!accepted) chain.close()
        }
    }

    suspend fun benchmark(
        context: Context, modelId: String? = null, width: Int = 960, height: Int = 540,
        sampleCount: Int = 3, fps: Float = 24f,
        preset: NpuBenchmarkPreset? = null, presetOutput: AndroidSize? = null,
        onProgress: suspend (Int?, Int) -> Unit = { _, _ -> },
    ): String = benchmarkLock.withLock {
        require(width in 16..1920 && height in 16..1080 && sampleCount in 1..10 && fps in 1f..240f)
        val executionContext = currentCoroutineContext()
        if (!available) return@withLock summary(context)
        val budget = playbackFrameBudgetNs(fps)
        val report = StringBuilder().appendLine(summary(context)).appendLine(
            context.getString(
                R.string.npu_bench_report_budget, width, height, fps.toString().removeSuffix(".0"),
                millis(budget), sampleCount,
            ),
        )
        val input = Size(width, height)
        val selected = if (preset == null) {
            val ids = modelId?.let(::listOf) ?: listOf(
                "artcnn/Upscale/C4F16", "cunny/Upscale/DS/2x12", "anime4k/Upscale/CNNx2S", "acnet/Upscale/F8B4",
            )
            ids.map { BenchmarkRun(it, listOf(it), input) }
        } else {
            listOfNotNull(presetRun(context, preset, input, presetOutput, report))
        }
        for ((name, ids, size) in selected) {
            executionContext.ensureActive()
            report.appendLine().appendLine(name)
            if (ids.any { !hasGraph(context, it) }) {
                report.appendLine(context.getString(R.string.npu_bench_graph_unavailable))
                continue
            }
            val totalFrames = sampleCount + 1
            onProgress(null, totalFrames)
            executionContext.ensureActive()
            val started = System.nanoTime()
            val chain = open(context, ids, size.width, size.height, 3840)
            val compileMs = (System.nanoTime() - started) / 1e6
            if (chain == null) {
                executionContext.ensureActive()
                report.appendLine(context.getString(R.string.npu_bench_compile_failed))
                break
            }
            try {
                executionContext.ensureActive()
                onProgress(0, totalFrames)
                executionContext.ensureActive()
                val frame = FloatArray(size.width * size.height * 4)
                val firstFrame = timedFrame(chain, frame, size.width, size.height)
                executionContext.ensureActive()
                onProgress(1, totalFrames)
                val hardware = Array(sampleCount) { LongArray(4) }
                val times = hardware.mapIndexed { index, timing ->
                    executionContext.ensureActive()
                    val duration = timedFrame(chain, frame, size.width, size.height, timing)
                    executionContext.ensureActive()
                    onProgress(index + 2, totalFrames)
                    duration
                }.sorted()
                val inBudget = times.count { it <= budget }
                val verdict = if (firstFrame <= budget && inBudget == sampleCount) R.string.npu_bench_passed
                    else R.string.npu_bench_over_budget
                report.appendLine(context.getString(verdict))
                report.appendLine(context.getString(R.string.npu_bench_compile_time, compileMs.roundToInt(), chain.outWidth, chain.outHeight))
                report.appendLine(context.getString(
                    R.string.npu_bench_frame_times, millis(firstFrame), median(times),
                    millis(times[ceil(sampleCount * 0.95).toInt() - 1]), inBudget, sampleCount,
                ))
                report.appendLine(
                    "NNAPI P50 ${median(hardware.map { it[1] })} · DSP P50 ${median(hardware.map { it[2] })}",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalStateException) {
                Log.w(OverlayTrial.TAG, "NPU benchmark execution failed for $name", error)
                report.appendLine(context.getString(R.string.npu_bench_execution_failed))
                break
            } finally {
                chain.close()
            }
        }
        report.appendLine().appendLine(context.getString(R.string.npu_bench_report_scope))
        val text = report.toString()
        executionContext.ensureActive()
        context.filesDir.resolve("npu-bench.txt").writeText(text)
        text
    }

    private fun presetRun(
        context: Context, preset: NpuBenchmarkPreset, input: Size, presetOutput: AndroidSize?, report: StringBuilder,
    ): BenchmarkRun? {
        val output = presetOutput ?: AndroidSize(input.width * 2, input.height * 2)
        val name = preset.displayName(context)
        val effects = GpuBenchmark.presetEffects(context, preset)
        report.appendLine().appendLine(context.getString(R.string.npu_bench_preset_target, name, output.width, output.height))
        if (effects.isEmpty()) {
            report.appendLine(context.getString(R.string.npu_bench_graph_unavailable))
            return null
        }
        val plan = MenuPlan(JSONObject()
            .put("effects", JSONArray(effects.map { JSONObject().put("id", it) }))
            .put("target", JSONObject().put("width", output.width).put("height", output.height)))
        val selection = plan.hybrid(context, input, plan.layout(context, input, 8192))
        if (selection == null) {
            report.appendLine(context.getString(R.string.npu_bench_preset_no_npu))
            return null
        }
        fun gpuSteps(stages: List<MenuStage>) = stages.joinToString(" → ") {
            plan.effectId(it) ?: context.getString(R.string.npu_bench_linear_resize, it.size.width, it.size.height)
        }
        if (selection.before.isNotEmpty()) {
            report.appendLine(context.getString(R.string.npu_bench_gpu_before, gpuSteps(selection.before)))
        }
        report.appendLine(context.getString(
            R.string.npu_bench_preset_npu, selection.inputSize.width, selection.inputSize.height, selection.models.joinToString(" → "),
        ))
        if (selection.after.isNotEmpty()) {
            report.appendLine(context.getString(R.string.npu_bench_gpu_after, gpuSteps(selection.after)))
        }
        return BenchmarkRun(context.getString(R.string.npu_bench_preset_measurement), selection.models, selection.inputSize)
    }

    private fun loadCatalog(context: Context): JSONObject {
        catalog?.let { return it }
        val parsed = JSONObject(context.assets.open("npu/catalog.json").bufferedReader().use { it.readText() })
        catalog = parsed
        return parsed
    }

    fun hasGraph(context: Context, id: String): Boolean = loadCatalog(context).has(id)

    fun modelIds(context: Context): List<String> = loadCatalog(context).keys().asSequence().sorted().toList()

    private fun graph(context: Context, id: String): ByteArray? {
        cache[id]?.let { return it }
        val file = loadCatalog(context).optJSONObject(id)?.optString("file") ?: return null
        val bytes = context.assets.open("npu/$file").use { it.readBytes() }
        cache[id] = bytes
        return bytes
    }

    private fun timedFrame(chain: NpuChain, frame: FloatArray, width: Int, height: Int, timing: LongArray? = null): Long {
        val start = System.nanoTime()
        chain.run(frame, width, height, timing)
        return System.nanoTime() - start
    }

    private fun formatNumber(value: Double) = String.format(Locale.US, "%.2f", value)

    private fun millis(nanos: Long) = "${formatNumber(nanos / 1e6)} ms"

    private fun median(samples: List<Long>): String {
        val valid = samples.filter { it >= 0 }.sorted()
        if (valid.isEmpty()) return "—"
        val ms = (valid[(valid.size - 1) / 2] / 2.0 + valid[valid.size / 2] / 2.0) / 1e6
        return "${formatNumber(ms)} ms"
    }
}

internal enum class NpuStatus(val id: String) {
    AWAITING_CONFIRMATION("awaitingConfirmation"),
    COMPILING("compiling"),
    CHECKING("checking"),
    ACTIVE("active"),
    CANCELLED("cancelled"),
    UNAVAILABLE("unavailable"),
    OVER_BUDGET("overBudget"),
    FAILED("failed"),
}

internal class NpuPreparation {
    private var chain: NpuChain? = null
    private var task: Job? = null
    private var playable = false
    @Volatile var phase = NpuStatus.CHECKING
    @Volatile var rejection: NpuStatus? = null
        private set
    @Volatile var cancelled = false
        private set

    @Synchronized fun publish(candidate: NpuChain): Boolean {
        if (cancelled) return false
        chain = candidate
        return true
    }

    @Synchronized fun attach(job: Job) {
        if (cancelled) job.cancel() else task = job
    }

    @Synchronized fun allowPlayback() {
        playable = !cancelled
    }

    @Synchronized fun take(): NpuChain? = if (playable) chain.also { chain = null } else null

    @Synchronized fun reject(reason: NpuStatus) {
        if (!cancelled) rejection = reason
    }

    @Synchronized fun cancelByUser() {
        rejection = NpuStatus.CANCELLED
        close()
    }

    @Synchronized fun close() {
        cancelled = true
        task?.cancel()
        task = null
        chain?.close()
        chain = null
    }
}

internal class NpuStep(
    val modelId: String,
    val operations: Array<String>,
    val handle: Long,
    val channels: Int,
    val composite: Int,
    val outW: Int,
    val outH: Int,
) {
    val output by lazy { ByteBuffer.allocateDirect(outW * outH * channels * 4).order(ByteOrder.nativeOrder()) }
    val produced by lazy { FloatArray(outW * outH * channels) }
    val composed by lazy { FloatArray(outW * outH * 4) }
    val timing = LongArray(1)
    val measuredTiming by lazy { LongArray(4) }
}

internal class NpuChain(private val steps: List<NpuStep>) {
    val outWidth = steps.last().outW
    val outHeight = steps.last().outH
    var executeNs = 0L
        private set
    private var input: ByteBuffer? = null
    val models by lazy {
        JSONArray(steps.map { step ->
            JSONObject().put("id", step.modelId).put("operations", JSONArray(step.operations))
        })
    }

    fun run(rgba: FloatArray, width: Int, height: Int, benchmarkTiming: LongArray? = null): FloatArray {
        benchmarkTiming?.fill(0)
        var frame = rgba
        var w = width
        var h = height
        var total = 0L
        for (step in steps) {
            val buffer = input?.takeIf { it.capacity() >= frame.size * 4 }
                ?: ByteBuffer.allocateDirect(frame.size * 4).order(ByteOrder.nativeOrder()).also { input = it }
            buffer.asFloatBuffer().put(frame)
            val timing = if (benchmarkTiming == null) step.timing else step.measuredTiming
            check(NpuRuntime.nativeExecute(step.handle, buffer, step.output, timing)) { "NpuExecuteFailed" }
            total += timing[0]
            if (benchmarkTiming != null) {
                for (index in benchmarkTiming.indices) {
                    val accumulated = benchmarkTiming[index]
                    benchmarkTiming[index] = if (accumulated >= 0 && timing[index] >= 0) accumulated + timing[index] else -1
                }
            }
            step.output.asFloatBuffer().get(step.produced)
            frame = compose(frame, w, h, step)
            w = step.outW
            h = step.outH
        }
        executeNs = total
        return frame
    }

    fun close() = steps.forEach { NpuRuntime.nativeRelease(it.handle) }

    private fun compose(source: FloatArray, sw: Int, sh: Int, step: NpuStep): FloatArray {
        val out = step.composed
        val produced = step.produced
        val sample = FloatArray(3)
        for (y in 0 until step.outH) {
            val sy = ((y + 0.5f) * sh / step.outH - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = minOf(y0 + 1, sh - 1)
            val fy = sy - y0
            for (x in 0 until step.outW) {
                val i = (y * step.outW + x) * 4
                val base = (y * step.outW + x) * step.channels
                val pix = produced[base]
                if (step.composite != 0) {
                    val sx = ((x + 0.5f) * sw / step.outW - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                    val x0 = sx.toInt()
                    val x1 = minOf(x0 + 1, sw - 1)
                    val fx = sx - x0
                    for (c in sample.indices) {
                        val a = source[(y0 * sw + x0) * 4 + c]
                        val b = source[(y0 * sw + x1) * 4 + c]
                        val d = source[(y1 * sw + x0) * 4 + c]
                        val e = source[(y1 * sw + x1) * 4 + c]
                        val above = a + (b - a) * fx
                        val below = d + (e - d) * fx
                        sample[c] = above + (below - above) * fy
                    }
                }
                when (step.composite) {
                    2 -> { // chroma recompose from enhanced luma
                        val yBase = 0.2126f * sample[0] + 0.7152f * sample[1] + 0.0722f * sample[2]
                        val delta = pix.coerceIn(0f, 1f) - yBase
                        for (c in sample.indices) out[i + c] = (sample[c] + delta).coerceIn(0f, 1f)
                    }
                    1 -> {
                        for (c in sample.indices) out[i + c] = (sample[c] + pix).coerceIn(0f, 1f)
                    }
                    3 -> {
                        for (c in sample.indices) out[i + c] = (sample[c] + produced[base + c]).coerceIn(0f, 1f)
                    }
                    else -> {
                        out[i] = produced[base].coerceIn(0f, 1f)
                        out[i + 1] = produced[base + minOf(1, step.channels - 1)].coerceIn(0f, 1f)
                        out[i + 2] = produced[base + minOf(2, step.channels - 1)].coerceIn(0f, 1f)
                    }
                }
                out[i + 3] = 1f
            }
        }
        return out
    }

}

@UnstableApi
internal class NpuFrameTransfer(
    private val width: Int,
    private val height: Int,
    private val outWidth: Int,
    private val outHeight: Int,
) {
    private val readback = ByteBuffer.allocateDirect(width * height * 4)
    private val input = FloatArray(readback.capacity())
    private val upload = ByteBuffer.allocateDirect(outWidth * outHeight * 4)
    private val previousFramebuffer = IntArray(1)
    private val ids = IntArray(2)
    val texture: Int get() = ids[1]
    private val copy = GlProgram(
        "attribute vec4 pos; varying vec2 uv; void main(){gl_Position=pos;uv=pos.xy*0.5+0.5;}",
        "precision mediump float; varying vec2 uv; uniform sampler2D src; void main(){gl_FragColor=texture2D(src,uv);}",
    ).apply { setBufferAttribute("pos", GlUtil.getNormalizedCoordinateBounds(), 4) }

    init {
        GL.glGenFramebuffers(1, ids, 0)
        GL.glGenTextures(1, ids, 1)
        GL.glBindTexture(GL.GL_TEXTURE_2D, ids[1])
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, outWidth, outHeight, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
    }

    fun read(inputTexture: Int): FloatArray {
        GL.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, previousFramebuffer, 0)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, ids[0])
        try {
            GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, inputTexture, 0)
            check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE)
            readback.clear()
            GL.glReadPixels(0, 0, width, height, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, readback)
            GlUtil.checkGlError()
        } finally {
            GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, 0, 0)
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, previousFramebuffer[0])
        }
        for (i in input.indices) input[i] = (readback.get(i).toInt() and 255) / 255f
        return input
    }

    fun upload(rgba: FloatArray) {
        upload.clear()
        for (value in rgba) upload.put((value.coerceIn(0f, 1f) * 255f).roundToInt().toByte())
        upload.flip()
        GL.glBindTexture(GL.GL_TEXTURE_2D, ids[1])
        GL.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, outWidth, outHeight, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, upload)
        GlUtil.checkGlError()
    }

    fun draw() {
        copy.use()
        copy.setSamplerTexIdUniform("src", ids[1], 0)
        copy.bindAttributesAndUniforms()
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    fun release() {
        GL.glDeleteFramebuffers(1, ids, 0)
        GL.glDeleteTextures(1, ids, 1)
        copy.delete()
    }
}
