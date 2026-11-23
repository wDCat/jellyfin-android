package org.jellyfin.mobile.player.videoproxy

import android.content.Context
import android.util.Size

@Suppress("UNUSED_PARAMETER")
internal object NpuRuntime {
    const val available = false
    fun summary(context: Context) = "Requires the enhancement debug build"
    fun modelIds(context: Context): List<String> = emptyList()
    suspend fun benchmark(
        context: Context, modelId: String?, width: Int, height: Int, sampleCount: Int, fps: Float,
        preset: NpuBenchmarkPreset?, presetOutput: Size?, onProgress: suspend (Int?, Int) -> Unit,
    ) = summary(context)
}
