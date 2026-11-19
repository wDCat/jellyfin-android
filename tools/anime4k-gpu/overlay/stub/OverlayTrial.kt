package org.jellyfin.mobile.player.videoproxy

import android.content.Context
import org.json.JSONObject
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.ExoPlayer

@Suppress("UNUSED_PARAMETER")
internal object OverlayTrial {
    const val available = false
    fun apply(player: ExoPlayer, json: String, status: (String, String?, Int, JSONObject?) -> Unit) = Unit
    fun ffmpeg(context: Context, renderer: Renderer, firstFrame: (Surface, Long) -> Unit) = renderer
    fun configureItem(context: Context, item: MediaItem.Builder) = Unit
    fun configure(context: Context, videoId: String, player: ExoPlayer) = Unit
}
