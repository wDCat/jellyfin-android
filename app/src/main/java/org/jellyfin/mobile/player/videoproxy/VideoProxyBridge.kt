package org.jellyfin.mobile.player.videoproxy

import android.webkit.JavascriptInterface
import kotlinx.coroutines.channels.Channel
import org.jellyfin.mobile.app.AppPreferences
import org.json.JSONObject
import timber.log.Timber

/**
 * JavaScript bridge interface for video element proxy.
 * This class provides methods that can be called from JavaScript to control
 * the native ExoPlayer instance that renders video content.
 */
@Suppress("unused")
class VideoProxyBridge(
    private val appPreferences: AppPreferences,
    private val videoProxyEventChannel: Channel<VideoProxyEvent>,
) {
    /**
     * Check if video proxy mode is enabled.
     */
    @JavascriptInterface
    fun isEnabled(): Boolean = appPreferences.videoProxyEnabled

    /**
     * Check if hardware decoding is preferred.
     */
    @JavascriptInterface
    fun isHardwareDecodingEnabled(): Boolean = appPreferences.videoProxyHardwareDecoding

    /**
     * Called when a video element is created and needs to be proxied.
     * @param videoId Unique identifier for the video element
     */
    @JavascriptInterface
    fun onVideoElementCreated(videoId: String) {
        Timber.d("Video element created: $videoId")
        videoProxyEventChannel.trySend(VideoProxyEvent.VideoCreated(videoId))
    }

    /**
     * Called when a video element is destroyed.
     * @param videoId Unique identifier for the video element
     */
    @JavascriptInterface
    fun onVideoElementDestroyed(videoId: String) {
        Timber.d("Video element destroyed: $videoId")
        videoProxyEventChannel.trySend(VideoProxyEvent.VideoDestroyed(videoId))
    }

    /**
     * Called when the video source is set.
     * @param videoId Unique identifier for the video element
     * @param src The video source URL
     */
    @JavascriptInterface
    fun setSource(videoId: String, src: String) {
        Timber.d("Set source for $videoId: $src")
        videoProxyEventChannel.trySend(VideoProxyEvent.SetSource(videoId, src))
    }

    /**
     * Called when play() is invoked on the video element.
     * @param videoId Unique identifier for the video element
     */
    @JavascriptInterface
    fun play(videoId: String) {
        Timber.d("Play video: $videoId")
        videoProxyEventChannel.trySend(VideoProxyEvent.Play(videoId))
    }

    /**
     * Called when pause() is invoked on the video element.
     * @param videoId Unique identifier for the video element
     */
    @JavascriptInterface
    fun pause(videoId: String) {
        Timber.d("Pause video: $videoId")
        videoProxyEventChannel.trySend(VideoProxyEvent.Pause(videoId))
    }

    /**
     * Called when currentTime is set on the video element.
     * @param videoId Unique identifier for the video element
     * @param timeMs The seek position in milliseconds
     */
    @JavascriptInterface
    fun seek(videoId: String, timeMs: Long) {
        Timber.d("Seek video $videoId to $timeMs ms")
        videoProxyEventChannel.trySend(VideoProxyEvent.Seek(videoId, timeMs))
    }

    /**
     * Called when volume is set on the video element.
     * @param videoId Unique identifier for the video element
     * @param volume The volume level (0.0 to 1.0)
     */
    @JavascriptInterface
    fun setVolume(videoId: String, volume: Float) {
        Timber.d("Set volume for $videoId: $volume")
        videoProxyEventChannel.trySend(VideoProxyEvent.SetVolume(videoId, volume))
    }

    /**
     * Called when playbackRate is set on the video element.
     * @param videoId Unique identifier for the video element
     * @param rate The playback rate (1.0 = normal speed)
     */
    @JavascriptInterface
    fun setPlaybackRate(videoId: String, rate: Float) {
        Timber.d("Set playback rate for $videoId: $rate")
        videoProxyEventChannel.trySend(VideoProxyEvent.SetPlaybackRate(videoId, rate))
    }

    /**
     * Called to update the video element position and size.
     * @param videoId Unique identifier for the video element
     * @param boundsJson JSON object containing x, y, width, height
     */
    @JavascriptInterface
    fun updateBounds(videoId: String, boundsJson: String) {
        try {
            val json = JSONObject(boundsJson)
            val bounds = VideoBounds(
                x = json.getDouble("x").toFloat(),
                y = json.getDouble("y").toFloat(),
                width = json.getDouble("width").toFloat(),
                height = json.getDouble("height").toFloat(),
            )
            videoProxyEventChannel.trySend(VideoProxyEvent.UpdateBounds(videoId, bounds))
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse bounds JSON")
        }
    }

    /**
     * Called when the video element visibility changes.
     * @param videoId Unique identifier for the video element
     * @param visible Whether the video element is visible
     */
    @JavascriptInterface
    fun setVisibility(videoId: String, visible: Boolean) {
        Timber.d("Set visibility for $videoId: $visible")
        videoProxyEventChannel.trySend(VideoProxyEvent.SetVisibility(videoId, visible))
    }

    /**
     * Called when fullscreen mode is requested.
     * @param videoId Unique identifier for the video element
     * @param fullscreen Whether fullscreen mode is enabled
     */
    @JavascriptInterface
    fun setFullscreen(videoId: String, fullscreen: Boolean) {
        Timber.d("Set fullscreen for $videoId: $fullscreen")
        videoProxyEventChannel.trySend(VideoProxyEvent.SetFullscreen(videoId, fullscreen))
    }

    /**
     * Called when the user selects a different audio track via the web OSD.
     * @param videoId Unique identifier for the video element
     * @param trackIndex The index of the audio track group to select
     */
    @JavascriptInterface
    fun setAudioTrack(videoId: String, trackIndex: Int) {
        Timber.d("Set audio track for $videoId: $trackIndex")
        videoProxyEventChannel.trySend(VideoProxyEvent.SetAudioTrack(videoId, trackIndex))
    }

    /**
     * Called when the user selects a different subtitle track via the web OSD.
     * @param videoId Unique identifier for the video element
     * @param trackIndex The index of the subtitle track group to select
     */
    @JavascriptInterface
    fun setSubtitleTrack(videoId: String, trackIndex: Int) {
        Timber.d("Set subtitle track for $videoId: $trackIndex")
        videoProxyEventChannel.trySend(VideoProxyEvent.SetSubtitleTrack(videoId, trackIndex))
    }

    /**
     * Called when the user disables all subtitle tracks via the web OSD.
     * @param videoId Unique identifier for the video element
     */
    @JavascriptInterface
    fun disableSubtitleTrack(videoId: String) {
        Timber.d("Disable subtitle track for $videoId")
        videoProxyEventChannel.trySend(VideoProxyEvent.DisableSubtitleTrack(videoId))
    }

    /**
     * Toggle ExoPlayer debug info overlay.
     * Called from the injected OSD button in the Jellyfin web player.
     */
    @JavascriptInterface
    fun toggleDebugInfo() {
        Timber.d("Toggle debug info")
        videoProxyEventChannel.trySend(VideoProxyEvent.ToggleDebugInfo)
    }

    /**
     * Check if a URL should be handled by the proxy.
     * Supports both Direct Play URLs and HLS URLs (resolved from blob: by JS).
     * ExoPlayer has native HLS support, so we can proxy .m3u8 streams too.
     *
     * @param url The video URL to check (must be a real HTTP/HTTPS URL, not blob:)
     * @return true if the URL should be proxied, false otherwise
     */
    @JavascriptInterface
    fun shouldProxyUrl(url: String): Boolean {
        // Reject non-HTTP(S) URLs that ExoPlayer cannot handle.
        // blob: URLs are created by the browser via URL.createObjectURL(MediaSource)
        // and are internal browser references that ExoPlayer cannot resolve.
        // data: URLs are also browser-only constructs.
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Timber.d("Should proxy URL: false (unsupported scheme) - ${url.take(80)}")
            return false
        }

        // Proxy any HTTP(S) URL when proxy is enabled.
        // This includes both direct play URLs and HLS .m3u8 URLs
        // (which JS resolves from blob: URLs created by hls.js).
        // ExoPlayer handles both direct streams and HLS natively.
        val shouldProxy = appPreferences.videoProxyEnabled
        Timber.d("Should proxy URL: $shouldProxy - $url")
        return shouldProxy
    }
}

