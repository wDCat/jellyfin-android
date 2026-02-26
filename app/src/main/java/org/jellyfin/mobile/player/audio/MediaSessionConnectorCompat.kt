package org.jellyfin.mobile.player.audio

import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * A lightweight bridge between a Media3 Player and MediaSessionCompat,
 * replacing the removed ExoPlayer MediaSessionConnector.
 */
@UnstableApi
class MediaSessionConnectorCompat(
    private val mediaSession: MediaSessionCompat,
) : Player.Listener {

    private var player: Player? = null
    private var playbackPreparer: PlaybackPreparer? = null
    private var queueNavigator: QueueNavigator? = null

    interface PlaybackPreparer {
        fun getSupportedPrepareActions(): Long
        fun onPrepare(playWhenReady: Boolean)
        fun onPrepareFromMediaId(mediaId: String, playWhenReady: Boolean, extras: android.os.Bundle?)
        fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: android.os.Bundle?)
        fun onPrepareFromUri(uri: android.net.Uri, playWhenReady: Boolean, extras: android.os.Bundle?)
        fun onCommand(player: Player, command: String, extras: android.os.Bundle?, cb: android.os.ResultReceiver?): Boolean
    }

    interface QueueNavigator {
        fun getMediaDescription(player: Player, windowIndex: Int): MediaDescriptionCompat
    }

    fun setPlayer(player: Player?) {
        this.player?.removeListener(this)
        this.player = player
        player?.addListener(this)
        updatePlaybackState()
    }

    fun setPlaybackPreparer(preparer: PlaybackPreparer) {
        this.playbackPreparer = preparer
        setupMediaSessionCallback()
    }

    fun setQueueNavigator(navigator: QueueNavigator) {
        this.queueNavigator = navigator
    }

    private fun setupMediaSessionCallback() {
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                player?.play()
            }

            override fun onPause() {
                player?.pause()
            }

            override fun onStop() {
                player?.stop()
            }

            override fun onSeekTo(pos: Long) {
                player?.seekTo(pos)
            }

            override fun onSkipToNext() {
                player?.seekToNextMediaItem()
            }

            override fun onSkipToPrevious() {
                player?.seekToPreviousMediaItem()
            }

            override fun onPrepare() {
                playbackPreparer?.onPrepare(true)
            }

            override fun onPrepareFromMediaId(mediaId: String, extras: android.os.Bundle?) {
                playbackPreparer?.onPrepareFromMediaId(mediaId, true, extras)
            }

            override fun onPrepareFromSearch(query: String, extras: android.os.Bundle?) {
                playbackPreparer?.onPrepareFromSearch(query, true, extras)
            }

            override fun onPrepareFromUri(uri: android.net.Uri, extras: android.os.Bundle?) {
                playbackPreparer?.onPrepareFromUri(uri, true, extras)
            }

            override fun onPlayFromMediaId(mediaId: String, extras: android.os.Bundle?) {
                playbackPreparer?.onPrepareFromMediaId(mediaId, true, extras)
            }

            override fun onPlayFromSearch(query: String, extras: android.os.Bundle?) {
                playbackPreparer?.onPrepareFromSearch(query, true, extras)
            }
        })
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        updatePlaybackState()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        updatePlaybackState()
    }

    private fun updatePlaybackState() {
        val p = player ?: return
        val state = when {
            p.playbackState == Player.STATE_IDLE -> PlaybackStateCompat.STATE_NONE
            p.playbackState == Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
            p.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            p.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val prepareActions = playbackPreparer?.getSupportedPrepareActions() ?: 0L
        val actions = prepareActions or
            PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

        val playbackStateCompat = PlaybackStateCompat.Builder()
            .setState(state, p.currentPosition, 1f)
            .setActions(actions)
            .build()
        mediaSession.setPlaybackState(playbackStateCompat)
    }
}
