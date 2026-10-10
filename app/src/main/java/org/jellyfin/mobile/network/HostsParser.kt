package org.jellyfin.mobile.network

import java.net.IDN
import java.net.InetAddress
import java.util.Locale

/** Parses IP literals without looking up names. Later entries replace earlier entries for the same host. */
object HostsParser {
    private const val MAX_HOST_LENGTH = 253
    private val whitespace = Regex("\\s+")
    private val label = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    private val ipv6Characters = Regex("[0-9a-fA-F:.]+")

    fun normalizeHost(host: String): String = IDN.toASCII(host.removeSuffix(".")).lowercase(Locale.ROOT)

    fun parse(text: String): Map<String, InetAddress> = buildMap {
        text.lineSequence().forEachIndexed { index, line ->
            val content = line.substringBefore('#').trim()
            if (content.isNotEmpty()) {
                try {
                    val fields = content.split(whitespace)
                    require(fields.size >= 2)
                    val address = parseAddress(fields.first())
                    fields.drop(1).forEach { name ->
                        val wildcard = name.startsWith("*.")
                        val host = normalizeHost(if (wildcard) name.removePrefix("*.") else name)
                        require(host.length <= MAX_HOST_LENGTH && host.split('.').all { it.matches(label) })
                        // A host entry must be a name, not another IP literal.
                        require(!host.all { it.isDigit() || it == '.' })
                        put(if (wildcard) "*.$host" else host, address)
                    }
                } catch (exception: IllegalArgumentException) {
                    throw IllegalArgumentException((index + 1).toString(), exception)
                }
            }
        }
    }

    /** Exact names take precedence, then the most specific wildcard suffix. Wildcards exclude the bare domain. */
    fun findAddress(entries: Map<String, InetAddress>, hostname: String): InetAddress? {
        val host = normalizeHost(hostname)
        return entries[host] ?: entries.entries
            .filter { (pattern, _) -> pattern.startsWith("*.") && host.endsWith(pattern.removePrefix("*")) }
            .maxByOrNull { it.key.length }?.value
    }

    private fun parseAddress(value: String): InetAddress {
        if (':' in value) {
            require(value.matches(ipv6Characters))
            return try {
                InetAddress.getByName(value)
            } catch (exception: java.net.UnknownHostException) {
                throw IllegalArgumentException("Invalid IPv6 address", exception)
            }
        }
        val parts = value.split('.')
        require(parts.size == IPV4_BYTES)
        val bytes = parts.map { part ->
            require(part.isNotEmpty() && part.all(Char::isDigit))
            val number = part.toIntOrNull()
            require(number != null && number in 0..MAX_BYTE)
            number.toByte()
        }.toByteArray()
        return InetAddress.getByAddress(bytes)
    }

    private const val IPV4_BYTES = 4
    private const val MAX_BYTE = 255
}
