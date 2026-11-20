package org.jellyfin.mobile.player.videoproxy

import android.graphics.Bitmap
import android.opengl.GLES20 as GL
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.GlUtil
import androidx.media3.exoplayer.ExoPlayer
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONObject
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import kotlinx.coroutines.launch
import org.jellyfin.mobile.webapp.WebappFunctionChannel
import org.koin.android.ext.android.get
import java.io.File

/** ADB-only fixture using the same manager, event channel, decoder and SurfaceView as the Web player. */
@UnstableApi
internal class OverlayTrialActivity : ComponentActivity(), OverlayBenchmark {
    override val clip: MediaItem.ClippingConfiguration?
        get() {
            if (!intent.hasExtra("item")) return null
            val start = intent.getLongExtra("startMs", 0L).coerceAtLeast(0)
            return MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start)
                .setEndPositionMs(start + 30_000).build()
        }

    private lateinit var manager: VideoOverlayManager
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val frame = FrameLayout(this)
        val viewport = FrameLayout(this)
        frame.addView(viewport, FrameLayout.LayoutParams(1920, 1080, Gravity.CENTER))
        val video = FrameLayout(this)
        val subs = FrameLayout(this)
        web = WebView(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        viewport.addView(video)
        viewport.addView(subs)
        viewport.addView(web)
        setContentView(frame)
        window.insetsController?.hide(WindowInsets.Type.systemBars())
        val callbacks = WebappFunctionChannel()
        manager = VideoOverlayManager(this, get(), callbacks, lifecycleScope, get(), get(), get(), get()) {}
        manager.initialize(video, subs, web)
        lifecycleScope.launch { for (script in callbacks) web.evaluateJavascript(script, null) }

        val id = if (intent.getBooleanExtra("hybrid", true)) "trial-gpu" else "trial-direct"
        lifecycleScope.launch {
            val item = intent.getStringExtra("item")
            val source = if (item != null) {
                val uuid = requireNotNull(item.toUUIDOrNull())
                requireNotNull(get<ApiClientController>().loadSavedServerUser())
                get<ApiClient>().videosApi.getVideoStreamUrl(itemId = uuid, static = true,
                    mediaSourceId = intent.getStringExtra("source") ?: item)
            } else {
                val name = if (intent.getBooleanExtra("catalogCheck", false)) "model-check.mp4" else "anime4k-overlay-fixture.mp4"
                val fixture = File(filesDir, "00000000-0000-0000-0000-000000000001/$name")
                require(fixture.isFile) { "missing ${fixture.name} in filesDir" }
                fixture.toURI().toString()
            }
            viewport.post {
                Log.i(OverlayEnhancement.TAG, "start id=$id viewport=${viewport.width}x${viewport.height} window=${frame.width}x${frame.height}")
                val events = manager.eventChannel
                events.trySend(VideoProxyEvent.VideoCreated(id))
                events.trySend(VideoProxyEvent.SetFullscreen(id, true))
                events.trySend(VideoProxyEvent.SetVisibility(id, true))
                events.trySend(VideoProxyEvent.SetSource(id, source))
                events.trySend(VideoProxyEvent.Play(id))
            }
        }
    }

    override fun onCnnFrame(frame: Int, pts: Long, startNs: Long, width: Int, height: Int, draw: (Boolean) -> Unit) {
        GL.glFinish()
        Log.i(OverlayEnhancement.TAG, "gpuFrame pts=$pts total=${System.nanoTime() - startNs}")
        if (frame == 72 && intent.getBooleanExtra("capture", false)) {
            saveCrop("enhanced", width, height)
            draw(false)
            saveCrop("bilinear", width, height)
            draw(true)
        }
    }

    /** Capture the same central patch twice from one decoded frame, only in a separate visual trial. */
    private fun saveCrop(label: String, width: Int, height: Int) {
        val side = 512
        val bytes = ByteBuffer.allocateDirect(side * side * 4)
        GL.glReadPixels(width - side / 2, height - side / 2, side, side, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, bytes)
        GlUtil.checkGlError()
        val topDown = ByteBuffer.allocateDirect(bytes.capacity())
        for (y in side - 1 downTo 0) {
            bytes.limit((y + 1) * side * 4)
            bytes.position(y * side * 4)
            topDown.put(bytes)
        }
        topDown.flip()
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(topDown)
        cacheDir.resolve("anime4k-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    override fun configurePlayer(videoId: String, player: ExoPlayer) {
        if (intent.getBooleanExtra("hybrid", true)) {
            val model = intent.getStringExtra("model") ?: "anime4k/Upscale/CNNx2S"
            val width = if (intent.getBooleanExtra("catalogCheck", false)) 128 else if (clip == null) 960 else 1920
            val height = width * 9 / 16
            val json = JSONObject().put("effects", JSONArray().put(JSONObject().put("id", model)))
                .put("target", JSONObject().put("width", width*2).put("height", height*2))
            OverlayEnhancement.apply(player, json.toString()) { state, reason, fps, _ -> Log.i(OverlayEnhancement.TAG, "model=$model state=$state reason=$reason fps=$fps") }
        }
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) { Log.i(OverlayEnhancement.TAG, "decoder=$decoderName") }
        })
        var started = 0L
        // PTS + requested release time allow offline checks for skipped frames and release cadence.
        player.setVideoFrameMetadataListener { pts, release, _, _ ->
            Log.i(OverlayEnhancement.TAG, "release pts=$pts ns=$release")
        }
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (started == 0L) started = System.nanoTime()
                Log.i(OverlayEnhancement.TAG, "first id=$videoId decoder=${player.videoFormat?.sampleMimeType}")
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(OverlayEnhancement.TAG, "error id=$videoId", error)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                val c = player.videoDecoderCounters ?: return
                c.ensureUpdated()
                Log.i(OverlayEnhancement.TAG, "ended id=$videoId rendered=${c.renderedOutputBufferCount} dropped=${c.droppedBufferCount} " +
                    "skipped=${c.skippedOutputBufferCount} maxConsecutive=${c.maxConsecutiveDroppedBufferCount} " +
                    "elapsedMs=${if (started == 0L) null else (System.nanoTime()-started)/1e6}")
            }
        })
    }

    override fun onDestroy() {
        manager.release()
        web.destroy()
        super.onDestroy()
    }
}
