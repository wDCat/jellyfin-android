package org.jellyfin.mobile.player.videoproxy

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.SubtitleView
import org.jellyfin.mobile.utils.applyDefaultAudioAttributes
import timber.log.Timber
import java.util.Locale

/**
 * Callback interface for video proxy player events.
 */
interface VideoProxyPlayerCallback {
    fun onStateChanged(videoId: String, state: VideoProxyPlayerState)
    fun onBuffering(videoId: String, isBuffering: Boolean)
    fun onError(videoId: String, errorCode: Int, errorMessage: String)
    fun onTracksChanged(videoId: String, audioTracks: List<ProxyTrackInfo>, subtitleTracks: List<ProxyTrackInfo>)
    fun onVideoSizeChanged(videoId: String, width: Int, height: Int, pixelWidthHeightRatio: Float)
    fun onRenderedFirstFrame(videoId: String)
}

/**
 * Represents the current state of the video proxy player.
 */
data class VideoProxyPlayerState(
    val currentTimeMs: Long = 0,
    val durationMs: Long = 0,
    val paused: Boolean = true,
    val ended: Boolean = false,
    val readyState: Int = 0,
    /**
     * True in the exact state update that signals seek completion.
     * JS uses this to clear its _seeking guard and fire the 'seeked' event
     * without relying on a fixed timeout or readyState transitions.
     */
    val seeked: Boolean = false,
)

/**
 * Track information exposed to JavaScript for audio/subtitle track switching.
 */
data class ProxyTrackInfo(
    val index: Int,
    val language: String?,
    val label: String?,
    val codec: String?,
    val channelCount: Int,
    val isDefault: Boolean,
    val isForced: Boolean,
    val isSelected: Boolean,
)

/**
 * Debug information collected from ExoPlayer.
 */
data class VideoProxyDebugInfo(
    val videoId: String = "",
    val hasExoPlayer: Boolean = false,
    val isProxying: Boolean = false,
    val sourceUrl: String = "",
    val playbackState: String = "NO_PLAYER",
    val videoDecoderName: String = "N/A",
    val audioDecoderName: String = "N/A",
    val videoFormat: String = "N/A",
    val audioFormat: String = "N/A",
    val resolution: String = "N/A",
    val frameRate: String = "N/A",
    val videoBitrate: String = "N/A",
    val audioBitrate: String = "N/A",
    val droppedFrames: Long = 0,
    val renderedFrames: Long = 0,
    val bufferPosition: Long = 0,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val playbackSpeed: Float = 1f,
    val volume: Float = 1f,
    val isHardwareDecoding: Boolean = false,
    val surfaceSize: String = "N/A",
    val networkBandwidth: String = "N/A",
    val networkBandwidthBps: Long = 0,
)

/**
 * A lightweight ExoPlayer wrapper for video element proxy playback.
 * This player renders video content to overlay the HTML video element.
 */
