package org.jellyfin.mobile.player.videoproxy

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** FFmpeg renders to a Surface directly and does not implement MSG_SET_VIDEO_EFFECTS. */
@UnstableApi
internal class EnhancedFfmpegRenderer(
    private val context: Context,
    renderer: Renderer,
    private val firstFrame: (Surface, Long) -> Unit,
) : ForwardingRenderer(renderer) {
    // FFmpeg reports the intermediate input Surface. Notify ExoPlayer for the visible output as well.
    private val awaitingFirstFrame = AtomicReference<Surface?>()
    private var effects = emptyList<Effect>()
    private var target: Surface? = null
    private var size = Size.UNKNOWN
    private var format: Format? = null
    private var processor: VideoFrameProcessor? = null
    @Volatile private var failed = false

    override fun enable(configuration: RendererConfiguration, formats: Array<Format>, stream: SampleStream,
                        positionUs: Long, joining: Boolean, mayRenderStartOfStream: Boolean, startPositionUs: Long,
                        offsetUs: Long, mediaPeriodId: MediaPeriodId) {
        super.enable(configuration, formats, stream, positionUs, joining, mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId)
        select(formats)
    }

    override fun replaceStream(formats: Array<Format>, stream: SampleStream, startPositionUs: Long,
                               offsetUs: Long, mediaPeriodId: MediaPeriodId) {
        super.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
        select(formats)
    }

    private fun select(formats: Array<Format>) {
        val candidate = formats.singleOrNull()?.takeIf {
            it.width > 0 && it.height > 0 && it.drmInitData == null && !ColorInfo.isTransferHdr(it.colorInfo)
        }
        if (format == candidate) return
        detach()
        format = candidate
        failed = false
        routeOutput()
    }

    override fun handleMessage(messageType: Int, message: Any?) {
        when (messageType) {
            Renderer.MSG_SET_VIDEO_EFFECTS -> {
                val next = (message as? List<*>)?.filterIsInstance<Effect>().orEmpty()
                if (effects != next) {
                    // Replace the processor between frames. Automatic Surface registration
                    // keeps one FrameInfo per stream; late buffers must stay on the old input.
                    detach()
                    effects = next
                    failed = false
                    routeOutput()
                }
            }
            Renderer.MSG_SET_VIDEO_OUTPUT -> {
                if (target !== message) awaitingFirstFrame.set(message as? Surface)
                target = message as? Surface
                if (message != null && target == null) {
                    detach()
                    super.handleMessage(messageType, message)
                } else routeOutput()
            }
            Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION -> {
                size = message as Size
                processor?.setOutputSurfaceInfo(outputInfo())
                super.handleMessage(messageType, message)
            }
            else -> super.handleMessage(messageType, message)
        }
    }

    private fun outputInfo(): SurfaceInfo? {
        val f = format ?: return null
        val surface = target?.takeIf { it.isValid } ?: return null
        return SurfaceInfo(surface, size.width.takeIf { it > 0 } ?: f.width, size.height.takeIf { it > 0 } ?: f.height)
    }

    private fun routeOutput() {
        val f = format
        val info = outputInfo()
        if (failed || f == null || info == null) {
            detach()
            super.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, target)
            return
        }
        try {
            if (processor == null) {
                awaitingFirstFrame.set(info.surface)
                val ready = CountDownLatch(1)
                val registered = effects.filterIsInstance<MenuGpuEffect>()
                val p = DefaultVideoFrameProcessor.Factory.Builder().build().create(context, DebugViewProvider.NONE,
                    ColorInfo.SDR_BT709_LIMITED, true, Runnable::run, object : VideoFrameProcessor.Listener {
                        override fun onOutputFrameAvailableForRendering(presentationTimeUs: Long, isRedrawnFrame: Boolean) {
                            awaitingFirstFrame.getAndSet(null)?.let { firstFrame(it, SystemClock.elapsedRealtime()) }
                        }

                        override fun onError(exception: VideoFrameProcessingException) {
                            Log.w(OverlayTrial.TAG, "FFmpeg enhancement failed", exception)
                            failed = true
                            registered.forEach { it.outputFailed() }
                            ready.countDown()
                        }
                    })
                processor = p
                p.setOutputSurfaceInfo(info)
                p.setOnInputSurfaceReadyListener(ready::countDown)
                registerStream(p, f)
                check(ready.await(5, TimeUnit.SECONDS) && !failed) { "FFmpeg enhancement input unavailable" }
            } else processor?.setOutputSurfaceInfo(info)
            super.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, processor!!.inputSurface)
        } catch (e: Exception) {
            Log.w(OverlayTrial.TAG, "Returning FFmpeg to direct output", e)
            failed = true
            effects.filterIsInstance<MenuGpuEffect>().forEach { it.outputFailed() }
            detach()
            super.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, target)
        }
    }

    private fun registerStream(p: VideoFrameProcessor, f: Format) {
        val color = f.colorInfo?.takeIf { it.isDataSpaceValid } ?: ColorInfo.SDR_BT709_LIMITED
        p.registerInputStream(VideoFrameProcessor.INPUT_TYPE_SURFACE_AUTOMATIC_FRAME_REGISTRATION,
            f.buildUpon().setColorInfo(color).build(), effects, 0L)
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (failed && processor != null) routeOutput()
        super.render(positionUs, elapsedRealtimeUs)
    }

    override fun resetPosition(positionUs: Long) {
        val recreate = processor != null
        if (recreate) detach()
        super.resetPosition(positionUs)
        if (recreate) routeOutput()
    }

    private fun detach() {
        val p = processor ?: return
        super.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, null)
        processor = null
        awaitingFirstFrame.set(null)
        p.release()
    }

    override fun disable() {
        detach()
        format = null
        super.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, target)
        super.disable()
    }

    override fun release() {
        detach()
        super.release()
    }
}
