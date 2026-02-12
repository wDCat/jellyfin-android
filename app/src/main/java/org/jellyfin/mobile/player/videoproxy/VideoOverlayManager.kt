package org.jellyfin.mobile.player.videoproxy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import com.google.android.exoplayer2.ui.SubtitleView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.webapp.WebappFunctionChannel
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages video overlay views and their corresponding ExoPlayer instances.
 * This class handles the creation, positioning, and lifecycle of video overlays
 * that render on top of the WebView.
 */
class VideoOverlayManager(
    private val context: Context,
    private val appPreferences: AppPreferences,
    private val webappFunctionChannel: WebappFunctionChannel,
    private val coroutineScope: CoroutineScope,
) : VideoProxyPlayerCallback {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayContainer: FrameLayout? = null
    private var webView: WebView? = null

    // Video ID -> Player instance
    private val players = ConcurrentHashMap<String, VideoProxyPlayer>()
    
    // Video ID -> Texture View
    private val textureViews = ConcurrentHashMap<String, TextureView>()

    // Video ID -> Subtitle View
    private val subtitleViews = ConcurrentHashMap<String, SubtitleView>()

    // Video ID -> Current bounds
    private val videoBounds = ConcurrentHashMap<String, VideoBounds>()
    
    // Video ID -> Visibility state
    private val videoVisibility = ConcurrentHashMap<String, Boolean>()

    // Progress update job
    private var progressUpdateJob: Job? = null
    private val progressUpdateIntervalMs = 250L

    /**
     * Event channel for receiving events from JavaScript bridge.
     */
    val eventChannel = Channel<VideoProxyEvent>(Channel.UNLIMITED)

    /**
     * Initialize the manager with the overlay container and WebView.
     */
    fun initialize(container: FrameLayout, webView: WebView) {
        this.overlayContainer = container
        this.webView = webView
        
        Timber.d("VideoOverlayManager initialized")
        
        // Start listening for events
        coroutineScope.launch {
            for (event in eventChannel) {
                handleEvent(event)
            }
        }

        // Start progress updates
        startProgressUpdates()
    }

    /**
     * Handle events from JavaScript bridge.
     */
    private fun handleEvent(event: VideoProxyEvent) {
        when (event) {
            is VideoProxyEvent.VideoCreated -> createVideoOverlay(event.videoId)
            is VideoProxyEvent.VideoDestroyed -> destroyVideoOverlay(event.videoId)
            is VideoProxyEvent.SetSource -> setVideoSource(event.videoId, event.src)
            is VideoProxyEvent.Play -> playVideo(event.videoId)
            is VideoProxyEvent.Pause -> pauseVideo(event.videoId)
            is VideoProxyEvent.Seek -> seekVideo(event.videoId, event.timeMs)
            is VideoProxyEvent.SetVolume -> setVideoVolume(event.videoId, event.volume)
            is VideoProxyEvent.SetPlaybackRate -> setVideoPlaybackRate(event.videoId, event.rate)
            is VideoProxyEvent.UpdateBounds -> updateVideoBounds(event.videoId, event.bounds)
            is VideoProxyEvent.SetVisibility -> setVideoVisibility(event.videoId, event.visible)
            is VideoProxyEvent.SetFullscreen -> setVideoFullscreen(event.videoId, event.fullscreen)
            is VideoProxyEvent.SetAudioTrack -> setAudioTrack(event.videoId, event.trackIndex)
            is VideoProxyEvent.SetSubtitleTrack -> setSubtitleTrack(event.videoId, event.trackIndex)
            is VideoProxyEvent.DisableSubtitleTrack -> disableSubtitleTrack(event.videoId)
        }
    }

    /**
     * Create a new video overlay for the given video ID.
     * This method runs on the main thread (called from lifecycleScope event handler),
     * so we can safely create views directly without mainHandler.post.
     */
    private fun createVideoOverlay(videoId: String) {
        Timber.d("Creating video overlay for $videoId")
        
        // Create player
        val player = VideoProxyPlayer(
            context = context,
            videoId = videoId,
            preferHardwareDecoding = appPreferences.videoProxyHardwareDecoding,
            callback = this,
        )
        player.initialize()
        players[videoId] = player

        // Create texture view directly (we're already on the main thread).
        // TextureView is below WebView in z-order (defined in layout XML).
        // WebView content is made transparent in the video area by JavaScript,
        // allowing the TextureView content to show through.
        // Touch events are handled by WebView directly (it's on top).
        val container = overlayContainer ?: return
        
        val textureView = TextureView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            // TextureView supports transparency by default when content has alpha
            isOpaque = false
            visibility = View.GONE
        }
        
        container.addView(textureView)
        textureViews[videoId] = textureView
        player.setTextureView(textureView)

        // Create subtitle view on top of the texture view
        val subtitleView = SubtitleView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            visibility = View.GONE
        }
        container.addView(subtitleView)
        subtitleViews[videoId] = subtitleView
        player.setSubtitleView(subtitleView)

        // Apply any pending bounds that arrived before the views were created
        videoBounds[videoId]?.let { bounds ->
            applyBoundsToTextureView(textureView, bounds)
            applyBoundsToSubtitleView(subtitleView, bounds)
        }

        Timber.d("TextureView and SubtitleView created for $videoId")
    }

    /**
     * Destroy a video overlay.
     */
    private fun destroyVideoOverlay(videoId: String) {
        Timber.d("Destroying video overlay for $videoId")

        players.remove(videoId)?.release()
        videoBounds.remove(videoId)
        videoVisibility.remove(videoId)

        textureViews.remove(videoId)?.let { textureView ->
            (textureView.parent as? ViewGroup)?.removeView(textureView)
        }
        subtitleViews.remove(videoId)?.let { subtitleView ->
            (subtitleView.parent as? ViewGroup)?.removeView(subtitleView)
        }
    }

    /**
     * Set the video source URL.
     */
    private fun setVideoSource(videoId: String, src: String) {
        Timber.d("Setting video source for $videoId: $src")
        players[videoId]?.setSource(src)
    }

    /**
     * Start playback.
     */
    private fun playVideo(videoId: String) {
        Timber.d("Playing video $videoId")
        players[videoId]?.play()

        // Show the texture view and subtitle view (already on main thread)
        textureViews[videoId]?.visibility = View.VISIBLE
        subtitleViews[videoId]?.visibility = View.VISIBLE
    }

    /**
     * Pause playback.
     */
    private fun pauseVideo(videoId: String) {
        Timber.d("Pausing video $videoId")
        players[videoId]?.pause()
    }

    /**
     * Seek to position.
     */
    private fun seekVideo(videoId: String, timeMs: Long) {
        Timber.d("Seeking video $videoId to $timeMs ms")
        players[videoId]?.seekTo(timeMs)
    }

    /**
     * Set video volume.
     */
    private fun setVideoVolume(videoId: String, volume: Float) {
        Timber.d("Setting volume for $videoId: $volume")
        players[videoId]?.setVolume(volume)
    }

    /**
     * Set video playback rate.
     */
    private fun setVideoPlaybackRate(videoId: String, rate: Float) {
        Timber.d("Setting playback rate for $videoId: $rate")
        players[videoId]?.setPlaybackRate(rate)
    }

    /**
     * Update video bounds (position and size).
     */
    private fun updateVideoBounds(videoId: String, bounds: VideoBounds) {
        videoBounds[videoId] = bounds

        textureViews[videoId]?.let { applyBoundsToTextureView(it, bounds) }
        subtitleViews[videoId]?.let { applyBoundsToSubtitleView(it, bounds) }
    }

    /**
     * Apply bounds to a TextureView, converting CSS pixels to device pixels.
     */
    private fun applyBoundsToTextureView(textureView: TextureView, bounds: VideoBounds) {
        val webView = webView ?: return
        
        // Convert WebView coordinates to screen coordinates
        val webViewLocation = IntArray(2)
        webView.getLocationOnScreen(webViewLocation)
        
        // Calculate position relative to overlay container
        val containerLocation = IntArray(2)
        overlayContainer?.getLocationOnScreen(containerLocation)
        
        val density = context.resources.displayMetrics.density
        
        // Convert CSS pixels to device pixels
        val left = (bounds.x * density).toInt() + webViewLocation[0] - containerLocation[0]
        val top = (bounds.y * density).toInt() + webViewLocation[1] - containerLocation[1]
        val width = (bounds.width * density).toInt()
        val height = (bounds.height * density).toInt()
        
        val layoutParams = textureView.layoutParams as? FrameLayout.LayoutParams ?: return
        layoutParams.width = width
        layoutParams.height = height
        layoutParams.leftMargin = left
        layoutParams.topMargin = top
        textureView.layoutParams = layoutParams
        
        Timber.d("Updated bounds: left=$left, top=$top, width=$width, height=$height")
    }

    /**
     * Apply bounds to a SubtitleView, using the same coordinate conversion as TextureView.
     */
    private fun applyBoundsToSubtitleView(subtitleView: SubtitleView, bounds: VideoBounds) {
        val webView = webView ?: return

        val webViewLocation = IntArray(2)
        webView.getLocationOnScreen(webViewLocation)

        val containerLocation = IntArray(2)
        overlayContainer?.getLocationOnScreen(containerLocation)

        val density = context.resources.displayMetrics.density

        val left = (bounds.x * density).toInt() + webViewLocation[0] - containerLocation[0]
        val top = (bounds.y * density).toInt() + webViewLocation[1] - containerLocation[1]
        val width = (bounds.width * density).toInt()
        val height = (bounds.height * density).toInt()

        val layoutParams = subtitleView.layoutParams as? FrameLayout.LayoutParams ?: return
        layoutParams.width = width
        layoutParams.height = height
        layoutParams.leftMargin = left
        layoutParams.topMargin = top
        subtitleView.layoutParams = layoutParams
    }

    /**
     * Set video visibility.
     */
    private fun setVideoVisibility(videoId: String, visible: Boolean) {
        Timber.d("Setting visibility for $videoId: $visible")
        videoVisibility[videoId] = visible
        val viewVisibility = if (visible) View.VISIBLE else View.GONE
        textureViews[videoId]?.visibility = viewVisibility
        subtitleViews[videoId]?.visibility = viewVisibility
    }

    /**
     * Set fullscreen mode.
     */
    private fun setVideoFullscreen(videoId: String, fullscreen: Boolean) {
        Timber.d("Setting fullscreen for $videoId: $fullscreen")

        val textureView = textureViews[videoId] ?: return
        val subtitleView = subtitleViews[videoId]

        if (fullscreen) {
            // Expand to fill the container
            val fullscreenParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                leftMargin = 0
                topMargin = 0
            }
            textureView.layoutParams = fullscreenParams
            subtitleView?.layoutParams = FrameLayout.LayoutParams(fullscreenParams)
        } else {
            // Restore original bounds
            videoBounds[videoId]?.let { bounds ->
                applyBoundsToTextureView(textureView, bounds)
                subtitleView?.let { applyBoundsToSubtitleView(it, bounds) }
            }
        }
    }

    /**
     * Set audio track for a video.
     */
    private fun setAudioTrack(videoId: String, trackIndex: Int) {
        Timber.d("Setting audio track $trackIndex for $videoId")
        players[videoId]?.setAudioTrack(trackIndex)
    }

    /**
     * Set subtitle track for a video.
     */
    private fun setSubtitleTrack(videoId: String, trackIndex: Int) {
        Timber.d("Setting subtitle track $trackIndex for $videoId")
        players[videoId]?.setSubtitleTrack(trackIndex)
    }

    /**
     * Disable all subtitle tracks for a video.
     */
    private fun disableSubtitleTrack(videoId: String) {
        Timber.d("Disabling subtitle track for $videoId")
        players[videoId]?.disableSubtitles()
    }

    // VideoProxyPlayerCallback implementation

    override fun onStateChanged(videoId: String, state: VideoProxyPlayerState) {
        // Notify JavaScript about state change
        val stateJson = """
            {
                "currentTime": ${state.currentTimeMs},
                "duration": ${state.durationMs},
                "paused": ${state.paused},
                "ended": ${state.ended},
                "readyState": ${state.readyState}
            }
        """.trimIndent()
        
        callJavaScript("VideoProxyCallback.onStateChange('$videoId', $stateJson)")
    }

    override fun onBuffering(videoId: String, isBuffering: Boolean) {
        callJavaScript("VideoProxyCallback.onBuffering('$videoId', $isBuffering)")
    }

    override fun onError(videoId: String, errorCode: Int, errorMessage: String) {
        val escapedMessage = errorMessage.replace("'", "\\'")
        callJavaScript("VideoProxyCallback.onError('$videoId', $errorCode, '$escapedMessage')")
    }

    override fun onTracksChanged(videoId: String, audioTracks: List<ProxyTrackInfo>, subtitleTracks: List<ProxyTrackInfo>) {
        val tracksJson = JSONObject().apply {
            put("audioTracks", JSONArray().apply {
                audioTracks.forEach { track ->
                    put(JSONObject().apply {
                        put("index", track.index)
                        put("language", track.language ?: "")
                        put("label", track.label ?: "")
                        put("codec", track.codec ?: "")
                        put("channelCount", track.channelCount)
                        put("isDefault", track.isDefault)
                        put("isForced", track.isForced)
                        put("isSelected", track.isSelected)
                    })
                }
            })
            put("subtitleTracks", JSONArray().apply {
                subtitleTracks.forEach { track ->
                    put(JSONObject().apply {
                        put("index", track.index)
                        put("language", track.language ?: "")
                        put("label", track.label ?: "")
                        put("codec", track.codec ?: "")
                        put("channelCount", track.channelCount)
                        put("isDefault", track.isDefault)
                        put("isForced", track.isForced)
                        put("isSelected", track.isSelected)
                    })
                }
            })
        }

        val escapedJson = tracksJson.toString().replace("'", "\\'")
        callJavaScript("VideoProxyCallback.onTracksChanged('$videoId', '$escapedJson')")
    }

    /**
     * Call JavaScript function in the WebView.
     */
    private fun callJavaScript(script: String) {
        webappFunctionChannel.call(script)
    }

    /**
     * Start periodic progress updates.
     */
    private fun startProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = coroutineScope.launch(Dispatchers.Main) {
            while (true) {
                players.values.forEach { player ->
                    player.updateProgress()
                }
                kotlinx.coroutines.delay(progressUpdateIntervalMs)
            }
        }
    }

    /**
     * Clean up all resources.
     */
    fun release() {
        Timber.d("Releasing VideoOverlayManager")
        
        progressUpdateJob?.cancel()
        eventChannel.close()
        
        // Release all players
        players.values.forEach { it.release() }
        players.clear()
        
        // Remove all overlay views
        mainHandler.post {
            textureViews.values.forEach { textureView ->
                (textureView.parent as? ViewGroup)?.removeView(textureView)
            }
            textureViews.clear()
            subtitleViews.values.forEach { subtitleView ->
                (subtitleView.parent as? ViewGroup)?.removeView(subtitleView)
            }
            subtitleViews.clear()
        }

        videoBounds.clear()
        videoVisibility.clear()
        overlayContainer = null
        webView = null
    }

    /**
     * Pause all active players (for Activity lifecycle).
     */
    fun pauseAll() {
        players.values.forEach { it.pause() }
    }

    /**
     * Resume all previously playing players (for Activity lifecycle).
     */
    fun resumeAll() {
        // Players will be resumed by JavaScript when appropriate
    }
}
