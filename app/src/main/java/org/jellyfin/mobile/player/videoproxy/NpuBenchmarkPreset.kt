package org.jellyfin.mobile.player.videoproxy

import android.content.Context
import androidx.annotation.StringRes
import org.jellyfin.mobile.R

internal enum class NpuBenchmarkTier(val id: String, @StringRes val label: Int) {
    FAST("performance", R.string.npu_bench_tier_fast),
    BALANCED("balanced", R.string.npu_bench_tier_balanced),
    QUALITY("quality", R.string.npu_bench_tier_quality),
    ULTRA("ultra", R.string.npu_bench_tier_ultra),
}

internal data class NpuBenchmarkPreset(val mode: String, val tier: NpuBenchmarkTier) {
    fun displayName(context: Context) =
        context.getString(R.string.npu_bench_preset_name, mode, context.getString(tier.label))
}
