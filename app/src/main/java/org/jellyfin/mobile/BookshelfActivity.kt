package org.jellyfin.mobile

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.dao.ServerDao
import org.jellyfin.mobile.network.HostsWebViewProxy
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.applyDefault
import org.jellyfin.mobile.utils.applyWindowInsetsAsMargins
import org.jellyfin.mobile.utils.isWebViewSupported
import org.jellyfin.mobile.utils.toast
import org.jellyfin.sdk.api.client.ApiClient
import org.json.JSONObject
import org.koin.android.ext.android.inject

class BookshelfActivity : AppCompatActivity() {
    private val preferences: AppPreferences by inject()
    private val serverDao: ServerDao by inject()
    private val hostsProxy: HostsWebViewProxy by inject()
    private val apiClient: ApiClient by inject()

    private lateinit var container: FrameLayout
    private var webView: WebView? = null
    private var serverLoad: Job? = null
    private var serverId: Long? = null
    private var savedNavigation: Bundle? = null
    private var connectDialog: AlertDialog? = null
    private val serverListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Constants.PREF_SERVER_ID) openSelectedServer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (!isWebViewSupported()) {
            toast(R.string.dialog_web_view_not_supported_message)
            finish()
            return
        }
        container = FrameLayout(this)
        setContentView(container)
        container.applyWindowInsetsAsMargins()
        savedNavigation = savedInstanceState?.getBundle(NAVIGATION_STATE)
        onBackPressedDispatcher.addCallback(this) {
            val view = webView
            if (view != null && view.canGoBack()) view.goBack() else finish()
        }
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        preferences.registerListener(serverListener)
        openSelectedServer()
    }

    private fun openSelectedServer() {
        val selected = preferences.currentServerId
        if (selected != null && selected == serverId) return
        serverLoad?.cancel()
        serverId = selected
        releaseWebView()
        if (selected == null) {
            showConnectDialog()
            return
        }
        connectDialog?.dismiss()
        serverLoad = lifecycleScope.launch {
            val server = withContext(Dispatchers.IO) { serverDao.getServer(selected) }
            if (selected != serverId || isFinishing) return@launch
            if (server == null) {
                serverId = null
                showConnectDialog()
                return@launch
            }
            val webRoot = "${server.hostname.trimEnd('/')}/web/"
            val view = WebView(this@BookshelfActivity)
            webView = view
            container.addView(view)
            view.configureBookshelf(webRoot)
            hostsProxy.ensureApplied { success ->
                if (webView !== view) return@ensureApplied
                if (!success) {
                    toast(R.string.custom_hosts_proxy_failed)
                    finish()
                    return@ensureApplied
                }
                val history = savedNavigation?.takeIf { it.getLong(SERVER_ID) == selected }
                savedNavigation = null
                if (history == null || view.restoreState(history) == null) {
                    view.loadUrl("${webRoot}jf-bookshelf/index.html")
                }
            }
        }
    }

    private fun WebView.configureBookshelf(webRoot: String) {
        settings.applyDefault()
        val origin = webRoot.toHttpUrl().newBuilder().encodedPath("/").build().toString().removeSuffix("/")
        val initializeDeviceId = """
            (() => {
                if (window !== window.top || location.origin !== ${JSONObject.quote(origin)}) return false;
                if (localStorage.getItem('_deviceId2')) return false;
                localStorage.setItem('_deviceId2', ${JSONObject.quote(apiClient.deviceInfo.id.toString())});
                return true;
            })()
        """.trimIndent()
        // Android supplies the main Web client's device ID natively. The standalone bookshelf
        // looks for the Web client's stored ID before it will validate the shared login.
        val atDocumentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (atDocumentStart) WebViewCompat.addDocumentStartJavaScript(this, initializeDeviceId, setOf(origin))
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!atDocumentStart) {
                    view.evaluateJavascript("if ($initializeDeviceId) location.reload()", null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // The bookshelf links back to the Jellyfin home page for sign-in.
                val url = request.url.buildUpon().clearQuery().fragment(null).build()
                if (!request.isForMainFrame || url != webRoot.toUri()) return false
                openJellyfin()
                return true
            }
        }
    }

    private fun showConnectDialog() {
        if (connectDialog?.isShowing == true) return
        connectDialog = AlertDialog.Builder(this)
            .setTitle(R.string.connect_to_server_title)
            .setPositiveButton(R.string.app_name) { _, _ -> openJellyfin() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun openJellyfin() {
        startActivity(Intent.makeMainActivity(ComponentName(this, MainActivity::class.java)))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val navigation = Bundle()
        if (webView?.saveState(navigation) != null) {
            navigation.putLong(SERVER_ID, requireNotNull(serverId))
            outState.putBundle(NAVIGATION_STATE, navigation)
        } else {
            outState.putBundle(NAVIGATION_STATE, savedNavigation)
        }
    }

    override fun onStop() {
        preferences.unregisterListener(serverListener)
        super.onStop()
    }

    private fun releaseWebView() {
        webView?.let {
            container.removeView(it)
            it.destroy()
        }
        webView = null
    }

    override fun onDestroy() {
        connectDialog?.dismiss()
        releaseWebView()
        super.onDestroy()
    }

    private companion object {
        const val NAVIGATION_STATE = "bookshelfNavigation"
        const val SERVER_ID = "serverId"
    }
}
