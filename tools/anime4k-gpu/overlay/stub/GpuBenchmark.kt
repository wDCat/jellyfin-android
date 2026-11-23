package org.jellyfin.mobile.player.videoproxy

import android.content.Context
import android.util.Size
import org.jellyfin.mobile.R

@Suppress("UNUSED_PARAMETER")
internal object GpuBenchmark {
    const val available = false
    fun modelIds(context: Context): List<String> = emptyList()
    fun presets(context: Context): List<NpuBenchmarkPreset> = emptyList()
    suspend fun benchmark(
        context: Context, modelId: String?, width: Int, height: Int, sampleCount: Int, fps: Float,
        preset: NpuBenchmarkPreset?, presetOutput: Size?,
        onProgress: suspend (Int?, Int) -> Unit,
    ) = context.getString(R.string.gpu_bench_unavailable)
}
