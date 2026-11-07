package org.jellyfin.mobile.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer

/** Adds software video decoding and routes AAC program configurations to FFmpeg audio. */
@UnstableApi
class VideoRenderersFactory(
    context: Context,
    private val audioProcessor: AudioProcessor? = null,
    private val frameProcessingTiming: VideoFrameProcessingTiming? = null,
) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        val firstAudioRenderer = out.size
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF ||
            !FfmpegLibrary.isAvailable() || !FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_AAC)
        ) return

        val index = (firstAudioRenderer until out.size).first { out[it] is MediaCodecAudioRenderer }
        out[index] = object : MediaCodecAudioRenderer(
            context,
            codecAdapterFactory,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            audioSink,
        ) {
            override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int {
                // Media3 reports zero channels for AAC channelConfiguration=0 (PCE).
                // Android can reject its initialization data even though FFmpeg decodes it.
                if (format.sampleMimeType == MimeTypes.AUDIO_AAC && format.channelCount == 0) {
                    return C.FORMAT_UNSUPPORTED_SUBTYPE
                }
                return super.supportsFormat(mediaCodecSelector, format)
            }
        }
    }

    override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory {
        val factory = super.getCodecAdapterFactory()
        val timing = frameProcessingTiming ?: return factory
        return MediaCodecAdapter.Factory { configuration ->
            val adapter = factory.createAdapter(configuration)
            if (MimeTypes.isVideo(configuration.format.sampleMimeType)) {
                FrameProcessingCodecAdapter(adapter, timing)
            } else {
                adapter
            }
        }
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val processor = audioProcessor
            ?: return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        val sink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(processor))
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
        // Encoded passthrough/offload bypasses PCM processors. Decode audio for independent gain.
        return object : ForwardingAudioSink(sink) {
            override fun supportsFormat(format: Format): Boolean =
                getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

            override fun getFormatSupport(format: Format): Int =
                if (format.sampleMimeType == MimeTypes.AUDIO_RAW) {
                    super.getFormatSupport(format)
                } else {
                    AudioSink.SINK_FORMAT_UNSUPPORTED
                }

            override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
                AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out,
        )
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) return

        val index = if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) out.size - 1 else out.size
        out.add(
            index,
            FfmpegVideoRenderer(
                allowedVideoJoiningTimeMs,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            ),
        )
    }
}
