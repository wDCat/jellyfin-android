package org.jellyfin.mobile.player.videoproxy

import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One GL thread owns a sampling window. Durations cover the effect draw and its optional GPU wait. */
internal class GpuFrameStats(
    private val path: String,
    private val gpu: String?,
    private val dimensions: String,
    private val stages: Int,
    private val waitsForGpu: Boolean,
    private val configureStartedNs: Long,
    sourceFps: Float?,
) {
    private val budgetMs = sourceFps?.takeIf { it.isFinite() && it > 0 }?.let { 1000.0 / it }
    private var samples = LongArray(64)
    private var count = 0
    private var total = 0L
    private var since = 0L
    private var firstFrameMs = 0.0

    fun record(startNs: Long, endNs: Long, report: EffectStatus) {
        total++
        if (total == 1L) {
            firstFrameMs = (endNs - configureStartedNs) / 1e6
            since = endNs
            report("active", null, 0, metadata())
            return
        }

        if (count == samples.size) samples = samples.copyOf(samples.size * 2)
        samples[count++] = endNs - startNs
        val interval = endNs - since
        if (interval < 1_000_000_000L) return

        samples.sort(0, count)
        val fps = count * 1e9 / interval
        val details = metadata().apply {
            put("fps", fps)
            put("samples", count)
            put("p50Ms", samples[(count - 1) / 2] / 1e6)
            put("p95Ms", samples[ceil(count * 0.95).toInt() - 1] / 1e6)
            put("maxMs", samples[count - 1] / 1e6)
            if (budgetMs != null && waitsForGpu) {
                put("overBudget", (0 until count).count { samples[it] > budgetMs * 1e6 })
            }
        }
        report("active", null, fps.roundToInt(), details)
        count = 0
        since = endNs
    }

    private fun metadata() = JSONObject().apply {
        put("path", path)
        put("gpu", gpu)
        put("dimensions", dimensions)
        put("stages", stages)
        put("waitsForGpu", waitsForGpu)
        put("firstFrameMs", firstFrameMs)
        put("totalFrames", total)
        put("budgetMs", budgetMs)
    }
}
