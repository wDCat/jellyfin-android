package org.jellyfin.mobile.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** Loopback HTTP proxy. HTTPS/WSS use opaque CONNECT tunnels; HTTP bodies are streamed unchanged. */
class HostsLocalProxy(private val hosts: CustomHosts) {
    private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    private val executor = Executors.newCachedThreadPool()
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(MAX_CONNECTIONS)
    val port: Int = server.localPort

    init {
        executor.execute {
            while (!server.isClosed) {
                val client = server.accept()
                if (slots.tryAcquire()) {
                    connections.add(client)
                    executor.execute {
                        try {
                            forward(client)
                        } finally {
                            connections.remove(client)
                            client.close()
                            slots.release()
                        }
                    }
                } else {
                    client.close()
                }
            }
        }
    }

    fun closeConnections() {
        connections.toList().forEach(Socket::close)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun forward(client: Socket) {
        var responseStarted = false
        try {
            client.soTimeout = HEADER_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val request = readRequest(input)
            connect(request.url).use { upstream ->
                connections.add(upstream)
                try {
                    responseStarted = true
                    exchange(client, input, upstream, request)
                } finally {
                    connections.remove(upstream)
                }
            }
        } catch (_: Exception) {
            if (!responseStarted && !client.isClosed) {
                try {
                    client.getOutputStream().write(
                        "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.ISO_8859_1),
                    )
                } catch (_: IOException) {
                    // The browser may already have cancelled this request.
                }
            }
        }
    }

    private fun exchange(client: Socket, input: InputStream, upstream: Socket, request: Request) {
        val output = client.getOutputStream()
        if (request.tunnel) {
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
        } else {
            upstream.getOutputStream().write(request.headers.toByteArray(Charsets.ISO_8859_1))
        }
        client.soTimeout = 0
        // A separate copy allows POST bodies, 100-continue, and WebSocket traffic in both directions.
        val upload = executor.submit {
            try {
                input.copyTo(upstream.getOutputStream())
                upstream.shutdownOutput()
            } catch (_: IOException) {
                // Closing either end cancels the other copy.
            }
        }
        try {
            upstream.getInputStream().copyTo(output)
        } finally {
            client.close()
            upload.cancel(true)
        }
    }

    private fun connect(url: HttpUrl): Socket {
        var failure: IOException? = null
        for (address in hosts.lookup(url.host)) {
            require(!(address.isLoopbackAddress && url.port == port)) { "Proxy loop" }
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, url.port), CONNECT_TIMEOUT_MS)
                return socket
            } catch (exception: IOException) {
                socket.close()
                failure = exception
            }
        }
        throw failure ?: IOException("No address available")
    }

    private data class Request(val url: HttpUrl, val tunnel: Boolean, val headers: String)

    private fun readRequest(input: InputStream): Request {
        val bytes = ByteArrayOutputStream()
        var ending = 0
        while (ending != HEADER_END) {
            val value = input.read()
            require(value >= 0 && bytes.size() < MAX_HEADER_BYTES) { "Incomplete or oversized proxy headers" }
            bytes.write(value)
            ending = (ending shl BYTE_BITS) or value
        }
        val lines = bytes.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter(String::isNotEmpty)
        val requestLine = lines.first().split(' ')
        require(requestLine.size == REQUEST_PARTS && requestLine[2] in listOf("HTTP/1.0", "HTTP/1.1"))
        val method = requestLine[0]
        require(method.matches(Regex("[A-Z]+")))
        val tunnel = method == "CONNECT"
        val url = requireNotNull(
            (if (tunnel) "https://${requestLine[1]}/" else requestLine[1]).toHttpUrlOrNull(),
        )
        require(url.username.isEmpty() && url.password.isEmpty())
        require(tunnel || url.scheme == "http")
        val headers = lines.drop(1)
        require(headers.all { ':' in it })
        val upgrade = headers.any { it.substringBefore(':').equals("Upgrade", ignoreCase = true) }
        val forwarded = headers.filterNot {
            it.substringBefore(':').lowercase() in listOf(
                "proxy-connection", "proxy-authorization", "connection", "host",
            )
        }
        val host = if (':' in url.host) "[${url.host}]" else url.host
        val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val head = buildString {
            append("$method $path HTTP/1.1\r\nHost: $host:${url.port}\r\n")
            forwarded.forEach { append(it).append("\r\n") }
            append(if (upgrade) "Connection: Upgrade\r\n" else "Connection: close\r\n")
            append("\r\n")
        }
        return Request(url, tunnel, head)
    }

    companion object {
        private const val MAX_CONNECTIONS = 32
        private const val HEADER_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val REQUEST_PARTS = 3
        private const val BYTE_BITS = 8
        private const val HEADER_END = 0x0d0a0d0a
    }
}
