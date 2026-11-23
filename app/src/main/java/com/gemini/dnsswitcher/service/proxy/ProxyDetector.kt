package com.gemini.dnsswitcher.service.proxy

import android.net.VpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object ProxyDetector {

    /**
     * Автоопределение типа прокси: SOCKS5 или HTTP
     */
    suspend fun detectType(
        config: ProxyConfig,
        vpnService: VpnService? = null,
        timeoutMs: Int = 3000
    ): ProxyType = withContext(Dispatchers.IO) {
        if (config.type != ProxyType.AUTO) {
            return@withContext config.type
        }

        // 1. Проверяем SOCKS5
        if (isSocks5(config, vpnService, timeoutMs)) {
            return@withContext ProxyType.SOCKS5
        }

        // 2. Проверяем HTTP CONNECT
        if (isHttpProxy(config, vpnService, timeoutMs)) {
            return@withContext ProxyType.HTTP
        }

        // По умолчанию большинство прокси в формате user:pass@host:port — SOCKS5
        ProxyType.SOCKS5
    }

    private fun isSocks5(
        config: ProxyConfig,
        vpnService: VpnService?,
        timeoutMs: Int
    ): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            vpnService?.protect(socket)
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(config.host, config.port), timeoutMs)

            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            // SOCKS5 приветствие: Ver=5, NMethods=2 (0x00 No Auth, 0x02 User/Pass)
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            out.flush()

            val resp = ByteArray(2)
            val bytesRead = input.read(resp)
            bytesRead == 2 && resp[0] == 0x05.toByte() && (resp[1] == 0x00.toByte() || resp[1] == 0x02.toByte())
        } catch (_: Exception) {
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun isHttpProxy(
        config: ProxyConfig,
        vpnService: VpnService?,
        timeoutMs: Int
    ): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            vpnService?.protect(socket)
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(config.host, config.port), timeoutMs)

            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            val probe = "CONNECT google.com:443 HTTP/1.1\r\nHost: google.com:443\r\n\r\n"
            out.write(probe.toByteArray(Charsets.US_ASCII))
            out.flush()

            val buffer = ByteArray(16)
            val bytesRead = input.read(buffer)
            if (bytesRead >= 4) {
                val str = String(buffer, 0, bytesRead, Charsets.US_ASCII)
                str.startsWith("HTTP/")
            } else {
                false
            }
        } catch (_: Exception) {
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Измерение пинга до прокси-сервера (в миллисекундах)
     */
    suspend fun testConnection(
        config: ProxyConfig,
        vpnService: VpnService? = null,
        timeoutMs: Int = 4000
    ): Long? = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var socket: Socket? = null
        try {
            socket = Socket()
            vpnService?.protect(socket)
            socket.connect(InetSocketAddress(config.host, config.port), timeoutMs)
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
