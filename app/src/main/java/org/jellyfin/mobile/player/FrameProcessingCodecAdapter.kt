package org.jellyfin.mobile.player

import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

/** Measures video output submission without changing scheduling, codec mode or platform callbacks. */
@UnstableApi
class FrameProcessingCodecAdapter(
    private val delegate: MediaCodecAdapter,
    private val timing: VideoFrameProcessingTiming,
) : MediaCodecAdapter by delegate {
    private val token = timing.openCodec()

    override fun releaseOutputBuffer(index: Int, render: Boolean) {
        if (render) {
            measureSubmission { delegate.releaseOutputBuffer(index, true) }
        } else {
            // Skipped/dropped buffers are not output submission measurements.
            delegate.releaseOutputBuffer(index, false)
        }
    }

    override fun releaseOutputBuffer(index: Int, renderTimeStampNs: Long) {
        // The requested timestamp is forwarded unchanged, never used to calculate a duration.
        measureSubmission { delegate.releaseOutputBuffer(index, renderTimeStampNs) }
    }

    private inline fun measureSubmission(release: () -> Unit) {
        val ticket = timing.revisionFor(token)
        if (ticket == null) {
            release()
            return
        }
        val start = System.nanoTime()
        release()
        val end = System.nanoTime()
        // Exceptions retain the original playback behavior and do not produce a successful sample.
        timing.record(token, ticket, start, end)
    }

    override fun flush() {
        timing.resetCodec(token)
        delegate.flush()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun setOutputSurface(surface: Surface) {
        timing.resetCodec(token)
        delegate.setOutputSurface(surface)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun detachOutputSurface() {
        timing.resetCodec(token)
        delegate.detachOutputSurface()
    }

    override fun release() {
        timing.closeCodec(token)
        delegate.release()
    }
}
