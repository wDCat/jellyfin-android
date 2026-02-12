package org.jellyfin.mobile.player.videoproxy

import android.content.Context
import android.net.Uri
import android.view.TextureView
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.text.CueGroup
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride
import com.google.android.exoplayer2.ui.SubtitleView
import com.google.android.exoplayer2.util.MimeTypes
import org.jellyfin.mobile.utils.applyDefaultAudioAttributes
import timber.log.Timber

/**
 * Callback interface for video proxy player events.
 */
interface VideoProxyPlayerCallback {
    fun onStateChanged(videoId: String, state: VideoProxyPlayerState)
    fun onBuffering(videoId: String, isBuffering: Boolean)
    fun onError(videoId: String, errorCode: Int, errorMessage: String)
    fun onTracksChanged(videoId: String, audioTracks: List<ProxyTrackInfo>, subtitleTracks: List<ProxyTrackInfo>)
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
 * A lightweight ExoPlayer wrapper for video element proxy playback.
 * This player renders video content to overlay the HTML video element.
 */
class VideoProxyPlayer(
    private val context: Context,
    private val videoId: String,
    private val preferHardwareDecoding: Boolean,
    private val callback: VideoProxyPlayerCallback,
) : Player.Listener {

    private var player: ExoPlayer? = null
    private var textureView: TextureView? = null
    private var subtitleView: SubtitleView? = null
    private var isReleased = false

    private var _currentState = VideoProxyPlayerState()
    val currentState: VideoProxyPlayerState get() = _currentState

    // Cached track groups for selection by index
    private var audioTrackGroups: List<Tracks.Group> = emptyList()
    private var subtitleTrackGroups: List<Tracks.Group> = emptyList()

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
        }

        textureView?.let { player?.setVideoTextureView(it) }
    }

    /**
     * Set the texture view for video rendering.
     */
    fun setTextureView(texture: TextureView?) {
        textureView = texture
        player?.setVideoTextureView(texture)
    }

    /**
     * Set the subtitle view for rendering embedded subtitles.
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
        player?.seekTo(positionMs)
        _currentState = _currentState.copy(currentTimeMs = positionMs)
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
     */
    fun setSubtitleTrack(groupIndex: Int) {
        val player = player ?: return
        val targetGroup = subtitleTrackGroups.getOrNull(groupIndex) ?: run {
            Timber.w("Subtitle track group index $groupIndex out of range (${subtitleTrackGroups.size} groups)")
            return
        }

        Timber.d("Selecting subtitle track $groupIndex for $videoId")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .addOverride(TrackSelectionOverride(targetGroup.mediaTrackGroup, listOf(0)))
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
    }

    /**
     * Disable all subtitle tracks.
     */
    fun disableSubtitles() {
        val player = player ?: return

        Timber.d("Disabling subtitles for $videoId")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        // Clear subtitle view
        subtitleView?.setCues(emptyList())
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
        textureView = null
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
            _currentState = _currentState.copy(
                durationMs = getDuration(),
            )
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
        _currentState = _currentState.copy(currentTimeMs = newPosition.positionMs)
        notifyStateChanged()
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
        subtitleView?.setCues(cueGroup.cues)
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
     */
    fun updateProgress() {
        val player = player ?: return
        if (player.playbackState == Player.STATE_READY && player.isPlaying) {
            _currentState = _currentState.copy(currentTimeMs = player.currentPosition)
            notifyStateChanged()
        }
    }

    private fun notifyStateChanged() {
        callback.onStateChanged(videoId, _currentState)
    }
}
