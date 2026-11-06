package org.jellyfin.mobile.player.videoproxy

import android.os.SystemClock
import android.webkit.WebView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** Statistics exposed to the web playback info panel, without authenticated source URLs. */
fun VideoProxyDebugInfo.toPlaybackInfoJson(): JSONObject = JSONObject().apply {
    put("videoId", videoId)
    put("isProxying", isProxying)
    put("playbackState", playbackState)
    put("videoDecoderName", videoDecoderName)
    put("audioDecoderName", audioDecoderName)
    put("videoFormat", videoFormat)
    put("audioFormat", audioFormat)
    put("resolution", resolution)
    put("frameRate", frameRate)
    put("videoBitrate", videoBitrate)
    put("audioBitrate", audioBitrate)
    put("droppedFrames", droppedFrames)
    put("renderedFrames", renderedFrames)
    put("bufferPosition", bufferPosition)
    put("currentPosition", currentPosition)
    put("duration", duration)
    put("playbackSpeed", playbackSpeed)
    put("volume", volume)
    put("isHardwareDecoding", isHardwareDecoding ?: JSONObject.NULL)
    put("surfaceSize", surfaceSize)
    put("networkBandwidth", networkBandwidth)
    put("textTrackEnabled", textTrackEnabled)
    val tracks = subtitleTracks.map { track ->
        JSONObject().apply {
            put("index", track.index)
            put("language", track.language)
            put("label", track.label)
            put("codec", track.codec)
            put("isSelected", track.isSelected)
        }
    }
    put("subtitleTracks", JSONArray(tracks))
}

/** Owns the optional statistics subscription, never the player's playback lifecycle. */
@UnstableApi
class VideoProxyPlaybackInfoReporter(
    private val players: Map<String, VideoProxyPlayer>,
    private val publish: (JSONObject) -> Unit,
) {
    private companion object {
        const val WARNING_INTERVAL_MS = 30000L
    }

    private var subscription: VideoProxyEvent.SetPlaybackInfoVisible? = null
    private var foreground = true
    private var lastWarningAt = -WARNING_INTERVAL_MS

    fun subscribe(event: VideoProxyEvent.SetPlaybackInfoVisible, view: WebView?) {
        subscription = event.takeIf { it.visible }
        resetSampling()
        update(view)
    }

    fun setForeground(value: Boolean) {
        foreground = value
        resetSampling()
    }

    fun release() {
        subscription = null
        foreground = false
        resetSampling()
    }

    private fun resetSampling() {
        val batch = subscription
        players.forEach { (videoId, player) ->
            val enabled = foreground && batch?.collectPerformance == true && batch.videoId == videoId
            player.resetPerformanceSubscription(enabled)
        }
    }

    fun update(view: WebView?) {
        val batch = subscription ?: return
        if (!foreground || view == null) return
        // Statistics failures must never propagate into playback or event handling.
        runCatching { publish(payload(batch, view)) }.onFailure {
            val now = SystemClock.elapsedRealtime()
            if (now - lastWarningAt >= WARNING_INTERVAL_MS) {
                lastWarningAt = now
                Timber.w("Unable to sample video proxy playback information")
            }
        }
    }

    private fun payload(batch: VideoProxyEvent.SetPlaybackInfoVisible, view: WebView): JSONObject {
        val sampledPlayers = if (batch.videoId.isEmpty()) players.values else listOfNotNull(players[batch.videoId])
        val samples = sampledPlayers.map { player ->
            player.getDebugInfo().toPlaybackInfoJson().apply {
                // Legacy diagnostics do not need performance samples.
                if (batch.collectPerformance && batch.videoId.isNotEmpty()) {
                    player.samplePerformance()?.let { put("performance", it) }
                }
            }
        }
        val cutoutType = WindowInsetsCompat.Type.displayCutout()
        val insets = ViewCompat.getRootWindowInsets(view)?.getInsets(cutoutType) ?: Insets.NONE
        return JSONObject().apply {
            put("generation", batch.generation)
            put("scope", batch.scope ?: JSONObject.NULL)
            put("players", JSONArray(samples))
            put("viewportWidth", view.width)
            put(
                "safeArea",
                JSONObject().apply {
                    put("left", insets.left)
                    put("right", insets.right)
                },
            )
        }
    }
}
