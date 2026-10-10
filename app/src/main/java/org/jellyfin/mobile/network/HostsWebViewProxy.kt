package org.jellyfin.mobile.network

import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import timber.log.Timber
import java.util.concurrent.Executor

/** Waits for Chromium to apply the process-wide proxy override before loading a page. */
class HostsWebViewProxy(private val hosts: CustomHosts) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private var proxy: HostsLocalProxy? = null
    private var applied = false
    private var applying = false
    private var initialized = false
    private val waiting = mutableListOf<(Boolean) -> Unit>()

    init {
        hosts.addListener {
            handler.post {
                proxy?.closeConnections()
                if (initialized) apply()
            }
        }
    }

    fun ensureApplied(onReady: (Boolean) -> Unit) {
        handler.post {
            initialized = true
            waiting.add(onReady)
            apply()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun apply() {
        if (applying) return
        val enabled = hosts.entries.isNotEmpty()
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            complete(!enabled)
            return
        }
        if (enabled == applied) {
            complete(true)
            return
        }
        applying = true
        try {
            val controller = ProxyController.getInstance()
            val finished = Runnable {
                applied = enabled
                applying = false
                apply() // A preference may have changed while Chromium applied the previous override.
            }
            if (enabled) {
                val local = proxy ?: HostsLocalProxy(hosts).also { proxy = it }
                val config = ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:${local.port}")
                    .removeImplicitRules()
                    .build()
                controller.setProxyOverride(config, executor, finished)
            } else {
                controller.clearProxyOverride(executor, finished)
            }
        } catch (exception: Exception) {
            Timber.e(exception, "Unable to configure WebView hosts proxy")
            applying = false
            complete(false)
        }
    }

    private fun complete(success: Boolean) {
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { it(success) }
    }
}
