package org.jellyfin.mobile.webapp

import android.view.View
import android.webkit.WebView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import timber.log.Timber

/**
 * Restores the WebView compositor after the activity window surface is destroyed.
 *
 * A long background drops the WebView GPU context. Video-proxy mode makes that visible:
 * the page is transparent, the underlay is black, and ExoPlayer's SurfaceView is a separate
 * surface that still shows the last frame. The page stays black until the next touch produces
 * a frame. This observer produces that frame on resume.
 */
internal class WebViewCompositorRecovery(
    private val webView: WebView,
    private val videoProxyEnabled: Boolean,
    private val isOverlayCompositingActive: () -> Boolean,
    private val applyCompositing: (Boolean) -> Unit,
) : DefaultLifecycleObserver {
    private var redraw: Runnable? = null
    private var delayedRedraw: Runnable? = null

    override fun onStart(owner: LifecycleOwner) {
        // The window surface is recreated here. Re-apply the overlay format before the first
        // draw so a resumed translucent window is not stuck on an opaque or black buffer.
        if (videoProxyEnabled) applyCompositing(isOverlayCompositingActive())
    }

    override fun onResume(owner: LifecycleOwner) {
        // The system pauses WebView rendering while the window is stopped.
        webView.onResume()
        webView.resumeTimers()
        cancel()
        val immediate = Runnable { forceFrame() }
        val delayed = Runnable { forceFrame() }
        redraw = immediate
        delayedRedraw = delayed
        // The new window surface may not exist on the first traversal. Nudge again once it does.
        webView.post(immediate)
        webView.postDelayed(delayed, COMPOSITOR_REDRAW_DELAY_MS)
    }

    override fun onPause(owner: LifecycleOwner) {
        cancel()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        cancel()
        owner.lifecycle.removeObserver(this)
    }

    private fun cancel() {
        redraw?.let(webView::removeCallbacks)
        delayedRedraw?.let(webView::removeCallbacks)
        redraw = null
        delayedRedraw = null
    }

    private fun forceFrame() {
        if (!webView.isAttachedToWindow) return
        val overlayVisible = videoProxyEnabled && isOverlayCompositingActive()
        if (videoProxyEnabled) applyCompositing(overlayVisible)
        // Do not clear the current frame first. A healthy page stays put; a discarded
        // display list gets the same nudge a tap would give it, without a black flash.
        webView.invalidate()
        (webView.parent as? View)?.invalidate()
        webView.evaluateJavascript(FORCE_COMPOSITOR_FRAME_JS, null)
        Timber.d("Forced WebView compositor frame after resume (overlay=%s)", overlayVisible)
    }

    private companion object {
        const val COMPOSITOR_REDRAW_DELAY_MS = 300L

        // A layout read plus a composited transform wakes Blink after the renderer was frozen.
        // The transform is cleared in the same turn so it cannot stick if the frame is dropped.
        const val FORCE_COMPOSITOR_FRAME_JS = """
            (function() {
                var root = document.documentElement;
                if (!root) return;
                var transform = root.style.transform;
                root.style.transform = transform ? transform + ' translateZ(0)' : 'translateZ(0)';
                void root.offsetWidth;
                root.style.transform = transform;
                requestAnimationFrame(function() { void root.offsetWidth; });
            })();
        """
    }
}
