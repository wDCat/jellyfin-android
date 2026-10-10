package org.jellyfin.mobile.network

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** Stops active HTTP/2 connections from carrying requests to an old mapping after a settings change. */
class HostsConnectionListener(hosts: CustomHosts) : EventListener() {
    private val connections = ConcurrentHashMap<Call, Socket>()

    init {
        hosts.addListener {
            connections.values.toSet().forEach { socket ->
                try {
                    socket.close()
                } catch (_: IOException) {
                    // A request may have closed the socket concurrently.
                }
            }
        }
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        connections[call] = connection.socket()
    }

    override fun connectionReleased(call: Call, connection: Connection) {
        connections.remove(call)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        connections.remove(call)
    }

    override fun callEnd(call: Call) {
        connections.remove(call)
    }
}
