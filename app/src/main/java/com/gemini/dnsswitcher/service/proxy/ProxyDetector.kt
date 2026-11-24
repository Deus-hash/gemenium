package com.gemini.dnsswitcher.service.proxy

import android.net.VpnService
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.InputStream
import java.net.*
import java.util.concurrent.atomic.AtomicInteger

data class ProxyCheckResult(
    val success: Boolean,
    val pingMs: Long? = null,
    val detectedType: ProxyType = ProxyType.AUTO,
    val resolvedIp: String? = null,
    val authOk: Boolean = false,
    val errorMessage: String? = null
)

object ProxyDetector {

    private val ipv4Regex = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val txIdCounter = AtomicInteger(0x2000)

    /**
     * Полная диагностика прокси:
     * 1) Разрешение домена (системный DNS + резервный UDP 8.8.8.8 / 77.88.8.8, приоритет IPv4)
     * 2) TCP-соединение и измерение реального пинга
     * 3) Автоопределение протокола (SOCKS5 / HTTP)
     * 4) Проверка логина/пароля и тестового выхода к google.com:443
     */
    suspend fun diagnoseProxy(
        config: ProxyConfig,
        vpnService: VpnService? = null,
        timeoutMs: Int = 5000
    ): ProxyCheckResult = withContext(Dispatchers.IO) {
        val addresses = try {
            resolveAddresses(config.host, vpnService)
        } catch (e: Exception) {
            return@withContext ProxyCheckResult(
                success = false,
                errorMessage = "Не удалось найти адрес «${config.host}» (ошибка DNS)"
            )
        }

        if (addresses.isEmpty()) {
            return@withContext ProxyCheckResult(
                success = false,
                errorMessage = "Не удалось разрешить домен «${config.host}»"
            )
        }

        var connectedAddr: InetAddress? = null
        var tcpPingMs: Long? = null
        var lastError: Exception? = null

        for (addr in addresses) {
            var s: Socket? = null
            try {
                val start = System.currentTimeMillis()
                s = Socket()
                vpnService?.protect(s)
                s.connect(InetSocketAddress(addr, config.port), timeoutMs)
                tcpPingMs = System.currentTimeMillis() - start
                connectedAddr = addr
                break
            } catch (e: Exception) {
                lastError = e
            } finally {
                try { s?.close() } catch (_: Exception) {}
            }
        }

        if (connectedAddr == null || tcpPingMs == null) {
            val targetDisplay = "${addresses.first().hostAddress ?: config.host}:${config.port}"
            val msg = when (lastError) {
                is SocketTimeoutException ->
                    "Таймаут подключения к $targetDisplay — сервер выключен или заблокирован провайдером"
                is ConnectException ->
                    "Соединение отклонено $targetDisplay — порт закрыт или сервер недоступен"
                is NoRouteToHostException ->
                    "Нет маршрута к $targetDisplay — проверьте сеть"
                else ->
                    "Ошибка сети ($targetDisplay): ${lastError?.message ?: "неизвестно"}"
            }
            return@withContext ProxyCheckResult(
                success = false,
                resolvedIp = addresses.first().hostAddress,
                errorMessage = msg
            )
        }

        val resolvedIp = connectedAddr.hostAddress

        // Если явно задан HTTP
        if (config.type == ProxyType.HTTP) {
            return@withContext verifyHttp(connectedAddr, config, tcpPingMs, resolvedIp, vpnService, timeoutMs)
        }

        // Проверяем SOCKS5 (работает и для AUTO, и для SOCKS5)
        val socksResult = verifySocks5(connectedAddr, config, tcpPingMs, resolvedIp, vpnService, timeoutMs)
        if (socksResult != null) {
            return@withContext socksResult
        }

        // Если AUTO и SOCKS5 не подошел — пробуем HTTP CONNECT
        if (config.type == ProxyType.AUTO) {
            return@withContext verifyHttp(connectedAddr, config, tcpPingMs, resolvedIp, vpnService, timeoutMs)
        }

        ProxyCheckResult(
            success = false,
            pingMs = tcpPingMs,
            detectedType = config.type,
            resolvedIp = resolvedIp,
            errorMessage = "Сервер не отвечает как SOCKS5 на порту ${config.port}"
        )
    }

