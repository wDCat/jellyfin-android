package org.jellyfin.mobile.player.videoproxy

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import org.jellyfin.mobile.app.AppPreferences
import java.nio.ByteBuffer

/** Applies the overlay player's independent volume to decoded PCM audio. */
@UnstableApi
class VideoProxyVolumeProcessor : BaseAudioProcessor() {
    @Volatile
    var gain: Float = 1f
        private set

    fun setVolumePercent(percent: Int) {
        gain = percent.coerceIn(0, AppPreferences.MAX_VIDEO_PROXY_VOLUME) /
            AppPreferences.DEFAULT_VIDEO_PROXY_VOLUME.toFloat()
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        // Stay active at 100% so volume changes do not require rebuilding the audio pipeline.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val output = replaceOutputBuffer(inputBuffer.remaining())
        val currentGain = gain
        if (currentGain == 1f) {
            output.put(inputBuffer)
        } else {
            while (inputBuffer.hasRemaining()) {
                val sample = (inputBuffer.short * currentGain).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.putShort(sample.toShort())
            }
        }
        output.flip()
    }
}
