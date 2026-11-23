package org.jellyfin.mobile.player.videoproxy

import android.app.Activity
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import android.view.Surface
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer

@UnstableApi
internal object OverlayTrial {
    const val TAG = "Anime4kTrial"
    fun supportsSize(width: Int, height: Int) =
        (width == 960 && height == 540) || (width == 1920 && height == 1080)

    val available: Boolean get() = PlaceboNative.available

    fun ffmpeg(context: Context, renderer: Renderer, firstFrame: (Surface, Long) -> Unit): Renderer =
        if (available) EnhancedFfmpegRenderer(context, renderer, firstFrame) else renderer

    fun apply(
        player: ExoPlayer, json: String, useNpu: Boolean, ignoreNpuBudget: Boolean = false, fixture: Boolean = false,
        compilationActivity: Activity? = null, status: EffectStatus,
    ) {
        if (json.isEmpty()) {
            player.setVideoEffects(emptyList())
            return
        }
        try {
            val plan = JSONObject(json).put("accelerator", if (useNpu) "npu" else "gpu")
            player.setVideoEffects(listOf(MenuGpuEffect(
                MenuPlan(plan), player.videoFormat?.frameRate, fixture, ignoreNpuBudget, compilationActivity, status,
            )))
        } catch (e: Exception) {
            player.setVideoEffects(emptyList())
            status("error", "EnhancementModeInvalid", 0, null)
        }
    }

    fun configureItem(context: Context, item: MediaItem.Builder) {
        if (context is OverlayTrialActivity) context.clip?.let(item::setClippingConfiguration)
    }

    fun configure(context: Context, videoId: String, player: ExoPlayer) {
        if (!available) return
        val bench = context is OverlayTrialActivity
        // Prime Media3 before prepare. Ordinary playback follows the Web menu.
        player.setVideoEffects(emptyList())
        if (context is OverlayTrialActivity && context.intent.getBooleanExtra("hybrid", true)) {
            val model = context.intent.getStringExtra("model") ?: "anime4k/Upscale/CNNx2S"
            val accelerator = context.intent.getStringExtra("accelerator") ?: "gpu"
            val width = if (context.intent.getBooleanExtra("catalogCheck", false)) 128 else if (context.clip == null) 960 else 1920
            val height = width * 9 / 16
            val anime4kA = context.intent.getBooleanExtra("anime4kA", false)
            val ids = if (anime4kA) {
                GpuBenchmark.presetEffects(context, NpuBenchmarkPreset("A", NpuBenchmarkTier.FAST))
            } else listOf(model)
            val effects = JSONArray(ids.map { JSONObject().put("id", it) })
            context.intent.getStringExtra("postFilter")?.let { effects.put(JSONObject().put("id", it)) }
            val json = JSONObject().put("effects", effects)
                .put("target", JSONObject().put("width", width*2).put("height", height*2))
            val ignoreNpuBudget = !context.intent.getBooleanExtra("npuRealtime", false) ||
                context.intent.getBooleanExtra("ignoreNpuBudget", false)
            val compilationActivity = context.takeIf { it.intent.getBooleanExtra("confirmNpuCompilation", false) }
            apply(player, json.toString(), accelerator == "npu", ignoreNpuBudget, fixture = true,
                compilationActivity = compilationActivity) {
                    state, reason, fps, diag ->
                val fixtureModel = if (anime4kA) "Anime4K A (performance)" else model
                Log.i(TAG, "model=$fixtureModel accel=$accelerator state=$state reason=$reason fps=$fps diag=$diag")
            }
        }
        if (!bench) return
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) { Log.i(TAG, "decoder=$decoderName") }
        })
        var started = 0L
        // PTS + requested release time allow offline checks for skipped frames and release cadence.
        player.setVideoFrameMetadataListener { pts, release, _, _ ->
            Log.i(TAG, "release pts=$pts ns=$release")
        }
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (started == 0L) started = System.nanoTime()
                Log.i(TAG, "first id=$videoId decoder=${player.videoFormat?.sampleMimeType}")
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "error id=$videoId", error)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                val c = player.videoDecoderCounters ?: return
                c.ensureUpdated()
                Log.i(TAG, "ended id=$videoId rendered=${c.renderedOutputBufferCount} dropped=${c.droppedBufferCount} " +
                    "skipped=${c.skippedOutputBufferCount} maxConsecutive=${c.maxConsecutiveDroppedBufferCount} " +
                    "elapsedMs=${if (started == 0L) null else (System.nanoTime()-started)/1e6}")
            }
        })
    }
}