    /**
     * Быстрое автоопределение протокола для LocalProxyBridge
     */
    suspend fun detectType(
        config: ProxyConfig,
        vpnService: VpnService? = null,
        timeoutMs: Int = 2500
    ): ProxyType = withContext(Dispatchers.IO) {
        if (config.type != ProxyType.AUTO) {
            return@withContext config.type
        }

        val addresses = try {
            resolveAddresses(config.host, vpnService)
        } catch (_: Exception) {
            return@withContext ProxyType.SOCKS5
        }

        val addr = addresses.firstOrNull() ?: return@withContext ProxyType.SOCKS5

        if (probeSocks5(addr, config.port, vpnService, timeoutMs)) {
            return@withContext ProxyType.SOCKS5
        }
        if (probeHttp(addr, config.port, vpnService, timeoutMs)) {
            return@withContext ProxyType.HTTP
        }
        ProxyType.SOCKS5
    }

    /**
     * Разрешает хост в список адресов с приоритетом IPv4 и резервным запросом к 8.8.8.8 / 77.88.8.8
     */
    fun resolveAddresses(host: String, vpnService: VpnService? = null): List<InetAddress> {
        val cleanHost = host.trim()
        if (ipv4Regex.matches(cleanHost) || cleanHost.contains(':')) {
            return listOf(InetAddress.getByName(cleanHost))
        }

        val systemResolved = try {
            InetAddress.getAllByName(cleanHost).toList()
        } catch (_: Exception) {
            emptyList()
        }

        if (systemResolved.isNotEmpty()) {
            // Сортируем так, чтобы IPv4 шел первым
            return systemResolved.sortedBy { if (it is Inet4Address) 0 else 1 }
        }

        // Резервное разрешение напрямую по UDP 53 (если системный DNS провайдера не отвечает)
        val fallbackDnsServers = listOf("8.8.8.8", "77.88.8.8")
        for (dnsIp in fallbackDnsServers) {
            val resolved = resolveViaUdpDns(cleanHost, dnsIp, vpnService)
            if (resolved.isNotEmpty()) {
                return resolved
            }
        }

        throw UnknownHostException("Unable to resolve $cleanHost")
    }