@UnstableApi
class VideoProxyPlayer(
    private val context: Context,
    private val videoId: String,
    private val preferHardwareDecoding: Boolean,
    private val callback: VideoProxyPlayerCallback,
) : Player.Listener {

    companion object {
        /**
         * Number of updateProgress() cycles to skip after seek completion (~750ms at 250ms interval).
         * Gives ExoPlayer's internal position tracker time to stabilize before we
         * poll currentPosition, preventing transient 0-position reports.
         */
        private const val POST_SEEK_SKIP_CYCLES = 3
    }

    private var player: ExoPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var subtitleView: SubtitleView? = null
    private var isReleased = false

    private var _currentState = VideoProxyPlayerState()
    val currentState: VideoProxyPlayerState get() = _currentState

    // Cached track groups for selection by index
    private var audioTrackGroups: List<Tracks.Group> = emptyList()
    private var subtitleTrackGroups: List<Tracks.Group> = emptyList()

    // Seeking state: suppresses stale position updates from updateProgress()
    // between the JS bridge.seek() call and ExoPlayer completing the seek.
    private var isSeeking = false

    // After seek completes, ExoPlayer.currentPosition may transiently return 0
    // (or a stale value) before the internal position tracker fully syncs.
    // Skip a few updateProgress() cycles to let it stabilize.
    private var postSeekSkipCount = 0

    // Debug info tracking
    private var videoDecoderName: String = "N/A"
    private var audioDecoderName: String = "N/A"
    private var droppedFrameCount: Long = 0
    private var currentSourceUrl: String = ""

    /**
     * Initialize the ExoPlayer instance.
     */
    fun initialize() {
        if (player != null || isReleased) return

        Timber.d("Initializing VideoProxyPlayer for $videoId")

        val renderersFactory = DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val decoderInfoList = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder,
                )
                // Apply hardware/software preference only for video track
                if (!MimeTypes.isVideo(mimeType)) {
                    return@setMediaCodecSelector decoderInfoList
                }
                if (preferHardwareDecoding) {
                    // Prefer hardware decoders, but allow fallback
                    val hwDecoders = decoderInfoList.filter(MediaCodecInfo::hardwareAccelerated)
                    if (hwDecoders.isNotEmpty()) hwDecoders else decoderInfoList
                } else {
                    // Prefer software decoders
                    val swDecoders = decoderInfoList.filterNot(MediaCodecInfo::hardwareAccelerated)
                    if (swDecoders.isNotEmpty()) swDecoders else decoderInfoList
                }
            }
        }

        player = ExoPlayer.Builder(context, renderersFactory).apply {
            setUsePlatformDiagnostics(false)
        }.build().apply {
            addListener(this@VideoProxyPlayer)
            applyDefaultAudioAttributes(C.AUDIO_CONTENT_TYPE_MOVIE)

            // Disable text track rendering — subtitles are handled entirely
            // by the Jellyfin web client's DOM-based renderer. ExoPlayer still
            // reports available text tracks (via onTracksChanged) so the web
            // client knows which subtitle streams exist.
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()

            // Add analytics listener for debug info collection
            addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    videoDecoderName = decoderName
                }

                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    audioDecoderName = decoderName
                }

                override fun onDroppedVideoFrames(
                    eventTime: AnalyticsListener.EventTime,
                    droppedFrames: Int,
                    elapsedMs: Long,
                ) {
                    droppedFrameCount += droppedFrames
                }

                override fun onVideoDecoderReleased(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                ) {
                    videoDecoderName = "N/A"
                }

                override fun onAudioDecoderReleased(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                ) {
                    audioDecoderName = "N/A"
                }
            })
        }

        surfaceView?.let { player?.setVideoSurfaceView(it) }
    }

    /**
     * Set the surface view for video rendering.
     * SurfaceView is used instead of TextureView to support HDR output.
     */
    fun setSurfaceView(surface: SurfaceView?) {
        surfaceView = surface
        player?.setVideoSurfaceView(surface)
    }

    /**
     * Set the subtitle view for rendering natively-handled subtitle tracks
     * (e.g., SubRip). Only used when the JS proxy routes a track to ExoPlayer.
     */
    fun setSubtitleView(view: SubtitleView?) {
        subtitleView = view
    }

    /**
     * Load and prepare a video source.
     */
    fun setSource(url: String) {
        val player = player ?: return
        
        Timber.d("Setting source for $videoId: $url")
        currentSourceUrl = url
        isSeeking = false
        postSeekSkipCount = 0
        
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        
        _currentState = _currentState.copy(
            currentTimeMs = 0,
            durationMs = 0,
            paused = true,
            ended = false,
            readyState = 0,
            seeked = false,
        )
    }

    /**
     * Start playback.
     */
    fun play() {
        player?.playWhenReady = true
        _currentState = _currentState.copy(paused = false, ended = false)
        notifyStateChanged()
    }

    /**
     * Pause playback.
     */
    fun pause() {
        player?.playWhenReady = false
        _currentState = _currentState.copy(paused = true)
        notifyStateChanged()
    }

    /**
     * Seek to a position.
     */
    fun seekTo(positionMs: Long) {
        isSeeking = true
        postSeekSkipCount = 0
        // Store seek target in currentTimeMs so JS shows the target position
        // immediately. seeked=false until ExoPlayer confirms completion.
        _currentState = _currentState.copy(currentTimeMs = positionMs, seeked = false)
        notifyStateChanged()
        player?.seekTo(positionMs)
    }

    /**
     * Set the volume level (0.0 to 1.0).
     */
    fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    /**
     * Set the playback rate.
     */
    fun setPlaybackRate(rate: Float) {
        player?.setPlaybackSpeed(rate.coerceIn(0.25f, 2f))
    }

    /**
     * Get the current playback position in milliseconds.
     */
    fun getCurrentPosition(): Long {
        return player?.currentPosition ?: 0L
    }

    /**
     * Get the duration in milliseconds.
     */
    fun getDuration(): Long {
        return player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L
    }

    /**
     * Select an audio track by its index among audio track groups.
     */
    fun setAudioTrack(groupIndex: Int) {
        val player = player ?: return
        val targetGroup = audioTrackGroups.getOrNull(groupIndex) ?: run {
            Timber.w("Audio track group index $groupIndex out of range (${audioTrackGroups.size} groups)")
            return
        }

        Timber.d("Selecting audio track $groupIndex for $videoId")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(targetGroup.mediaTrackGroup, listOf(0)))
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
    }

    /**
     * Select a subtitle track by its index among text track groups.
     * Called by the JS proxy when a natively-renderable track (e.g., SubRip)
     * is selected. Enables text tracks and overrides the selection.
     */
    fun setSubtitleTrack(groupIndex: Int) {
        val player = player ?: return
        val targetGroup = subtitleTrackGroups.getOrNull(groupIndex) ?: run {
            Timber.w("Subtitle track group index $groupIndex out of range (${subtitleTrackGroups.size} groups)")
            return
        }

        Timber.d("Selecting subtitle track $groupIndex for $videoId (native rendering)")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .addOverride(TrackSelectionOverride(targetGroup.mediaTrackGroup, listOf(0)))
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
    }

    /**
     * Disable all subtitle tracks in ExoPlayer.
     * Called when the user selects a non-native subtitle track (handled by
     * the web client) or disables subtitles entirely.
     */
    fun disableSubtitles() {
        val player = player ?: return

        Timber.d("Disabling ExoPlayer subtitles for $videoId")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        // Clear subtitle view
        subtitleView?.setCues(emptyList())
    }

    /**
     * Get current debug information from the ExoPlayer instance.
     */
    fun getDebugInfo(): VideoProxyDebugInfo {
        val player = player
        if (player == null) {
            return VideoProxyDebugInfo(
                videoId = videoId,
                hasExoPlayer = false,
                isProxying = currentSourceUrl.isNotEmpty(),
                sourceUrl = currentSourceUrl,
                playbackState = "NO_PLAYER (null)",
            )
        }

        val videoFormat = player.videoFormat
        val audioFormat = player.audioFormat

        val playbackStateStr = when (player.playbackState) {
            Player.STATE_IDLE -> if (currentSourceUrl.isEmpty()) "IDLE (no source)" else "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> if (player.isPlaying) "PLAYING" else "PAUSED"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN(${player.playbackState})"
        }

        val resolutionStr = videoFormat?.let { "${it.width}x${it.height}" } ?: "N/A"
        val frameRateStr = videoFormat?.frameRate?.let {
            if (it > 0) String.format(Locale.US, "%.2f fps", it) else "N/A"
        } ?: "N/A"

        val videoBitrateStr = videoFormat?.bitrate?.takeIf { it > 0 }?.let {
            formatBitrate(it.toLong())
        } ?: "N/A"
        val audioBitrateStr = audioFormat?.bitrate?.takeIf { it > 0 }?.let {
            formatBitrate(it.toLong())
        } ?: "N/A"

        val videoFormatStr = videoFormat?.let {
            buildString {
                append(it.sampleMimeType ?: "unknown")
                it.codecs?.let { codecs -> append(" ($codecs)") }
            }
        } ?: "N/A"

        val audioFormatStr = audioFormat?.let {
            buildString {
                append(it.sampleMimeType ?: "unknown")
                it.channelCount.takeIf { ch -> ch > 0 }?.let { ch -> append(" ${ch}ch") }
                it.sampleRate.takeIf { sr -> sr > 0 }?.let { sr -> append(" ${sr}Hz") }
            }
        } ?: "N/A"

        val renderedFrames = try {
            player.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        }

        val surfaceSizeStr = surfaceView?.let { "${it.width}x${it.height}" } ?: "N/A"

        val bandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(context)
        val bandwidthEstimateBps = bandwidthMeter.bitrateEstimate
        val bandwidthStr = if (bandwidthEstimateBps > 0) {
            formatBitrate(bandwidthEstimateBps)
        } else {
            "N/A"
        }

        val isHw = videoDecoderName.contains("c2.", ignoreCase = true) ||
            videoDecoderName.contains("OMX.", ignoreCase = true) ||
            (!videoDecoderName.contains("ffmpeg", ignoreCase = true) &&
                !videoDecoderName.contains("libvpx", ignoreCase = true) &&
                !videoDecoderName.contains("libdav1d", ignoreCase = true) &&
                videoDecoderName != "N/A")

        return VideoProxyDebugInfo(
            videoId = videoId,
            hasExoPlayer = true,
            isProxying = currentSourceUrl.isNotEmpty(),
            sourceUrl = currentSourceUrl,
            playbackState = playbackStateStr,
            videoDecoderName = videoDecoderName,
            audioDecoderName = audioDecoderName,
            videoFormat = videoFormatStr,
            audioFormat = audioFormatStr,
            resolution = resolutionStr,
            frameRate = frameRateStr,
            videoBitrate = videoBitrateStr,
            audioBitrate = audioBitrateStr,
            droppedFrames = droppedFrameCount,
            renderedFrames = renderedFrames,
            bufferPosition = player.bufferedPosition,
            currentPosition = player.currentPosition,
            duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            playbackSpeed = player.playbackParameters.speed,
            volume = player.volume,
            isHardwareDecoding = isHw,
            surfaceSize = surfaceSizeStr,
            networkBandwidth = bandwidthStr,
            networkBandwidthBps = bandwidthEstimateBps,
        )
    }

    /**
     * Get the current network bandwidth estimate in bits per second.
     * Uses ExoPlayer's default bandwidth meter.
     */
    fun getNetworkBandwidthEstimate(): Long {
        return DefaultBandwidthMeter.getSingletonInstance(context).bitrateEstimate
    }

    /**
     * Get the current video format bitrate in bits per second, or 0 if unavailable.
     */
    fun getVideoFormatBitrate(): Long {
        return player?.videoFormat?.bitrate?.toLong()?.takeIf { it > 0 } ?: 0L
    }

    private fun formatBitrate(bps: Long): String {
        return when {
            bps >= 1_000_000 -> String.format(Locale.US, "%.2f Mbps", bps / 1_000_000.0)
            bps >= 1_000 -> String.format(Locale.US, "%.0f kbps", bps / 1_000.0)
            else -> "$bps bps"
        }
    }

    /**
     * Release the player resources.
     */
    fun release() {
        if (isReleased) return
        
        Timber.d("Releasing VideoProxyPlayer for $videoId")
        
        isReleased = true
        player?.run {
            removeListener(this@VideoProxyPlayer)
            release()
        }
        player = null
        surfaceView = null
        subtitleView = null
        audioTrackGroups = emptyList()
        subtitleTrackGroups = emptyList()
    }

    // Player.Listener implementation

    override fun onPlaybackStateChanged(playbackState: Int) {
        Timber.d("Playback state changed for $videoId: $playbackState")
        
        val readyState = when (playbackState) {
            Player.STATE_IDLE -> 0
            Player.STATE_BUFFERING -> 2
            Player.STATE_READY -> 4
            Player.STATE_ENDED -> 4
            else -> 0
        }

        val isBuffering = playbackState == Player.STATE_BUFFERING
        callback.onBuffering(videoId, isBuffering)

        if (playbackState == Player.STATE_ENDED) {
            _currentState = _currentState.copy(
                ended = true,
                paused = true,
                readyState = readyState,
            )
        } else {
            _currentState = _currentState.copy(readyState = readyState)
        }

        if (playbackState == Player.STATE_READY) {
            if (isSeeking) {
                isSeeking = false
                // Skip a few updateProgress() cycles so ExoPlayer's internal position
                // tracker has time to stabilize. player.currentPosition may transiently
                // return 0 right after STATE_READY, causing the progress bar to flash.
                postSeekSkipCount = POST_SEEK_SKIP_CYCLES
                _currentState = _currentState.copy(seeked = true, durationMs = getDuration())
            } else {
                _currentState = _currentState.copy(durationMs = getDuration())
            }
        }

        notifyStateChanged()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        Timber.d("Is playing changed for $videoId: $isPlaying")
        _currentState = _currentState.copy(paused = !isPlaying)
        notifyStateChanged()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK && isSeeking) {
            // For in-buffer seeks the player stays in STATE_READY and
            // onPlaybackStateChanged(STATE_READY) will NOT re-fire, so isSeeking
            // would never be cleared via that path. Detect this case here: if the
            // player is already STATE_READY when the seek discontinuity fires, the
            // seek was served entirely from the buffer — clear isSeeking and signal
            // JS immediately.
            if (player?.playbackState == Player.STATE_READY) {
                isSeeking = false
                postSeekSkipCount = POST_SEEK_SKIP_CYCLES
                _currentState = _currentState.copy(
                    currentTimeMs = newPosition.positionMs,
                    seeked = true,
                )
                notifyStateChanged()
                return
            }
        }
        // For SEEK discontinuities that go through buffering, or for any other
        // discontinuity reason: only update position if we're still actively seeking
        // (the position should be the seek target). Post-seek discontinuities (e.g.,
        // INTERNAL adjustments) are silently absorbed during the grace period to
        // avoid transient zero positions reaching JS.
        if (isSeeking) {
            _currentState = _currentState.copy(
                currentTimeMs = newPosition.positionMs,
                seeked = false,
            )
            notifyStateChanged()
        } else if (postSeekSkipCount <= 0) {
            _currentState = _currentState.copy(
                currentTimeMs = newPosition.positionMs,
                seeked = false,
            )
            notifyStateChanged()
        } else {
            Timber.d("Suppressed post-seek position discontinuity: ${newPosition.positionMs} ms (grace period)")
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        Timber.e(error, "Player error for $videoId")
        val errorCode = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> 2 // MEDIA_ERR_NETWORK
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> 4 // MEDIA_ERR_SRC_NOT_SUPPORTED
            else -> 3 // MEDIA_ERR_DECODE
        }
        callback.onError(videoId, errorCode, error.message ?: "Unknown error")
    }

    override fun onTracksChanged(tracks: Tracks) {
        Timber.d("Tracks changed for $videoId: ${tracks.groups.size} groups")

        audioTrackGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        subtitleTrackGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

        val audioTracks = audioTrackGroups.mapIndexed { index, group ->
            buildTrackInfo(index, group)
        }
        val subtitleTracks = subtitleTrackGroups.mapIndexed { index, group ->
            buildTrackInfo(index, group)
        }

        Timber.d("Audio tracks: ${audioTracks.size}, Subtitle tracks: ${subtitleTracks.size}")
        callback.onTracksChanged(videoId, audioTracks, subtitleTracks)
    }

    @Suppress("DEPRECATION")
    override fun onCues(cueGroup: CueGroup) {
        // Forward cues to SubtitleView for natively-rendered tracks (e.g., SubRip).
        // When ExoPlayer's text tracks are disabled (for non-native codecs),
        // this callback is not called, so no action is needed.
        subtitleView?.setCues(cueGroup.cues)
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        Timber.d("Video size changed for $videoId: ${videoSize.width}x${videoSize.height} pixelRatio=${videoSize.pixelWidthHeightRatio}")
        callback.onVideoSizeChanged(videoId, videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
    }

    override fun onRenderedFirstFrame() {
        Timber.d("Rendered first frame for $videoId")
        callback.onRenderedFirstFrame(videoId)
    }

    /**
     * Build a [ProxyTrackInfo] from a [Tracks.Group].
     * Uses the first format in the group as representative.
     */
    private fun buildTrackInfo(index: Int, group: Tracks.Group): ProxyTrackInfo {
        val format: Format = group.getTrackFormat(0)
        return ProxyTrackInfo(
            index = index,
            language = format.language,
            label = format.label,
            codec = format.sampleMimeType,
            channelCount = format.channelCount,
            isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
            isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
            isSelected = group.isTrackSelected(0),
        )
    }

    /**
     * Update current time and notify state changed.
     * This should be called periodically during playback.
     * Skipped while a seek is in progress to avoid sending stale positions.
     */
    fun updateProgress() {
        val player = player ?: return
        if (isSeeking) return
        if (postSeekSkipCount > 0) {
            postSeekSkipCount--
            return
        }
        if (player.playbackState == Player.STATE_READY && player.isPlaying) {
            _currentState = _currentState.copy(
                currentTimeMs = player.currentPosition,
                seeked = false,
            )
            notifyStateChanged()
        }
    }

    private fun notifyStateChanged() {
        callback.onStateChanged(videoId, _currentState)
    }
}
