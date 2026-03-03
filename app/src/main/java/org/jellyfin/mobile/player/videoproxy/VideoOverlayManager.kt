package org.jellyfin.mobile.player.videoproxy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.player.deviceprofile.DeviceProfileBuilder
import org.jellyfin.mobile.player.source.MediaSourceResolver
import org.jellyfin.mobile.webapp.WebappFunctionChannel
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages video overlay views and their corresponding ExoPlayer instances.
 * This class handles the creation, positioning, and lifecycle of video overlays
 * that render on top of the WebView.
 */
@UnstableApi
class VideoOverlayManager(
    private val context: Context,
    private val appPreferences: AppPreferences,
    private val webappFunctionChannel: WebappFunctionChannel,
    private val coroutineScope: CoroutineScope,
    private val mediaSourceResolver: MediaSourceResolver,
    private val apiClient: ApiClient,
    private val deviceProfileBuilder: DeviceProfileBuilder,
) : VideoProxyPlayerCallback {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayContainer: FrameLayout? = null
    private var subtitleContainer: FrameLayout? = null
    private var webView: WebView? = null

    // Video ID -> Player instance
    private val players = ConcurrentHashMap<String, VideoProxyPlayer>()
    
    // Video ID -> Surface View
    private val surfaceViews = ConcurrentHashMap<String, SurfaceView>()

    // Video ID -> Subtitle View (for natively-rendered tracks like SubRip)
    private val subtitleViews = ConcurrentHashMap<String, SubtitleView>()

    // Video ID -> Current bounds
    private val videoBounds = ConcurrentHashMap<String, VideoBounds>()
    
    // Video ID -> Visibility state
    private val videoVisibility = ConcurrentHashMap<String, Boolean>()

    // Video ID -> Native video size (for aspect ratio correction)
    private data class VideoNativeSize(val width: Int, val height: Int, val pixelWidthHeightRatio: Float)
    private val videoNativeSizes = ConcurrentHashMap<String, VideoNativeSize>()

    // Progress update job
    private var progressUpdateJob: Job? = null
    private val progressUpdateIntervalMs = 250L

    // Debug info
    private var debugInfoView: TextView? = null
    private var debugContainerView: View? = null
    private var debugInfoVisible = false
    private var debugUpdateCounter = 0

    // Bitrate indicator (top-right corner, shown during buffering)
    private var bitrateIndicatorView: TextView? = null
    private var bitrateIndicatorVisible = false

    /**
     * Event channel for receiving events from JavaScript bridge.
     */
    val eventChannel = Channel<VideoProxyEvent>(Channel.UNLIMITED)

    /**
     * Initialize the manager with the overlay container, subtitle container, and WebView.
     * @param container FrameLayout below the WebView for SurfaceViews (hole-punching)
     * @param subtitleContainer FrameLayout above the WebView for SubtitleViews
     * @param webView The WebView instance
     */
    fun initialize(container: FrameLayout, subtitleContainer: FrameLayout, webView: WebView) {
        this.overlayContainer = container
        this.subtitleContainer = subtitleContainer
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
            is VideoProxyEvent.ResolveAndSetSource -> resolveAndSetVideoSource(event.videoId, event.itemId, event.mediaSourceId)
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
        surfaceViews.remove(videoId)?.let { oldSurface ->
            (oldSurface.parent as? ViewGroup)?.removeView(oldSurface)
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

        // Create SurfaceView (supports HDR output via hardware overlay).
        // SurfaceView uses hole-punching: it renders behind the Window by default,
        // and the WebView's transparent area lets the video show through.
        val container = overlayContainer ?: return
        
        val surfaceView = SurfaceView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            visibility = View.GONE
        }
        
        container.addView(surfaceView)
        surfaceViews[videoId] = surfaceView
        player.setSurfaceView(surfaceView)

        // Create subtitle view in a separate container BELOW the WebView.
        // SubtitleView is visible through the transparent video area of the WebView
        // (makeTransparent() in VideoElementProxy.js clears all ancestor backgrounds).
        // Placing it below the WebView ensures the OSD controls naturally appear on top.
        val subContainer = subtitleContainer ?: container
        val subtitleView = SubtitleView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            visibility = View.GONE
            // A transparent background drawable is needed so this View is excluded
            // from SurfaceView's transparent region. Without it the compositor
            // skips alpha blending here, making semi-transparent backgrounds opaque.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        applyDefaultSubtitleStyle(subtitleView)
        subContainer.addView(subtitleView)
        subtitleViews[videoId] = subtitleView
        player.setSubtitleView(subtitleView)

        // Apply any pending bounds that arrived before the views were created
        videoBounds[videoId]?.let { bounds ->
            applyBoundsToSurfaceView(surfaceView, bounds)
            applyBoundsToSubtitleView(subtitleView, bounds)
        }

        Timber.d("SurfaceView and SubtitleView created for $videoId")
    }

    /**
     * Destroy a video overlay.
     */
    private fun destroyVideoOverlay(videoId: String) {
        Timber.d("Destroying video overlay for $videoId")

        players.remove(videoId)?.release()
        videoBounds.remove(videoId)
        videoVisibility.remove(videoId)
        videoNativeSizes.remove(videoId)

        surfaceViews.remove(videoId)?.let { surfaceView ->
            (surfaceView.parent as? ViewGroup)?.removeView(surfaceView)
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
     * Resolve a blob: URL to a real direct-play URL via the Jellyfin API,
     * similar to how [org.jellyfin.mobile.bridge.ExternalPlayer] works.
     *
     * When the web client chooses HLS transcoding (blob: URLs from hls.js/MSE),
     * we bypass that and ask the server for a static direct-play stream instead,
     * letting ExoPlayer handle the decoding natively.
     */
    private fun resolveAndSetVideoSource(videoId: String, itemId: String, mediaSourceId: String) {
        val itemUuid = itemId.toUUIDOrNull() ?: run {
            Timber.e("Invalid item ID for video proxy resolution: $itemId")
            callJavaScript("VideoProxyCallback.onError('$videoId', 4, 'Invalid item ID')")
            return
        }

        Timber.d("Resolving direct play URL for $videoId: itemId=$itemId, mediaSourceId=$mediaSourceId")

        // Use the external player profile which forces direct play (no transcoding)
        val profile = deviceProfileBuilder.getExternalPlayerProfile()
        val videosApi = apiClient.videosApi

        coroutineScope.launch(Dispatchers.IO) {
            mediaSourceResolver.resolveMediaSource(
                itemId = itemUuid,
                mediaSourceId = mediaSourceId.ifEmpty { null },
                deviceProfile = profile,
                maxStreamingBitrate = Int.MAX_VALUE, // Ensure direct play
                autoOpenLiveStream = false,
            ).onSuccess { jellyfinMediaSource ->
                val url = videosApi.getVideoStreamUrl(
                    itemId = jellyfinMediaSource.itemId,
                    static = true,
                    mediaSourceId = jellyfinMediaSource.id,
                    playSessionId = jellyfinMediaSource.playSessionId,
                )
                Timber.d("Resolved direct play URL for $videoId: $url")

                // Set the resolved URL as the video source (must run on main thread)
                launch(Dispatchers.Main) {
                    setVideoSource(videoId, url)
                }
            }.onFailure { error ->
                Timber.e(error, "Failed to resolve media source for $videoId (itemId=$itemId)")
                val escapedMessage = (error.message ?: "Unknown error").replace("'", "\\'")
                launch(Dispatchers.Main) {
                    callJavaScript("VideoProxyCallback.onError('$videoId', 4, 'Failed to resolve: $escapedMessage')")
                }
            }
        }
    }

    /**
     * Start playback.
     */
    private fun playVideo(videoId: String) {
        Timber.d("Playing video $videoId")
        players[videoId]?.play()

        // Show the surface view and subtitle view (already on main thread)
        surfaceViews[videoId]?.visibility = View.VISIBLE
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

        surfaceViews[videoId]?.let { applyBoundsToSurfaceView(it, bounds) }
        subtitleViews[videoId]?.let { applyBoundsToSubtitleView(it, bounds) }

        // Reapply aspect ratio after bounds change
        reapplyAspectRatio(videoId)
    }

    /**
     * Apply bounds to a SurfaceView, converting CSS pixels to device pixels.
     */
    private fun applyBoundsToSurfaceView(surfaceView: SurfaceView, bounds: VideoBounds) {
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
        
        val layoutParams = surfaceView.layoutParams as? FrameLayout.LayoutParams ?: return
        layoutParams.width = width
        layoutParams.height = height
        layoutParams.leftMargin = left
        layoutParams.topMargin = top
        surfaceView.layoutParams = layoutParams
        
        Timber.d("Updated bounds: left=$left, top=$top, width=$width, height=$height")
    }

    /**
     * Apply bounds to a SubtitleView, using the same coordinate conversion as SurfaceView
     * but relative to the subtitle container (which is above the WebView).
     */
    private fun applyBoundsToSubtitleView(subtitleView: SubtitleView, bounds: VideoBounds) {
        val webView = webView ?: return
        val subContainer = subtitleContainer ?: overlayContainer ?: return

        val webViewLocation = IntArray(2)
        webView.getLocationOnScreen(webViewLocation)

        val containerLocation = IntArray(2)
        subContainer.getLocationOnScreen(containerLocation)

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
        surfaceViews[videoId]?.visibility = viewVisibility
        subtitleViews[videoId]?.visibility = viewVisibility
    }

    /**
     * Set fullscreen mode.
     */
    private fun setVideoFullscreen(videoId: String, fullscreen: Boolean) {
        Timber.d("Setting fullscreen for $videoId: $fullscreen")

        val surfaceView = surfaceViews[videoId] ?: return
        val subtitleView = subtitleViews[videoId]

        if (fullscreen) {
            val fullscreenParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                leftMargin = 0
                topMargin = 0
            }
            surfaceView.layoutParams = fullscreenParams
            subtitleView?.layoutParams = FrameLayout.LayoutParams(fullscreenParams)
        } else {
            videoBounds[videoId]?.let { bounds ->
                applyBoundsToSurfaceView(surfaceView, bounds)
                subtitleView?.let { applyBoundsToSubtitleView(it, bounds) }
            }
        }

        reapplyAspectRatio(videoId)
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

    override fun onVideoSizeChanged(videoId: String, width: Int, height: Int, pixelWidthHeightRatio: Float) {
        Timber.d("Video size changed for $videoId: ${width}x${height} pixelRatio=$pixelWidthHeightRatio")
        val size = VideoNativeSize(width, height, pixelWidthHeightRatio)
        videoNativeSizes[videoId] = size

        // Apply aspect ratio via LayoutParams to the SurfaceView
        surfaceViews[videoId]?.let { surfaceView ->
            surfaceView.post {
                applyAspectRatio(surfaceView, size)
            }
        }

        // Notify JavaScript about video intrinsic dimensions
        callJavaScript("VideoProxyCallback.onVideoSizeChanged('$videoId', $width, $height, $pixelWidthHeightRatio)")
    }

    override fun onRenderedFirstFrame(videoId: String) {
        Timber.d("First frame rendered for $videoId")
        // Notify JavaScript so it can make the web content transparent now that
        // the native ExoPlayer has actual video content to show through.
        callJavaScript("VideoProxyCallback.onFirstFrameRendered('$videoId')")
    }

    /**
     * Apply aspect ratio correction to SurfaceView by adjusting its LayoutParams
     * to achieve FIT_CENTER behavior. SurfaceView doesn't support matrix transforms,
     * so we calculate the correct size and offset directly.
     */
    private fun applyAspectRatio(surfaceView: SurfaceView, size: VideoNativeSize) {
        val parent = surfaceView.parent as? FrameLayout ?: return
        val containerWidth = (surfaceView.layoutParams as? FrameLayout.LayoutParams)?.width
            ?.takeIf { it > 0 } ?: parent.width
        val containerHeight = (surfaceView.layoutParams as? FrameLayout.LayoutParams)?.height
            ?.takeIf { it > 0 } ?: parent.height

        if (containerWidth <= 0 || containerHeight <= 0 || size.width <= 0 || size.height <= 0) return

        val videoAspect = (size.width * size.pixelWidthHeightRatio) / size.height
        val containerAspect = containerWidth.toFloat() / containerHeight.toFloat()

        val params = surfaceView.layoutParams as? FrameLayout.LayoutParams ?: return
        val currentLeft = params.leftMargin
        val currentTop = params.topMargin

        if (videoAspect > containerAspect) {
            val fitHeight = (containerWidth / videoAspect).toInt()
            val yOffset = (containerHeight - fitHeight) / 2
            params.width = containerWidth
            params.height = fitHeight
            params.leftMargin = currentLeft
            params.topMargin = currentTop + yOffset
        } else {
            val fitWidth = (containerHeight * videoAspect).toInt()
            val xOffset = (containerWidth - fitWidth) / 2
            params.width = fitWidth
            params.height = containerHeight
            params.leftMargin = currentLeft + xOffset
            params.topMargin = currentTop
        }

        surfaceView.layoutParams = params
        Timber.d("Applied aspect ratio: video=${size.width}x${size.height} container=${containerWidth}x${containerHeight}")
    }

    /**
     * Reapply the aspect ratio for a video, if native size is known.
     * Should be called after any bounds/layout change.
     */
    private fun reapplyAspectRatio(videoId: String) {
        val surfaceView = surfaceViews[videoId] ?: return
        val size = videoNativeSizes[videoId] ?: return
        surfaceView.post { applyAspectRatio(surfaceView, size) }
    }

    /**
     * Apply subtitle style from app preferences: font, text size, background, offset.
     */
    private fun applyDefaultSubtitleStyle(subtitleView: SubtitleView) {
        subtitleView.setApplyEmbeddedStyles(false)
        subtitleView.setApplyEmbeddedFontSizes(false)

        val typeface = when (appPreferences.subtitleFont) {
            "sans_serif" -> android.graphics.Typeface.SANS_SERIF
            "serif" -> android.graphics.Typeface.SERIF
            "monospace" -> android.graphics.Typeface.MONOSPACE
            else -> null
        }

        val bgColor = when (appPreferences.subtitleBackground) {
            "semi" -> android.graphics.Color.argb(128, 0, 0, 0)
            "opaque" -> android.graphics.Color.BLACK
            else -> android.graphics.Color.TRANSPARENT
        }

        val style = CaptionStyleCompat(
            android.graphics.Color.WHITE,
            bgColor,
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            android.graphics.Color.BLACK,
            typeface,
        )
        subtitleView.setStyle(style)
        subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, appPreferences.subtitleTextSize.toFloat())

        val bottomPadding = (appPreferences.subtitleOffset * context.resources.displayMetrics.density).toInt()
        subtitleView.setPadding(0, 0, 0, bottomPadding)
    }

    override fun onStateChanged(videoId: String, state: VideoProxyPlayerState) {
        // Notify JavaScript about state change.
        // seeked=true tells JS to clear its _seeking guard and fire the 'seeked'
        // event without relying on a fixed timeout.
        val stateJson = """
            {
                "currentTime": ${state.currentTimeMs},
                "duration": ${state.durationMs},
                "paused": ${state.paused},
                "ended": ${state.ended},
                "readyState": ${state.readyState},
                "seeked": ${state.seeked}
            }
        """.trimIndent()
        
        callJavaScript("VideoProxyCallback.onStateChange('$videoId', $stateJson)")
    }

    override fun onBuffering(videoId: String, isBuffering: Boolean) {
        callJavaScript("VideoProxyCallback.onBuffering('$videoId', $isBuffering)")
        updateBitrateIndicator(videoId, isBuffering)
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
     * Set the bitrate indicator TextView (for buffering bitrate display in top-right corner).
     */
    fun setBitrateIndicatorView(textView: TextView) {
        bitrateIndicatorView = textView
    }

    /**
     * Update the bitrate indicator when buffering state changes.
     * Shows estimated network bandwidth and video format bitrate in the top-right corner.
     */
    private fun updateBitrateIndicator(videoId: String, isBuffering: Boolean) {
        val indicator = bitrateIndicatorView ?: return

        if (isBuffering) {
            bitrateIndicatorVisible = true
            val player = players[videoId]
            if (player != null) {
                val bandwidthBps = player.getNetworkBandwidthEstimate()
                val videoBitrateBps = player.getVideoFormatBitrate()
                val bandwidthStr = if (bandwidthBps > 0) formatBitrateDisplay(bandwidthBps) else "..."
                val videoBitrateStr = if (videoBitrateBps > 0) formatBitrateDisplay(videoBitrateBps) else "N/A"
                indicator.text = buildString {
                    append("⏳ ")
                    append(bandwidthStr)
                    if (videoBitrateBps > 0) {
                        append(" / ")
                        append(videoBitrateStr)
                    }
                }
            } else {
                indicator.text = "⏳ ..."
            }
            indicator.visibility = View.VISIBLE
        } else {
            bitrateIndicatorVisible = false
            indicator.visibility = View.GONE
        }
    }

    /**
     * Refresh the bitrate indicator with updated bandwidth estimate.
     * Called periodically from the progress update loop while buffering.
     */
    private fun refreshBitrateIndicator() {
        val indicator = bitrateIndicatorView ?: return
        // Find the actively buffering player
        val activePlayer = players.values.firstOrNull { player ->
            player.currentState.readyState == 2 // STATE_BUFFERING maps to readyState 2
        } ?: players.values.firstOrNull { it.currentState.readyState > 0 }
        ?: return

        val bandwidthBps = activePlayer.getNetworkBandwidthEstimate()
        val videoBitrateBps = activePlayer.getVideoFormatBitrate()
        val bandwidthStr = if (bandwidthBps > 0) formatBitrateDisplay(bandwidthBps) else "..."
        val videoBitrateStr = if (videoBitrateBps > 0) formatBitrateDisplay(videoBitrateBps) else "N/A"
        indicator.text = buildString {
            append("⏳ ")
            append(bandwidthStr)
            if (videoBitrateBps > 0) {
                append(" / ")
                append(videoBitrateStr)
            }
        }
    }

    /**
     * Format bitrate for display (bps -> human readable).
     */
    private fun formatBitrateDisplay(bps: Long): String {
        return when {
            bps >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f Mbps", bps / 1_000_000.0)
            bps >= 1_000 -> String.format(java.util.Locale.US, "%.0f kbps", bps / 1_000.0)
            else -> "$bps bps"
        }
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
                // Update debug info and bitrate indicator every ~500ms (every 2nd progress tick at 250ms interval)
                debugUpdateCounter++
                if (debugUpdateCounter >= 2) {
                    debugUpdateCounter = 0
                    if (debugInfoVisible) {
                        try {
                            updateDebugInfo()
                        } catch (e: Exception) {
                            Timber.e(e, "Error updating debug info")
                        }
                    }
                    // Refresh bitrate indicator while buffering to show live bandwidth estimate
                    if (bitrateIndicatorVisible) {
                        try {
                            refreshBitrateIndicator()
                        } catch (e: Exception) {
                            Timber.e(e, "Error refreshing bitrate indicator")
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
            appendLine("─── Subtitles ───")
            appendLine("Text track: ${if (info.textTrackEnabled) "enabled" else "disabled"}")
            if (info.subtitleTracks.isEmpty()) {
                appendLine("Tracks: (none)")
            } else {
                info.subtitleTracks.forEach { sub ->
                    val sel = if (sub.isSelected) "▸" else " "
                    val lang = sub.language.ifEmpty { "?" }
                    val label = sub.label.ifEmpty { "no label" }
                    appendLine("$sel [$lang] $label (${sub.codec})")
                }
            }
            appendLine("─── Network ───")
            appendLine("Bandwidth: ${info.networkBandwidth}")
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
            surfaceViews.values.forEach { surfaceView ->
                (surfaceView.parent as? ViewGroup)?.removeView(surfaceView)
            }
            surfaceViews.clear()
            subtitleViews.values.forEach { subtitleView ->
                (subtitleView.parent as? ViewGroup)?.removeView(subtitleView)
            }
            subtitleViews.clear()
        }

        videoBounds.clear()
        videoVisibility.clear()
        videoNativeSizes.clear()
        overlayContainer = null
        subtitleContainer = null
        webView = null
        debugInfoView = null
        debugContainerView = null
        debugInfoVisible = false
        bitrateIndicatorView = null
        bitrateIndicatorVisible = false
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
