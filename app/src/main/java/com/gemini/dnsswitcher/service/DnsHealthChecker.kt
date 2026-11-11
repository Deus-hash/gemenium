package com.gemini.dnsswitcher.service

import android.net.VpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

class DnsHealthChecker(private val vpnService: VpnService) {

    private val txIdCounter = AtomicInteger(0x1000)

    // Кэшированный разрешенный IP целевого хоста для исключения влияния сторонних DNS
    @Volatile
    private var lastResolvedHost: String? = null
    @Volatile
    private var lastResolvedAddress: InetAddress? = null

    private fun buildQueryPacket(domain: String, txId: Int): ByteArray {
        val parts = domain.split(".")
        var totalLen = 12 // DNS header
        for (part in parts) {
            totalLen += 1 + part.length
        }
        totalLen += 1 // null terminator
        totalLen += 4 // QTYPE (2) + QCLASS (2)

        val data = ByteArray(totalLen)
        // Transaction ID
        data[0] = ((txId ushr 8) and 0xFF).toByte()
        data[1] = (txId and 0xFF).toByte()
        // Flags: Standard query, recursion desired (0x0100)
        data[2] = 0x01.toByte()
        data[3] = 0x00.toByte()
        // Questions: 1
        data[4] = 0x00.toByte()
        data[5] = 0x01.toByte()
        // Answer RRs: 0, Authority RRs: 0, Additional RRs: 0 (6..11 = 0)

        var offset = 12
        for (part in parts) {
            val partBytes = part.toByteArray(Charsets.US_ASCII)
            data[offset++] = partBytes.size.toByte()
            System.arraycopy(partBytes, 0, data, offset, partBytes.size)
            offset += partBytes.size
        }
        data[offset++] = 0x00.toByte() // end of name

        // QTYPE = 1 (A record)
        data[offset++] = 0x00.toByte()
        data[offset++] = 0x01.toByte()
        // QCLASS = 1 (IN)
        data[offset++] = 0x00.toByte()
        data[offset] = 0x01.toByte()

        return data
    }

    suspend fun measurePing(hostOrIp: String, timeoutMs: Int = 3500): Long? = withContext(Dispatchers.IO) {
        // Проверяем по очереди два надежных домена
        val testDomains = listOf("google.com", "cloudflare.com")
        for (domain in testDomains) {
            val ping = probeDomain(hostOrIp, domain, timeoutMs)
            if (ping != null) {
                return@withContext ping
            }
        }
        null
    }

    private fun probeDomain(hostOrIp: String, domain: String, timeoutMs: Int): Long? {
        var socket: DatagramSocket? = null
        return try {
            val address = getOrResolveAddress(hostOrIp) ?: return null

            val txId = txIdCounter.incrementAndGet() and 0xFFFF
            val queryBytes = buildQueryPacket(domain, txId)

            val startTime = System.currentTimeMillis()
            socket = DatagramSocket()
            vpnService.protect(socket)
            socket.soTimeout = timeoutMs

            val packet = DatagramPacket(queryBytes, queryBytes.size, address, 53)
            socket.send(packet)

            val buffer = ByteArray(512)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)

            val elapsed = System.currentTimeMillis() - startTime
            val expectedHigh = ((txId ushr 8) and 0xFF).toByte()
            val expectedLow = (txId and 0xFF).toByte()

            if (response.length >= 2 &&
                buffer[0] == expectedHigh &&
                buffer[1] == expectedLow
            ) {
                elapsed
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    private fun getOrResolveAddress(hostOrIp: String): InetAddress? {
        if (hostOrIp == lastResolvedHost && lastResolvedAddress != null) {
            return lastResolvedAddress
        }
        return try {
            val addr = InetAddress.getByName(hostOrIp)
            lastResolvedHost = hostOrIp
            lastResolvedAddress = addr
            addr
        } catch (_: Exception) {
            null
        }
    }
}
