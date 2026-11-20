package org.jellyfin.mobile.player.videoproxy

import android.content.Context
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import org.json.JSONObject

/** Implemented only by the opt-in debug fixture. */
@UnstableApi
internal interface OverlayBenchmark {
    val clip: MediaItem.ClippingConfiguration?
    fun configurePlayer(videoId: String, player: ExoPlayer)
    fun onCnnFrame(frame: Int, pts: Long, startNs: Long, width: Int, height: Int, draw: (Boolean) -> Unit)
}

@UnstableApi
internal object OverlayEnhancement {
    const val TAG = "OverlayEnhancement"

    val available = try {
        System.loadLibrary("overlay_gpu")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    fun supportsSize(width: Int, height: Int) =
        (width == 960 && height == 540) || (width == 1920 && height == 1080)

    fun ffmpeg(context: Context, renderer: Renderer, firstFrame: (Surface, Long) -> Unit): Renderer =
        if (available) EnhancedFfmpegRenderer(context, renderer, firstFrame) else renderer

    fun apply(player: ExoPlayer, json: String, status: EffectStatus) {
        if (json.isEmpty()) {
            player.setVideoEffects(emptyList())
            return
        }
        try {
            player.setVideoEffects(listOf(MenuGpuEffect(MenuPlan(JSONObject(json)), player.videoFormat?.frameRate, status)))
        } catch (e: Exception) {
            player.setVideoEffects(emptyList())
            status("error", "EnhancementModeInvalid", 0, null)
        }
    }
}
