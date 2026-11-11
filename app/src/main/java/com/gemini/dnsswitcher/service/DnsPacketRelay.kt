package com.gemini.dnsswitcher.service

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Высокопроизводительный двусторонний ретранслятор DNS с локальным LRU-кэшем (0 ms latency).
 */
class DnsPacketRelay(
    private val vpnService: VpnService,
    private val vpnInterface: ParcelFileDescriptor
) {
    private val tag = "DnsPacketRelay"

    @Volatile
    private var isRunning = false

    @Volatile
    private var currentUpstreamHost: String = "1.1.1.1"

    @Volatile
    private var currentUpstreamAddress: InetAddress? = null

    private var upstreamSocket: DatagramSocket? = null
    private var tunReaderThread: Thread? = null
    private var socketReceiverThread: Thread? = null

    private var tunOutputStream: FileOutputStream? = null

    private val pendingQueries = ConcurrentHashMap<Int, PendingQuery>()
    
    // Кэш ответов DNS: хеш запроса (без Transaction ID) -> (Ответные байты, время истечения TTL)
    private val responseCache = ConcurrentHashMap<ByteBuffer, CachedResponse>()

    @Volatile
    private var lastSuccessfulResponseTime = 0L

    fun getLastSuccessfulTrafficTime(): Long = lastSuccessfulResponseTime

    data class PendingQuery(
        val clientIp: ByteArray,
        val clientPort: Int,
        val ipId: Short,
        val queryKey: ByteBuffer?,
        val timestamp: Long
    )

    data class CachedResponse(
        val dnsPayload: ByteArray,
        val expiresAt: Long
    )

    fun setUpstream(hostOrIp: String) {
        currentUpstreamHost = hostOrIp
        // При смене DNS очищаем кэш, чтобы не возвращать старые IP
        responseCache.clear()
        Thread {
            try {
                currentUpstreamAddress = InetAddress.getByName(hostOrIp)
                Log.d(tag, "Upstream DNS: $hostOrIp -> ${currentUpstreamAddress?.hostAddress}")
            } catch (e: Exception) {
                Log.e(tag, "Ошибка разрешения upstream DNS $hostOrIp: ${e.message}")
            }
        }.start()
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        try {
            val socket = DatagramSocket()
            vpnService.protect(socket)
            upstreamSocket = socket
            tunOutputStream = FileOutputStream(vpnInterface.fileDescriptor)

            startTunReader()
            startSocketReceiver()
        } catch (e: Exception) {
            Log.e(tag, "Не удалось запустить DnsPacketRelay: ${e.message}", e)
        }
    }

    fun stop() {
        isRunning = false
        tunReaderThread?.interrupt()
        socketReceiverThread?.interrupt()
        tunReaderThread = null
        socketReceiverThread = null

        try {
            upstreamSocket?.close()
            upstreamSocket = null
        } catch (_: Exception) {}

        try {
            tunOutputStream?.close()
            tunOutputStream = null
        } catch (_: Exception) {}

        pendingQueries.clear()
        responseCache.clear()
    }

    private fun startTunReader() {
        tunReaderThread = Thread {
            val inputStream = FileInputStream(vpnInterface.fileDescriptor)
            val buffer = ByteArray(32767)

            while (isRunning && !Thread.interrupted()) {
                try {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead >= 28) { // 20 байт IPv4 + 8 байт UDP
                        handleTunPacket(buffer, bytesRead)
                    } else if (bytesRead < 0) {
                        break
                    }
                } catch (e: Exception) {
                    if (!isRunning) break
                }
            }
        }.apply {
            name = "TunReaderThread"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun handleTunPacket(buffer: ByteArray, length: Int) {
        val version = (buffer[0].toInt() and 0xF0) ushr 4
        if (version != 4) return

        val ihl = (buffer[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return

        val protocol = buffer[9].toInt() and 0xFF
        if (protocol != 17) return // UDP

        val srcPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
        val dstPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)

        if (dstPort != 53) return

        val udpLen = ((buffer[ihl + 4].toInt() and 0xFF) shl 8) or (buffer[ihl + 5].toInt() and 0xFF)
        val dnsPayloadLen = udpLen - 8
        if (dnsPayloadLen < 2 || length < ihl + 8 + dnsPayloadLen) return

        val dnsOffset = ihl + 8
        val txId = ((buffer[dnsOffset].toInt() and 0xFF) shl 8) or (buffer[dnsOffset + 1].toInt() and 0xFF)
        val ipId = (((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)).toShort()

        val clientIp = ByteArray(4)
        System.arraycopy(buffer, 12, clientIp, 0, 4)

        // Ключ для кэша: тело DNS-запроса без первых 2 байтов (Transaction ID)
        val queryKey = if (dnsPayloadLen > 2) {
            val keyBytes = ByteArray(dnsPayloadLen - 2)
            System.arraycopy(buffer, dnsOffset + 2, keyBytes, 0, dnsPayloadLen - 2)
            ByteBuffer.wrap(keyBytes)
        } else null

        // Проверяем локальный кэш
        val now = System.currentTimeMillis()
        if (queryKey != null) {
            val cached = responseCache[queryKey]
            if (cached != null && cached.expiresAt > now) {
                // МГНОВЕННЫЙ ОТВЕТ ИЗ КЭША (0 ms!)
                val answerPayload = cached.dnsPayload.clone()
                // Подставляем текущий Transaction ID клиента
                answerPayload[0] = (txId ushr 8).toByte()
                answerPayload[1] = (txId and 0xFF).toByte()

                val pending = PendingQuery(clientIp, srcPort, ipId, null, now)
                tunOutputStream?.let { sendResponseToTun(it, answerPayload, answerPayload.size, pending) }
                return
            }
        }

        pruneExpiredQueries()
        pendingQueries[txId] = PendingQuery(
            clientIp = clientIp,
            clientPort = srcPort,
            ipId = ipId,
            queryKey = queryKey,
            timestamp = now
        )

        val targetAddr = currentUpstreamAddress ?: return
        val socket = upstreamSocket ?: return

        try {
            val outgoingPacket = DatagramPacket(buffer, dnsOffset, dnsPayloadLen, targetAddr, 53)
            socket.send(outgoingPacket)
        } catch (e: Exception) {
            Log.e(tag, "Ошибка пересылки UDP DNS: ${e.message}")
        }
    }

    private fun startSocketReceiver() {
        socketReceiverThread = Thread {
            val socket = upstreamSocket ?: return@Thread
            val receiveBuffer = ByteArray(4096)
            val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)

            while (isRunning && !Thread.interrupted()) {
                try {
                    socket.receive(packet)
                    val len = packet.length
                    if (len >= 2) {
                        lastSuccessfulResponseTime = System.currentTimeMillis()
                        val txId = ((receiveBuffer[0].toInt() and 0xFF) shl 8) or (receiveBuffer[1].toInt() and 0xFF)
                        val pending = pendingQueries.remove(txId)
                        if (pending != null) {
                            // Сохраняем ответ в кэш на 60 секунд
                            if (pending.queryKey != null && len > 2) {
                                val cachedBytes = ByteArray(len)
                                System.arraycopy(receiveBuffer, 0, cachedBytes, 0, len)
                                responseCache[pending.queryKey] = CachedResponse(
                                    dnsPayload = cachedBytes,
                                    expiresAt = System.currentTimeMillis() + 60_000L
                                )
                            }
                            tunOutputStream?.let { sendResponseToTun(it, receiveBuffer, len, pending) }
                        }
                    }
                } catch (e: Exception) {
                    if (!isRunning) break
                }
            }
        }.apply {
            name = "SocketReceiverThread"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun sendResponseToTun(
        outputStream: FileOutputStream,
        dnsData: ByteArray,
        dnsLen: Int,
        pending: PendingQuery
    ) {
        val totalLen = 20 + 8 + dnsLen
        val packet = ByteArray(totalLen)

        // 1. IPv4 Header (20 байт)
        packet[0] = 0x45.toByte() // Version 4, IHL 5
        packet[1] = 0x00.toByte()
        packet[2] = ((totalLen ushr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = ((pending.ipId.toInt() ushr 8) and 0xFF).toByte()
        packet[5] = (pending.ipId.toInt() and 0xFF).toByte()
        packet[6] = 0x40.toByte() // Don't Fragment
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()   // TTL
        packet[9] = 17.toByte()   // UDP
        packet[10] = 0.toByte()
        packet[11] = 0.toByte()

        // Source IP: 10.0.0.1 (Виртуальный DNS)
        packet[12] = 10.toByte()
        packet[13] = 0.toByte()
        packet[14] = 0.toByte()
        packet[15] = 1.toByte()

        // Destination IP: исходный IP клиента (10.0.0.2)
        System.arraycopy(pending.clientIp, 0, packet, 16, 4)

        // Контрольная сумма IP
        val ipChecksum = calculateIpChecksum(packet, 0, 20)
        packet[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // 2. UDP Header (8 байт)
        packet[20] = 0.toByte()   // Source Port: 53
        packet[21] = 53.toByte()
        packet[22] = ((pending.clientPort ushr 8) and 0xFF).toByte()
        packet[23] = (pending.clientPort and 0xFF).toByte()
        val udpLen = 8 + dnsLen
        packet[24] = ((udpLen ushr 8) and 0xFF).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        packet[26] = 0.toByte()   // 0x0000 = отключено для IPv4
        packet[27] = 0.toByte()

        // 3. DNS Payload
        System.arraycopy(dnsData, 0, packet, 28, dnsLen)

        try {
            synchronized(outputStream) {
                outputStream.write(packet, 0, totalLen)
                outputStream.flush()
            }
        } catch (_: Exception) {}
    }

    private fun calculateIpChecksum(buffer: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        for (i in 0 until length step 2) {
            val high = buffer[offset + i].toInt() and 0xFF
            val low = buffer[offset + i + 1].toInt() and 0xFF
            sum += (high shl 8) or low
            if (sum > 0xFFFF) {
                sum = (sum and 0xFFFF) + 1
            }
        }
        return sum.inv() and 0xFFFF
    }

    private fun pruneExpiredQueries() {
        if (pendingQueries.size > 200) {
            val now = System.currentTimeMillis()
            val it = pendingQueries.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (now - entry.value.timestamp > 3500) {
                    it.remove()
                }
            }
        }
    }
}
