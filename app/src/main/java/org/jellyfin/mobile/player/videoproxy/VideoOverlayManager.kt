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
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Manages video overlay views and their corresponding ExoPlayer instances.
 * This class handles the creation, positioning, and lifecycle of video overlays
 * that render on top of the WebView.
 */
@UnstableApi
@Suppress("LongParameterList")
class VideoOverlayManager(
    private val context: Context,
    private val appPreferences: AppPreferences,
    private val webappFunctionChannel: WebappFunctionChannel,
    private val coroutineScope: CoroutineScope,
    private val mediaSourceResolver: MediaSourceResolver,
    private val apiClient: ApiClient,
    private val deviceProfileBuilder: DeviceProfileBuilder,
    private val mediaSourceFactory: MediaSource.Factory,
) : VideoProxyPlayerCallback {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val resolutionJobs = mutableMapOf<String, Job>()
    private val fullscreenVideos = mutableSetOf<String>()
    private var eventJob: Job? = null
    private var released = false
    private var overlayContainer: FrameLayout? = null
    private var subtitleContainer: FrameLayout? = null
    private var webView: WebView? = null
    private val videoContainers = ConcurrentHashMap<String, FrameLayout>()
    private val layoutChangeListener = View.OnLayoutChangeListener {
        _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        val sizeChanged = right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop
        val positionChanged = left != oldLeft || top != oldTop
        if (sizeChanged || positionChanged) surfaceViews.keys.forEach { reapplyAspectRatio(it) }
    }

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

    // Native statistics are sent only while Jellyfin's playback info panel is open.
    private var playbackInfoVisible = false
    private var statsUpdateCounter = 0

    // Bitrate indicator (top-right corner, shown during buffering)
    private var bitrateIndicatorView: TextView? = null
    private var bitrateIndicatorContainer: View? = null
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
        container.addOnLayoutChangeListener(layoutChangeListener)
        webView.addOnLayoutChangeListener(layoutChangeListener)
        
        Timber.d("VideoOverlayManager initialized")
        
        // Start listening for events
        eventJob = coroutineScope.launch {
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
        if (released) return
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
            is VideoProxyEvent.SetPlaybackInfoVisible -> setPlaybackInfoVisible(event.visible)
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
        surfaceViews.remove(videoId)
        videoContainers.remove(videoId)?.let { oldContainer ->
            (oldContainer.parent as? ViewGroup)?.removeView(oldContainer)
        }
        subtitleViews.remove(videoId)?.let { oldSubtitle ->
            (oldSubtitle.parent as? ViewGroup)?.removeView(oldSubtitle)
        }

        // Create player
        val player = VideoProxyPlayer(
            context = context,
            videoId = videoId,
            preferHardwareDecoding = appPreferences.videoProxyHardwareDecoding,
            callback = this,
            mediaSourceFactory = mediaSourceFactory,
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
        
        // Crop cover/zoom to this video's box, including for inline videos.
        val videoContainer = createVideoContainer(surfaceView)
        container.addView(videoContainer)
        videoContainers[videoId] = videoContainer
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

        resolutionJobs.remove(videoId)?.cancel()
        fullscreenVideos.remove(videoId)
        players.remove(videoId)?.release()
        videoBounds.remove(videoId)
        videoVisibility.remove(videoId)
        videoNativeSizes.remove(videoId)

        surfaceViews.remove(videoId)
        videoContainers.remove(videoId)?.let { videoContainer ->
            (videoContainer.parent as? ViewGroup)?.removeView(videoContainer)
        }
        subtitleViews.remove(videoId)?.let { subtitleView ->
            (subtitleView.parent as? ViewGroup)?.removeView(subtitleView)
        }

        if (playbackInfoVisible) updatePlaybackInfo()
    }

    /**
     * Set the video source URL.
     */
    private fun setVideoSource(videoId: String, src: String) {
        Timber.d("Setting video source for $videoId: $src")
        resolutionJobs.remove(videoId)?.cancel()
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
        resolutionJobs.remove(videoId)?.cancel()
        val player = players[videoId] ?: return
        val itemUuid = itemId.toUUIDOrNull() ?: run {
            callJavaScript("onError", videoId, MEDIA_ERR_SRC_NOT_SUPPORTED, "Invalid item ID")
            return
        }
        val profile = deviceProfileBuilder.getExternalPlayerProfile()
        resolutionJobs[videoId] = coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                mediaSourceResolver.resolveMediaSource(
                    itemId = itemUuid,
                    mediaSourceId = mediaSourceId.ifEmpty { null },
                    deviceProfile = profile,
                    maxStreamingBitrate = Int.MAX_VALUE,
                    autoOpenLiveStream = false,
                )
            }
            // A newer source, destroyed element or released view invalidates this result.
            if (!isActive || released || players[videoId] !== player) return@launch
            result.onSuccess { source ->
                val url = apiClient.videosApi.getVideoStreamUrl(
                    itemId = source.itemId,
                    static = true,
                    mediaSourceId = source.id,
                    playSessionId = source.playSessionId,
                )
                player.setSource(url)
            }.onFailure { error ->
                Timber.e(error, "Failed to resolve media source for $videoId")
                callJavaScript(
                    "onError",
                    videoId,
                    MEDIA_ERR_SRC_NOT_SUPPORTED,
                    "Failed to resolve: " + (error.message ?: "Unknown error"),
                )
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
        videoContainers[videoId]?.visibility = View.VISIBLE
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
        reapplyAspectRatio(videoId)
    }

    /**
     * Convert CSS pixels using the actual WebView viewport, rather than assuming
     * its page scale always matches the device density.
     */
    private fun applyBoundsToView(view: View, bounds: VideoBounds, container: FrameLayout) {
        val webView = webView ?: return
        val webViewLocation = IntArray(2)
        webView.getLocationOnScreen(webViewLocation)
        val containerLocation = IntArray(2)
        container.getLocationOnScreen(containerLocation)
        val scale = if (bounds.viewportWidth > 0 && webView.width > 0) {
            webView.width / bounds.viewportWidth
        } else {
            context.resources.displayMetrics.density
        }
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        params.width = (bounds.width * scale).roundToInt()
        params.height = (bounds.height * scale).roundToInt()
        params.leftMargin = (bounds.x * scale).roundToInt() + webViewLocation[0] - containerLocation[0]
        params.topMargin = (bounds.y * scale).roundToInt() + webViewLocation[1] - containerLocation[1]
        view.layoutParams = params
    }

    private fun createVideoContainer(surfaceView: SurfaceView) = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        clipChildren = true
        clipToPadding = true
        visibility = View.GONE
        addView(surfaceView)
    }

    private fun applyBoundsToSurfaceView(surfaceView: SurfaceView, bounds: VideoBounds) {
        val videoContainer = surfaceView.parent as? FrameLayout ?: return
        val container = overlayContainer ?: return
        applyBoundsToView(videoContainer, bounds, container)
    }

    private fun applyBoundsToSubtitleView(subtitleView: SubtitleView, bounds: VideoBounds) {
        val container = subtitleContainer ?: overlayContainer ?: return
        applyBoundsToView(subtitleView, bounds, container)
    }

    /**
     * Set video visibility.
     */
    private fun setVideoVisibility(videoId: String, visible: Boolean) {
        Timber.d("Setting visibility for $videoId: $visible")
        videoVisibility[videoId] = visible
        val viewVisibility = if (visible) View.VISIBLE else View.GONE
        videoContainers[videoId]?.visibility = viewVisibility
        surfaceViews[videoId]?.visibility = viewVisibility
        subtitleViews[videoId]?.visibility = viewVisibility
    }

    /**
     * Set fullscreen mode.
     */
    private fun setVideoFullscreen(videoId: String, fullscreen: Boolean) {
        if (fullscreen) fullscreenVideos.add(videoId) else fullscreenVideos.remove(videoId)
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

        reapplyAspectRatio(videoId)
        callJavaScript("onVideoSizeChanged", videoId, width, height, pixelWidthHeightRatio)
    }

    override fun onRenderedFirstFrame(videoId: String) {
        Timber.d("First frame rendered for $videoId")
        // Notify JavaScript so it can make the web content transparent now that
        // the native ExoPlayer has actual video content to show through.
        callJavaScript("onFirstFrameRendered", videoId)
    }

    private fun resolveContainerDimension(dimension: Int, parentDimension: Int) =
        if (dimension == FrameLayout.LayoutParams.MATCH_PARENT) parentDimension else dimension

    /**
     * Match the web player's fit mode. Cover keeps the aspect ratio and crops
     * inside the video container; fill stretches only when requested by the web UI.
     */
    private fun applyAspectRatio(surfaceView: SurfaceView, size: VideoNativeSize, objectFit: String) {
        val parent = surfaceView.parent as? FrameLayout ?: return
        val parentParams = parent.layoutParams as? FrameLayout.LayoutParams ?: return
        val container = overlayContainer ?: return
        val containerWidth = resolveContainerDimension(parentParams.width, container.width)
        val containerHeight = resolveContainerDimension(parentParams.height, container.height)
        if (containerWidth <= 0 || containerHeight <= 0) return
        if (size.width <= 0 || size.height <= 0) return

        val videoWidth = size.width * size.pixelWidthHeightRatio
        if (videoWidth <= 0 || !videoWidth.isFinite()) return
        val widthScale = containerWidth / videoWidth
        val heightScale = containerHeight.toFloat() / size.height
        val scale = when (objectFit) {
            "cover" -> max(widthScale, heightScale)
            else -> min(widthScale, heightScale)
        }
        val params = if (objectFit == "fill") {
            FrameLayout.LayoutParams(containerWidth, containerHeight)
        } else {
            FrameLayout.LayoutParams((videoWidth * scale).roundToInt(), (size.height * scale).roundToInt())
        }
        surfaceView.layoutParams = params.apply {
            leftMargin = (containerWidth - width) / 2
            topMargin = (containerHeight - height) / 2
        }
    }

    /**
     * Refit against the original video box after bounds, fit mode or window size changes.
     */
    private fun reapplyAspectRatio(videoId: String) {
        val surfaceView = surfaceViews[videoId] ?: return
        surfaceView.post {
            if (released || surfaceViews[videoId] !== surfaceView) return@post
            val videoContainer = videoContainers[videoId] ?: return@post
            val subtitleView = subtitleViews[videoId]
            val bounds = videoBounds[videoId]
            if (videoId in fullscreenVideos) {
                val params = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                videoContainer.layoutParams = params
                subtitleView?.layoutParams = FrameLayout.LayoutParams(params)
            } else {
                bounds ?: return@post
                applyBoundsToSurfaceView(surfaceView, bounds)
                subtitleView?.let { applyBoundsToSubtitleView(it, bounds) }
            }
            val size = videoNativeSizes[videoId] ?: return@post
            applyAspectRatio(surfaceView, size, bounds?.objectFit ?: "contain")
        }
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
        val json = JSONObject().apply {
            put("currentTime", state.currentTimeMs)
            put("duration", state.durationMs)
            put("buffered", state.bufferedPositionMs)
            put("paused", state.paused)
            put("ended", state.ended)
            put("readyState", state.readyState)
            put("seekable", state.seekable)
            put("seeked", state.seeked)
        }
        callJavaScript("onStateChange", videoId, json)
    }

    override fun onBuffering(videoId: String, isBuffering: Boolean) {
        callJavaScript("onBuffering", videoId, isBuffering)
        updateBitrateIndicator(videoId, isBuffering)
    }

    override fun onError(videoId: String, errorCode: Int, errorMessage: String) {
        callJavaScript("onError", videoId, errorCode, errorMessage)
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

        callJavaScript("onTracksChanged", videoId, tracksJson)
    }

    private fun callJavaScript(method: String, vararg arguments: Any?) {
        if (released) return
        val jsonArguments = JSONArray(arguments.toList())
        webappFunctionChannel.call("window.VideoProxyCallback?.$method(...$jsonArguments)")
    }

    /**
     * Set the bitrate indicator TextView (for buffering bitrate display in top-right corner).
     */
    fun setBitrateIndicatorView(textView: TextView, container: View) {
        bitrateIndicatorView = textView
        bitrateIndicatorContainer = container
    }

    /**
     * Update the bitrate indicator when buffering state changes.
     * Shows estimated network bandwidth and video format bitrate in the top-right corner.
     */
    private fun updateBitrateIndicator(videoId: String, isBuffering: Boolean) {
        val indicator = bitrateIndicatorView ?: return
        val container = bitrateIndicatorContainer ?: return

        if (isBuffering) {
            bitrateIndicatorVisible = true
            val player = players[videoId]
            if (player != null) {
                val bandwidthBps = player.getNetworkBandwidthEstimate()
                val videoBitrateBps = player.getVideoFormatBitrate()
                val bandwidthStr = if (bandwidthBps > 0) formatBitrateDisplay(bandwidthBps) else "..."
                val videoBitrateStr = if (videoBitrateBps > 0) formatBitrateDisplay(videoBitrateBps) else "N/A"
                indicator.text = buildString {
                    append(bandwidthStr)
                    if (videoBitrateBps > 0) {
                        append(" / ")
                        append(videoBitrateStr)
                    }
                }
            } else {
                indicator.text = "..."
            }
            container.visibility = View.VISIBLE
        } else {
            bitrateIndicatorVisible = false
            container.visibility = View.GONE
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
     * Also handles playback info updates when visible (every ~500ms via counter).
     */
    private fun startProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = coroutineScope.launch(Dispatchers.Main) {
            while (true) {
                players.values.forEach { player ->
                    player.updateProgress()
                }
                // Update playback info and bitrate indicator every ~500ms (every 2nd progress tick at 250ms interval)
                statsUpdateCounter++
                if (statsUpdateCounter >= 2) {
                    statsUpdateCounter = 0
                    if (playbackInfoVisible) {
                        try {
                            updatePlaybackInfo()
                        } catch (e: Exception) {
                            Timber.e(e, "Error updating playback info")
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

    private fun setPlaybackInfoVisible(visible: Boolean) {
        playbackInfoVisible = visible
        if (visible) updatePlaybackInfo()
    }

    private fun updatePlaybackInfo() {
        val view = webView ?: return
        val insets = ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.displayCutout()) ?: Insets.NONE
        val payload = JSONObject().apply {
            put("players", JSONArray(players.values.map { it.getDebugInfo().toPlaybackInfoJson() }))
            put("viewportWidth", view.width)
            put("safeArea", JSONObject().apply {
                put("left", insets.left)
                put("right", insets.right)
            })
        }
        callJavaScript("onPlaybackInfo", payload)
    }

    /**
     * Clean up all resources.
     */
    fun release() {
        released = true
        eventJob?.cancel()
        resolutionJobs.values.forEach { it.cancel() }
        resolutionJobs.clear()
        fullscreenVideos.clear()
        overlayContainer?.removeOnLayoutChangeListener(layoutChangeListener)
        webView?.removeOnLayoutChangeListener(layoutChangeListener)
        Timber.d("Releasing VideoOverlayManager")
        
        progressUpdateJob?.cancel()
        eventChannel.close()
        
        // Release all players
        players.values.forEach { it.release() }
        players.clear()
        
        // Remove all overlay views
        mainHandler.post {
            videoContainers.values.forEach { videoContainer ->
                (videoContainer.parent as? ViewGroup)?.removeView(videoContainer)
            }
            videoContainers.clear()
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
        playbackInfoVisible = false
        bitrateIndicatorContainer?.visibility = View.GONE
        bitrateIndicatorView = null
        bitrateIndicatorContainer = null
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

    private companion object {
        const val MEDIA_ERR_SRC_NOT_SUPPORTED = 4
    }
}
