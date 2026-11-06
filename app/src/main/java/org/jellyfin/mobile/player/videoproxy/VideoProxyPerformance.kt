package org.jellyfin.mobile.player.videoproxy

import android.media.MediaCodecList
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.player.VideoFrameProcessingTiming
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Main-thread sampler. Counters describe renderer output, not final display scanout. */
@UnstableApi
class VideoProxyPerformance {
    private companion object {
        const val SAMPLE_INTERVAL_MS = 1000L
        const val MAX_WINDOW_MS = 2500L
        const val MAX_PUBLIC_INTERVAL_MS = 120000L
    }

    private data class Baseline(val rendered: Int, val dropped: Int) {
        val valid: Boolean get() = rendered >= 0 && dropped >= 0

        fun precedes(previous: Baseline?): Boolean = previous != null &&
            (rendered < previous.rendered || dropped < previous.dropped)

        fun delta(previous: Baseline?): Baseline? = if (valid && previous?.valid == true) {
            Baseline(rendered - previous.rendered, dropped - previous.dropped)
        } else {
            null
        }
    }

    val frameProcessingTiming = VideoFrameProcessingTiming()

    private val instanceId = UUID.randomUUID().toString()
    private var generation = 0L
    private var counters: DecoderCounters? = null
    private var baseline: Baseline? = null
    private var sampledAt = SystemClock.elapsedRealtime()
    var hardwareDecoding: Boolean? = null
        private set

    /** Pause/resume/seek invalidate the window without discarding the chart's history. */
    fun resetWindow() {
        baseline = null
        frameProcessingTiming.resetWindow()
    }

    fun resetSubscription(enabled: Boolean) {
        frameProcessingTiming.setEnabled(enabled)
        resetWindow()
        sampledAt = SystemClock.elapsedRealtime()
    }

    fun decoderChanged(name: String? = null) {
        generation++
        counters = null
        resetWindow()
        hardwareDecoding = actualHardwareDecoding(name)
    }

    fun sample(current: DecoderCounters?, playing: Boolean): JSONObject? {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - sampledAt
        if (elapsed < SAMPLE_INTERVAL_MS) return null
        current?.ensureUpdated()
        val next = current?.let { Baseline(it.renderedOutputBufferCount, it.droppedBufferCount) }
        val previous = baseline
        val changed = current !== counters || next?.precedes(previous) == true
        if (changed) generation++
        val delta = if (playing && !changed && elapsed <= MAX_WINDOW_MS) next?.delta(previous) else null
        val fps = delta?.let { it.rendered * SAMPLE_INTERVAL_MS.toDouble() / elapsed }
        val processing = frameProcessingTiming.sampleWindow(delta != null)
        counters = current
        baseline = next.takeIf { playing }
        sampledAt = now
        // Oversized windows only establish a baseline; never invent a shorter measurement interval.
        if (elapsed > MAX_PUBLIC_INTERVAL_MS) return null
        return JSONObject().apply {
            put("name", "ExoPlayer · renderer output")
            put("kind", "player")
            put("sampledAtMonotonicMs", now)
            put("intervalMs", elapsed)
            put("fps", fps ?: JSONObject.NULL)
            put("droppedFrames", delta?.dropped ?: JSONObject.NULL)
            putFrameProcessing(processing)
            put("hardwareDecoding", hardwareDecoding ?: JSONObject.NULL)
            put("generation", "$instanceId:$generation")
        }
    }

    private fun JSONObject.putFrameProcessing(window: VideoFrameProcessingTiming.Window) {
        put("frameDelayMs", window.meanMs ?: JSONObject.NULL)
        put("frameDelayKind", "processing")
        if (BuildConfig.DEBUG) put("frameProcessingTiming", window.toDebugJson())
    }

    /** Debug-only evidence; omitted from the public API by the page adapter. No media identifiers. */
    private fun VideoFrameProcessingTiming.Window.toDebugJson() = JSONObject().apply {
        put("frameCount", frames.size)
        val timings = frames.map { frame ->
            JSONObject().apply {
                put("startedNs", frame.startedNs)
                put("completedNs", frame.completedNs)
            }
        }
        put("frames", JSONArray(timings))
    }

    private fun actualHardwareDecoding(name: String?): Boolean? {
        if (name == null) return null
        // This project's Nextlib FfmpegVideoDecoder reports ffmpeg<version>-<codec>.
        if (name.startsWith("ffmpeg")) return false
        // Older platform/Media3 classifications rely on name heuristics: leave them unknown.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val codec = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && (it.name == name || it.canonicalName == name)
            }
            when {
                codec?.isHardwareAccelerated == true -> true
                codec?.isSoftwareOnly == true -> false
                else -> null
            }
        }.getOrNull()
    }
}
