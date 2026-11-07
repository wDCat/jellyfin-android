package org.jellyfin.mobile.player.videoproxy

import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter

/** Measures bytes received over the last second, independently of bandwidth estimation. */
@UnstableApi
class VideoProxyDownloadSpeedMeter(private val delegate: BandwidthMeter) : BandwidthMeter by delegate, TransferListener {
    private companion object {
        const val BUCKET_MS = 100L
        const val BUCKET_COUNT = 10
    }

    private val lock = Any()
    private val ticks = LongArray(BUCKET_COUNT) { -1L }
    private val bytes = LongArray(BUCKET_COUNT)
    private val delegateListener = delegate.transferListener

    override fun getTransferListener(): TransferListener = this

    fun reset() = synchronized(lock) {
        ticks.fill(-1L)
        bytes.fill(0L)
    }

    fun getDownloadSpeedBps(): Long = synchronized(lock) {
        val tick = SystemClock.elapsedRealtime() / BUCKET_MS
        // Ten 100 ms buckets form a rolling one-second window. Stalled transfers expire to zero.
        bytes.indices.sumOf { if (tick - ticks[it] in 0 until BUCKET_COUNT) bytes[it] else 0L } * 8
    }

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        delegateListener?.onTransferInitializing(source, dataSpec, isNetwork)
    }

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        delegateListener?.onTransferStart(source, dataSpec, isNetwork)
    }

    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (isNetwork) {
            synchronized(lock) {
                val tick = SystemClock.elapsedRealtime() / BUCKET_MS
                val index = (tick % BUCKET_COUNT).toInt()
                if (ticks[index] != tick) {
                    ticks[index] = tick
                    bytes[index] = 0
                }
                bytes[index] += bytesTransferred
            }
        }
        delegateListener?.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        delegateListener?.onTransferEnd(source, dataSpec, isNetwork)
    }
}