/**
 * Represents the bounds of a video element.
 */
data class VideoBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * Sealed class representing events from JavaScript to native.
 */
sealed class VideoProxyEvent {
    data class VideoCreated(val videoId: String) : VideoProxyEvent()
    data class VideoDestroyed(val videoId: String) : VideoProxyEvent()
    data class SetSource(val videoId: String, val src: String) : VideoProxyEvent()
    data class Play(val videoId: String) : VideoProxyEvent()
    data class Pause(val videoId: String) : VideoProxyEvent()
    data class Seek(val videoId: String, val timeMs: Long) : VideoProxyEvent()
    data class SetVolume(val videoId: String, val volume: Float) : VideoProxyEvent()
    data class SetPlaybackRate(val videoId: String, val rate: Float) : VideoProxyEvent()
    data class UpdateBounds(val videoId: String, val bounds: VideoBounds) : VideoProxyEvent()
    data class SetVisibility(val videoId: String, val visible: Boolean) : VideoProxyEvent()
    data class SetFullscreen(val videoId: String, val fullscreen: Boolean) : VideoProxyEvent()
    data class SetAudioTrack(val videoId: String, val trackIndex: Int) : VideoProxyEvent()
    data class SetSubtitleTrack(val videoId: String, val trackIndex: Int) : VideoProxyEvent()
    data class DisableSubtitleTrack(val videoId: String) : VideoProxyEvent()
    data object ToggleDebugInfo : VideoProxyEvent()
}
