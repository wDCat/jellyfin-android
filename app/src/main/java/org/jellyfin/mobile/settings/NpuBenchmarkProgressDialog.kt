package org.jellyfin.mobile.settings

import android.app.Activity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.ViewCompat
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.R
import kotlin.coroutines.resume

internal fun npuDialogBuilder(activity: Activity) =
    MaterialAlertDialogBuilder(ContextThemeWrapper(activity, R.style.AppTheme_Settings))

internal suspend fun confirmNpuCompilation(activity: Activity, models: List<String>, width: Int, height: Int): Boolean {
    if (activity.isFinishing || activity.isDestroyed || activity.isChangingConfigurations) return false
    return suspendCancellableCoroutine { continuation ->
        fun reply(compile: Boolean) {
            if (continuation.isActive) continuation.resume(compile)
        }
        val dialog = npuDialogBuilder(activity)
            .setTitle(R.string.npu_playback_compile_title)
            .setMessage(activity.getString(R.string.npu_playback_compile_message, width, height, models.joinToString("\n")))
            .setPositiveButton(R.string.npu_playback_compile_action) { _, _ -> reply(true) }
            .setNegativeButton(R.string.npu_playback_keep_gpu) { _, _ -> reply(false) }
            .setOnCancelListener { reply(false) }
            .show()
        continuation.invokeOnCancellation { activity.runOnUiThread { dialog.dismiss() } }
    }
}

internal suspend fun <T> runWithNpuProgress(
    builder: MaterialAlertDialogBuilder,
    show: (MaterialAlertDialogBuilder) -> AlertDialog = { it.show() },
    onCancel: () -> Unit = {},
    @StringRes progressLabel: Int = R.string.npu_bench_sampling,
    @StringRes finalPhase: Int = R.string.npu_bench_compiling,
    @StringRes initialPhase: Int = R.string.npu_bench_compiling,
    work: suspend (onProgress: suspend (completed: Int?, total: Int) -> Unit) -> T,
): T = coroutineScope {
    val context = builder.context
    val phase = TextView(context).apply {
        setText(initialPhase)
        ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
    }
    val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
    }
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPaddingRelative(padding, padding / 2, padding, 0)
        addView(phase)
        addView(progress, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    fun cancelBenchmark() {
        onCancel()
        cancel()
    }
    val dialog = show(
        builder.setView(content)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelBenchmark() }
            .setOnCancelListener { cancelBenchmark() },
    )
    dialog.setCanceledOnTouchOutside(false)
    try {
        withContext(Dispatchers.Default) {
            work { completed, total ->
                withContext(Dispatchers.Main) {
                    progress.isIndeterminate = completed == null || completed == 0
                    if (completed == null) {
                        phase.setText(if (total == 0) finalPhase else initialPhase)
                    } else {
                        phase.text = context.getString(progressLabel, completed, total)
                        progress.max = total
                        progress.progress = completed
                    }
                }
            }
        }
    } finally {
        dialog.dismiss()
    }
}
