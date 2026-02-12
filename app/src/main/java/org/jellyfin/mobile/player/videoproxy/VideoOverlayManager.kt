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
import android.widget.TextView
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

    // Debug info
    private var debugInfoView: TextView? = null
    private var debugContainerView: View? = null
    private var debugInfoVisible = false
    private var debugUpdateCounter = 0

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
            is VideoProxyEvent.ToggleDebugInfo -> toggleDebugInfo()
        }
    }

    /**
     * Create a new video overlay for the given video ID.
     * This method runs on the main thread (called from lifecycleScope event handler),
     * so we can safely create views directly without mainHandler.post.
     */
    private fun createVideoOverlay(videoId: String) {
        Timber.d("Creating video overlay for $videoId (existing players: ${players.size})")

        // If a player already exists for this video ID, clean it up first
        players.remove(videoId)?.let { oldPlayer ->
            Timber.d("Releasing existing player for $videoId before re-creating")
            oldPlayer.release()
        }
        textureViews.remove(videoId)?.let { oldTexture ->
            (oldTexture.parent as? ViewGroup)?.removeView(oldTexture)
        }
        subtitleViews.remove(videoId)?.let { oldSubtitle ->
            (oldSubtitle.parent as? ViewGroup)?.removeView(oldSubtitle)
        }

        // Clean up stale players that have no source (idle video elements that were
        // created but never used). This prevents accumulation of unused ExoPlayer instances.
        val staleIds = players.entries
            .filter { (id, _) -> id != videoId }
            .filter { (_, player) -> player.currentState.readyState == 0 && player.getDuration() == 0L }
            .map { it.key }
        for (staleId in staleIds) {
            Timber.d("Cleaning up stale player: $staleId")
            destroyVideoOverlay(staleId)
        }
        
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

        // Auto-hide debug info when all players are destroyed (playback ended)
        if (players.isEmpty()) {
            hideDebugInfo()
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
     * Also handles debug info updates when visible (every ~500ms via counter).
     */
    private fun startProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = coroutineScope.launch(Dispatchers.Main) {
            while (true) {
                players.values.forEach { player ->
                    player.updateProgress()
                }
                // Update debug info every ~500ms (every 2nd progress tick at 250ms interval)
                if (debugInfoVisible) {
                    debugUpdateCounter++
                    if (debugUpdateCounter >= 2) {
                        debugUpdateCounter = 0
                        try {
                            updateDebugInfo()
                        } catch (e: Exception) {
                            Timber.e(e, "Error updating debug info")
                        }
                    }
                }
                kotlinx.coroutines.delay(progressUpdateIntervalMs)
            }
        }
    }

    /**
     * Set the debug info TextView and its outer container (for visibility control).
     */
    fun setDebugInfoView(textView: TextView, container: View) {
        debugInfoView = textView
        debugContainerView = container
    }

    /**
     * Toggle the debug info overlay visibility.
     * Debug updates are driven by the existing progress update loop,
     * so no separate coroutine is needed.
     * @return true if the debug info is now visible, false otherwise.
     */
    fun toggleDebugInfo(): Boolean {
        debugInfoVisible = !debugInfoVisible
        debugUpdateCounter = 0
        if (debugInfoVisible) {
            debugContainerView?.visibility = View.VISIBLE
            // Force an immediate update so user sees info right away
            try {
                updateDebugInfo()
            } catch (e: Exception) {
                Timber.e(e, "Error during initial debug info update")
            }
        } else {
            debugContainerView?.visibility = View.GONE
        }
        Timber.d("Debug info toggled: visible=$debugInfoVisible")
        return debugInfoVisible
    }

    /**
     * Hide the debug info overlay (called from close button).
     */
    fun hideDebugInfo() {
        if (debugInfoVisible) {
            debugInfoVisible = false
            debugContainerView?.visibility = View.GONE
            Timber.d("Debug info hidden")
        }
    }

    /**
     * Update the debug info text view with current ExoPlayer stats.
     * Called from the progress update loop on the main thread.
     */
    private fun updateDebugInfo() {
        val view = debugInfoView ?: return

        val playerEntries = players.entries.toList()
        if (playerEntries.isEmpty()) {
            view.text = buildString {
                appendLine("ExoPlayer Video Proxy Debug")
                appendLine("═══════════════════════════")
                append("Players: 0 (no video element created)")
            }
            return
        }

        // Find the best player to show details for:
        // prefer one that is actively proxying (has source), then any
        val allInfos = playerEntries.map { (id, player) -> id to player.getDebugInfo() }
        val activeInfo = allInfos.firstOrNull { it.second.isProxying }
            ?: allInfos.first()

        val info = activeInfo.second
        val posStr = formatTime(info.currentPosition)
        val durStr = formatTime(info.duration)
        val bufStr = formatTime(info.bufferPosition)

        // Truncate source URL for display
        val sourceDisplay = when {
            info.sourceUrl.isEmpty() -> "(none - not proxied)"
            info.sourceUrl.length > 60 -> "...${info.sourceUrl.takeLast(57)}"
            else -> info.sourceUrl
        }

        view.text = buildString {
            appendLine("ExoPlayer Video Proxy Debug")
            appendLine("═══════════════════════════")
            appendLine("Players: ${playerEntries.size}")
            // Show brief status of all players
            allInfos.forEach { (id, pi) ->
                val marker = if (id == activeInfo.first) "▸" else " "
                appendLine("$marker $id: ${pi.playbackState} exo=${pi.hasExoPlayer} src=${pi.isProxying}")
            }
            appendLine("═══════════════════════════")
            appendLine("Active: ${info.videoId}")
            appendLine("State: ${info.playbackState}")
            appendLine("ExoPlayer: ${if (info.hasExoPlayer) "initialized" else "NULL"}")
            appendLine("Source: $sourceDisplay")
            appendLine("─── Video ───")
            appendLine("Decoder: ${info.videoDecoderName}")
            appendLine("HW Accel: ${if (info.isHardwareDecoding) "Yes" else "No"}")
            appendLine("Format: ${info.videoFormat}")
            appendLine("Resolution: ${info.resolution}")
            appendLine("Frame Rate: ${info.frameRate}")
            appendLine("Bitrate: ${info.videoBitrate}")
            appendLine("Surface: ${info.surfaceSize}")
            appendLine("Frames: ${info.renderedFrames} rendered, ${info.droppedFrames} dropped")
            appendLine("─── Audio ───")
            appendLine("Decoder: ${info.audioDecoderName}")
            appendLine("Format: ${info.audioFormat}")
            appendLine("Bitrate: ${info.audioBitrate}")
            appendLine("Volume: ${String.format("%.0f%%", info.volume * 100)}")
            appendLine("─── Playback ───")
            appendLine("Position: $posStr / $durStr")
            appendLine("Buffered: $bufStr")
            append("Speed: ${info.playbackSpeed}x")
        }
    }

    /**
     * Format milliseconds to HH:MM:SS or MM:SS.
     */
    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Check if there are any active players (for showing/hiding debug toggle).
     */
    fun hasActivePlayers(): Boolean = players.isNotEmpty()

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
        debugInfoView = null
        debugContainerView = null
        debugInfoVisible = false
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
