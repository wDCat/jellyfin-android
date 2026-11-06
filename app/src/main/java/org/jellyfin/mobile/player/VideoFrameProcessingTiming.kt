package org.jellyfin.mobile.player

/**
 * Wall-clock duration of successful MediaCodec output submission calls, in System.nanoTime units.
 * Start: immediately before releaseOutputBuffer(render=true / timestamp). End: immediately after
 * the call returns. Includes CPU work and any IPC/blocking inside that call. Excludes decoding,
 * the scheduled wait after submission, GPU execution and Surface/display presentation.
 *
 * Reports the mean of calls completed in the current sampling window. A reset invalidates in-flight
 * calls; empty, interrupted and oversized windows remain unknown. No timer or callback is installed.
 * Playback thread writes and the main-thread sampler reads under the same lock.
 */
class VideoFrameProcessingTiming {
    private companion object {
        const val MAX_FRAMES = 256
        const val MAX_DURATION_NS = 2_500_000_000L
        const val NS_PER_MS = 1_000_000.0
    }

    data class Frame(val startedNs: Long, val completedNs: Long)
    data class Window(val frames: List<Frame>) {
        val meanMs: Double? get() = frames.takeIf { it.isNotEmpty() }
            ?.let { calls -> calls.sumOf { it.completedNs - it.startedNs } / NS_PER_MS / calls.size }
    }

    private var enabled = false
    private var codec = 0L
    private var revision = 0L
    private var windowStartNs = System.nanoTime()
    private val completed = ArrayDeque<Frame>()

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        resetWindow()
        enabled = value
    }

    @Synchronized
    fun resetWindow() {
        revision++
        completed.clear()
        windowStartNs = System.nanoTime()
    }

    @Synchronized
    fun openCodec(): Long {
        resetWindow()
        return ++codec
    }

    @Synchronized
    fun closeCodec(token: Long) {
        if (token != codec) return
        resetWindow()
        codec++
    }

    @Synchronized
    fun resetCodec(token: Long) {
        if (token == codec) resetWindow()
    }

    @Synchronized
    fun revisionFor(token: Long): Long? = revision.takeIf { enabled && token == codec }

    @Synchronized
    fun record(token: Long, ticket: Long, startedNs: Long, completedNs: Long) {
        if (!enabled || token != codec || ticket != revision) return
        if (completedNs <= windowStartNs || completedNs < startedNs) return
        if (completedNs - startedNs > MAX_DURATION_NS) return
        if (completed.size >= MAX_FRAMES) completed.removeFirst()
        completed.addLast(Frame(startedNs, completedNs))
    }

    @Synchronized
    fun sampleWindow(valid: Boolean): Window {
        val frames = if (enabled && valid) completed.toList() else emptyList()
        completed.clear()
        windowStartNs = System.nanoTime()
        return Window(frames)
    }
}
