package org.jellyfin.mobile.network

import android.content.SharedPreferences
import okhttp3.Dns
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.utils.Constants
import timber.log.Timber
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Shared by native clients and the WebView proxy. Requests keep their original URLs. */
class CustomHosts(private val preferences: AppPreferences) : Dns {
    @Volatile
    var entries: Map<String, InetAddress> = readEntries()
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Constants.PREF_CUSTOM_HOSTS_ENABLED || key == Constants.PREF_CUSTOM_HOSTS_ENTRIES) {
            val updated = readEntries()
            if (updated != entries) {
                entries = updated
                listeners.forEach { it() }
            }
        }
    }

    init {
        preferences.registerListener(preferenceListener)
    }

    override fun lookup(hostname: String): List<InetAddress> =
        HostsParser.findAddress(entries, hostname)?.let(::listOf) ?: Dns.SYSTEM.lookup(hostname)

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun readEntries(): Map<String, InetAddress> = if (preferences.customHostsEnabled) {
        try {
            HostsParser.parse(preferences.customHostsEntries)
        } catch (exception: IllegalArgumentException) {
            Timber.e(exception, "Invalid saved hosts configuration")
            emptyMap()
        }
    } else {
        emptyMap()
    }
}
