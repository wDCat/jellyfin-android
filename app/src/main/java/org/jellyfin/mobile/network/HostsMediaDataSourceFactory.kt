package org.jellyfin.mobile.network

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import org.chromium.net.ExperimentalCronetEngine
import org.jellyfin.mobile.utils.Constants
import org.json.JSONObject
import timber.log.Timber
import java.net.InetAddress
import java.util.concurrent.Executors

/** Acquires an engine for each open so seeks and new streams use the current hosts configuration. */
class HostsMediaDataSourceFactory(
    private val context: Context,
    private val hosts: CustomHosts,
    client: OkHttpClient,
) : DataSource.Factory {
    private val executor = Executors.newCachedThreadPool()
    private val fallback = OkHttpDataSource.Factory(client)
        .setUserAgent(Util.getUserAgent(context, Constants.APP_INFO_NAME))
    private var current: Engine? = null

    private class Engine(
        val cronet: CronetEngine?,
        val factory: DataSource.Factory,
        var users: Int = 0,
    )

    init {
        hosts.addListener {
            synchronized(this) {
                val old = current
                current = null
                if (old?.users == 0) old.cronet?.shutdown()
            }
        }
    }

    @Synchronized
    private fun acquire(): Engine {
        val engine = current ?: buildEngine().also { current = it }
        engine.users++
        return engine
    }

    @Synchronized
    private fun release(engine: Engine) {
        engine.users--
        if (engine !== current && engine.users == 0) engine.cronet?.shutdown()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun buildEngine(): Engine {
        val entries = hosts.entries
        try {
            val provider = CronetProvider.getAllProviders(context).firstOrNull {
                it.name == CronetProvider.PROVIDER_NAME_APP_PACKAGED && it.isEnabled
            } ?: return Engine(null, fallback)
            val builder = provider.createBuilder()
            if (entries.isNotEmpty()) {
                // The packaged implementation exposes the experimental host resolver options.
                val experimental = builder as? ExperimentalCronetEngine.Builder ?: return Engine(null, fallback)
                val ordered = entries.entries.sortedWith(
                    compareBy<Map.Entry<String, InetAddress>> { it.key.startsWith("*.") }
                        .thenByDescending { it.key.length },
                )
                val rules = ordered.joinToString(", ") { (host, address) ->
                    val ip = address.hostAddress!!
                    "MAP $host " + if (':' in ip) "[$ip]" else ip
                }
                experimental.setExperimentalOptions(
                    JSONObject().put("HostResolverRules", JSONObject().put("host_resolver_rules", rules)).toString(),
                )
            }
            val cronet = builder.enableHttp2(true).enableQuic(true).enableBrotli(true)
                .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_IN_MEMORY, HTTP_CACHE_SIZE).build()
            val factory = CronetDataSource.Factory(cronet, executor)
                .setUserAgent(Util.getUserAgent(context, Constants.APP_INFO_NAME))
            return Engine(cronet, factory)
        } catch (exception: Exception) {
            Timber.w(exception, "Cronet unavailable; using hosts-aware OkHttp for playback")
            return Engine(null, fallback)
        }
    }

    override fun createDataSource(): DataSource = Source()

    private inner class Source : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var engine: Engine? = null
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners.add(transferListener)
            delegate?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            check(delegate == null)
            val acquired = acquire()
            engine = acquired
            val opened = acquired.factory.createDataSource()
            delegate = opened
            listeners.forEach(opened::addTransferListener)
            return opened.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            checkNotNull(delegate).read(buffer, offset, length)

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                delegate?.close()
            } finally {
                delegate = null
                engine?.let(::release)
                engine = null
            }
        }
    }

    companion object {
        private const val HTTP_CACHE_SIZE = 16L * 1024 * 1024
    }
}