    private fun verifySocks5(
        addr: InetAddress,
        config: ProxyConfig,
        tcpPingMs: Long,
        resolvedIp: String?,
        vpnService: VpnService?,
        timeoutMs: Int
    ): ProxyCheckResult? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            vpnService?.protect(socket)
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(addr, config.port), timeoutMs)

            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            // SOCKS5 greeting
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            out.flush()

            val firstByte = input.read()
            if (firstByte != 0x05) {
                // Если сервер ответил текстовым HTTP ("H" = 0x48), сразу отдаём null чтобы проверился HTTP
                return null
            }
            val method = input.read()
            if (method == -1) return null

            if (method == 0x02) {
                if (!config.hasAuth) {
                    return ProxyCheckResult(
                        success = false,
                        pingMs = tcpPingMs,
                        detectedType = ProxyType.SOCKS5,
                        resolvedIp = resolvedIp,
                        errorMessage = "Прокси требует логин и пароль (формат: логин:пароль@адрес:порт)"
                    )
                }

                val uBytes = (config.username ?: "").toByteArray(Charsets.UTF_8)
                val pBytes = (config.password ?: "").toByteArray(Charsets.UTF_8)
                val authPacket = ByteArray(3 + uBytes.size + pBytes.size)
                authPacket[0] = 0x01
                authPacket[1] = uBytes.size.toByte()
                System.arraycopy(uBytes, 0, authPacket, 2, uBytes.size)
                val pOff = 2 + uBytes.size
                authPacket[pOff] = pBytes.size.toByte()
                System.arraycopy(pBytes, 0, authPacket, pOff + 1, pBytes.size)

                out.write(authPacket)
                out.flush()

                val authResp = ByteArray(2)
                readFully(input, authResp)
                if (authResp[1] != 0x00.toByte()) {
                    return ProxyCheckResult(
                        success = false,
                        pingMs = tcpPingMs,
                        detectedType = ProxyType.SOCKS5,
                        resolvedIp = resolvedIp,
                        authOk = false,
                        errorMessage = "Неверный логин или пароль прокси (SOCKS5)"
                    )
                }
            } else if (method != 0x00) {
                return ProxyCheckResult(
                    success = false,
                    pingMs = tcpPingMs,
                    detectedType = ProxyType.SOCKS5,
                    resolvedIp = resolvedIp,
                    errorMessage = "Прокси предложил неподдерживаемый метод авторизации ($method)"
                )
            }

            // Тестовый CONNECT на www.google.com:443
            val testHost = "www.google.com".toByteArray(Charsets.US_ASCII)
            val cmd = ByteArray(7 + testHost.size)
            cmd[0] = 0x05
            cmd[1] = 0x01
            cmd[2] = 0x00
            cmd[3] = 0x03
            cmd[4] = testHost.size.toByte()
            System.arraycopy(testHost, 0, cmd, 5, testHost.size)
            val pOff = 5 + testHost.size
            cmd[pOff] = 0x01 // 443 >> 8 = 1
            cmd[pOff + 1] = 0xBB.toByte() // 443 & 0xFF = 187

            out.write(cmd)
            out.flush()

            val respHeader = ByteArray(4)
            readFully(input, respHeader)
            if (respHeader[1] != 0x00.toByte()) {
                val errCode = respHeader[1].toInt() and 0xFF
                return ProxyCheckResult(
                    success = false,
                    pingMs = tcpPingMs,
                    detectedType = ProxyType.SOCKS5,
                    resolvedIp = resolvedIp,
                    authOk = true,
                    errorMessage = "Логин принят, но прокси не смог открыть google.com (код SOCKS5: $errCode)"
                )
            }

            ProxyCheckResult(
                success = true,
                pingMs = tcpPingMs,
                detectedType = ProxyType.SOCKS5,
                resolvedIp = resolvedIp,
                authOk = true
            )
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun verifyHttp(
        addr: InetAddress,
        config: ProxyConfig,
        tcpPingMs: Long,
        resolvedIp: String?,
        vpnService: VpnService?,
        timeoutMs: Int
    ): ProxyCheckResult {
        var socket: Socket? = null
        return try {
            socket = Socket()
            vpnService?.protect(socket)
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(addr, config.port), timeoutMs)

            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            val sb = StringBuilder()
            sb.append("CONNECT www.google.com:443 HTTP/1.1\r\n")
            sb.append("Host: www.google.com:443\r\n")
            if (config.hasAuth) {
                val creds = "${config.username}:${config.password}"
                val b64 = Base64.encodeToString(creds.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                sb.append("Proxy-Authorization: Basic $b64\r\n")
            }
            sb.append("Proxy-Connection: Keep-Alive\r\n\r\n")

            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            out.flush()

            val statusLine = readLine(input)
            if (statusLine.isNullOrEmpty() || !statusLine.startsWith("HTTP/")) {
                return ProxyCheckResult(
                    success = false,
                    pingMs = tcpPingMs,
                    detectedType = ProxyType.AUTO,
                    resolvedIp = resolvedIp,
                    errorMessage = "Сервер на порту ${config.port} не ответил ни как SOCKS5, ни как HTTP-прокси"
                )
            }

            val parts = statusLine.split(" ")
            val code = parts.getOrNull(1)?.toIntOrNull() ?: 0

            when {
                code in 200..299 -> ProxyCheckResult(
                    success = true,
                    pingMs = tcpPingMs,
                    detectedType = ProxyType.HTTP,
                    resolvedIp = resolvedIp,
                    authOk = true
                )
                code == 407 -> ProxyCheckResult(
                    success = false,
                    pingMs = tcpPingMs,
                    detectedType = ProxyType.HTTP,
                    resolvedIp = resolvedIp,
                    authOk = false,
                    errorMessage = if (config.hasAuth) {
                        "Неверный логин или пароль прокси (HTTP 407)"
                    } else {
                        "Прокси требует логин и пароль (HTTP 407)"
                    }
                )
                else -> ProxyCheckResult(
                    success = false,
                    pingMs = tcpPingMs,
                    detectedType = ProxyType.HTTP,
                    resolvedIp = resolvedIp,
                    errorMessage = "Прокси отклонил тестовый запрос: $statusLine"
                )
            }
        } catch (e: SocketTimeoutException) {
            ProxyCheckResult(
                success = false,
                pingMs = tcpPingMs,
                detectedType = ProxyType.AUTO,
                resolvedIp = resolvedIp,
                errorMessage = "Таймаут ответа от прокси при проверке протокола"
            )
        } catch (e: Exception) {
            ProxyCheckResult(
                success = false,
                pingMs = tcpPingMs,
                detectedType = ProxyType.AUTO,
                resolvedIp = resolvedIp,
                errorMessage = "Ошибка проверки: ${e.message}"
            )
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun probeSocks5(addr: InetAddress, port: Int, vpnService: VpnService?, timeoutMs: Int): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            vpnService?.protect(socket)
            socket.soTimeout = minOf(timeoutMs, 1500)
            socket.connect(InetSocketAddress(addr, port), timeoutMs)

            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            out.flush()

            val resp = ByteArray(2)
            readFully(input, resp)
            resp[0] == 0x05.toByte() && (resp[1] == 0x00.toByte() || resp[1] == 0x02.toByte())
        } catch (_: Exception) {
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun probeHttp(addr: InetAddress, port: Int, vpnService: VpnService?, timeoutMs: Int): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            vpnService?.protect(socket)
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(addr, port), timeoutMs)

            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write("CONNECT www.google.com:443 HTTP/1.1\r\nHost: www.google.com:443\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()

            val buf = ByteArray(16)
            val read = input.read(buf)
            read >= 5 && String(buf, 0, read, Charsets.US_ASCII).startsWith("HTTP/")
        } catch (_: Exception) {
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun resolveViaUdpDns(host: String, dnsIp: String, vpnService: VpnService?): List<InetAddress> {
        var socket: DatagramSocket? = null
        return try {
            val txId = txIdCounter.incrementAndGet() and 0xFFFF
            val query = buildDnsAQuery(host, txId)

            socket = DatagramSocket()
            vpnService?.protect(socket)
            socket.soTimeout = 2000

            val dnsAddr = InetAddress.getByName(dnsIp)
            socket.send(DatagramPacket(query, query.size, dnsAddr, 53))

            val buf = ByteArray(512)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)

            parseDnsAResponse(host, buf, resp.length, txId)
        } catch (_: Exception) {
            emptyList()
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun buildDnsAQuery(domain: String, txId: Int): ByteArray {
        val parts = domain.split(".")
        var totalLen = 12
        for (p in parts) totalLen += 1 + p.length
        totalLen += 5 // null terminator + QTYPE(2) + QCLASS(2)

        val data = ByteArray(totalLen)
        data[0] = ((txId ushr 8) and 0xFF).toByte()
        data[1] = (txId and 0xFF).toByte()
        data[2] = 0x01.toByte()
        data[5] = 0x01.toByte()

        var offset = 12
        for (p in parts) {
            val bytes = p.toByteArray(Charsets.US_ASCII)
            data[offset++] = bytes.size.toByte()
            System.arraycopy(bytes, 0, data, offset, bytes.size)
            offset += bytes.size
        }
        data[offset++] = 0x00
        data[offset++] = 0x00
        data[offset++] = 0x01 // A
        data[offset++] = 0x00
        data[offset] = 0x01 // IN
        return data
    }

    private fun parseDnsAResponse(host: String, data: ByteArray, len: Int, txId: Int): List<InetAddress> {
        if (len < 12) return emptyList()
        val rTxId = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        if (rTxId != txId) return emptyList()

        val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)

        var offset = 12
        for (i in 0 until qdCount) {
            offset = skipDnsName(data, len, offset) + 4
            if (offset > len) return emptyList()
        }

        val result = mutableListOf<InetAddress>()
        for (i in 0 until anCount) {
            offset = skipDnsName(data, len, offset)
            if (offset + 10 > len) break
            val type = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val rdLen = ((data[offset + 8].toInt() and 0xFF) shl 8) or (data[offset + 9].toInt() and 0xFF)
            offset += 10
            if (offset + rdLen > len) break
            if (type == 1 && rdLen == 4) {
                val ipBytes = data.copyOfRange(offset, offset + 4)
                result.add(InetAddress.getByAddress(host, ipBytes))
            }
            offset += rdLen
        }
        return result
    }

    private fun skipDnsName(data: ByteArray, len: Int, start: Int): Int {
        var pos = start
        var steps = 0
        while (pos < len && steps < 64) {
            val b = data[pos].toInt() and 0xFF
            if (b == 0) return pos + 1
            if ((b and 0xC0) == 0xC0) return pos + 2
            pos += 1 + b
            steps++
        }
        return pos
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val c = input.read(buffer, read, buffer.size - read)
            if (c < 0) throw EOFException()
            read += c
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            val c = b.toChar()
            if (c == '\n') break
            if (c != '\r') sb.append(c)
        }
        return sb.toString()
    }
}
