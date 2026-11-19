package org.jellyfin.mobile.player.videoproxy

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
class OverlayTrialActivity : ComponentActivity() {
    internal val clip: MediaItem.ClippingConfiguration?
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
                Log.i(OverlayTrial.TAG, "start id=$id viewport=${viewport.width}x${viewport.height} window=${frame.width}x${frame.height}")
                val events = manager.eventChannel
                events.trySend(VideoProxyEvent.VideoCreated(id))
                events.trySend(VideoProxyEvent.SetFullscreen(id, true))
                events.trySend(VideoProxyEvent.SetVisibility(id, true))
                events.trySend(VideoProxyEvent.SetSource(id, source))
                events.trySend(VideoProxyEvent.Play(id))
            }
        }
    }

    override fun onDestroy() {
        manager.release()
        web.destroy()
        super.onDestroy()
    }
}
