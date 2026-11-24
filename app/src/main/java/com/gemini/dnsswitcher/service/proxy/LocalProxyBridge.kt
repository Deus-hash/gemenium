package com.gemini.dnsswitcher.service.proxy

import android.net.VpnService
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Локальный мост-прокси на 127.0.0.1:<port>.
 * Поддерживает любые сайты, Google-сервисы, веб-приложения и внешние ссылки.
 */
class LocalProxyBridge(
    private val vpnService: VpnService,
    private val proxyConfig: ProxyConfig
) {
    private val tag = "LocalProxyBridge"
    private val ipv4Regex = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    @Volatile
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val detectedTypeDeferred = CompletableDeferred<ProxyType>()

    @Volatile
    var actualPort: Int = 18080
        private set

    @Volatile
    private var cachedProxyAddresses: List<InetAddress> = emptyList()
    @Volatile
    private var cachedProxyResolvedAt: Long = 0L

    fun start(): Int {
        if (isRunning) return actualPort
        isRunning = true

        // 1. Немедленно поднимаем ServerSocket с SO_REUSEADDR до установки VPN-интерфейса
        val ss = ServerSocket()
        ss.reuseAddress = true
        val loopback = InetAddress.getByName("127.0.0.1")
        try {
            ss.bind(InetSocketAddress(loopback, 18080), 256)
            actualPort = 18080
        } catch (_: Exception) {
            ss.bind(InetSocketAddress(loopback, 0), 256)
            actualPort = ss.localPort
        }
        serverSocket = ss
        Log.d(tag, "Локальный прокси-мост запущен на 127.0.0.1:$actualPort")

        // 2. Асинхронно определяем протокол прокси (если AUTO) и кэшируем IP-адреса
        scope.launch {
            val type = if (proxyConfig.type == ProxyType.AUTO) {
                ProxyDetector.detectType(proxyConfig, vpnService)
            } else {
                proxyConfig.type
            }
            detectedTypeDeferred.complete(type)
            Log.d(tag, "Протокол прокси определен: $type")
        }

        // 3. Цикл приема подключений
        scope.launch {
            try {
                while (isRunning) {
                    val clientSocket = ss.accept()
                    scope.launch {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(tag, "Ошибка в цикле ServerSocket: ${e.message}")
                }
            }
        }

        return actualPort
    }

    fun stop() {
        isRunning = false
        if (!detectedTypeDeferred.isCompleted) {
            detectedTypeDeferred.cancel()
        }
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (_: Exception) {}
        scope.cancel()
    }

    private suspend fun handleClient(clientSocket: Socket) = withContext(Dispatchers.IO) {
        var remoteSocket: Socket? = null
        try {
            clientSocket.tcpNoDelay = true
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            val requestLine = readLine(clientIn) ?: return@withContext
            if (requestLine.isEmpty()) return@withContext

            val parts = requestLine.split(" ")
            if (parts.size < 2) return@withContext

            val method = parts[0]
            val uri = parts[1]
            val version = parts.getOrElse(2) { "HTTP/1.1" }

            val targetHost: String
            val targetPort: Int

            if (method.equals("CONNECT", ignoreCase = true)) {
                val colon = uri.lastIndexOf(':')
                if (colon != -1) {
                    targetHost = uri.substring(0, colon)
                    targetPort = uri.substring(colon + 1).toIntOrNull() ?: 443
                } else {
                    targetHost = uri
                    targetPort = 443
                }

                while (true) {
                    val line = readLine(clientIn) ?: break
                    if (line.isEmpty()) break
                }

                val type = try { detectedTypeDeferred.await() } catch (_: Exception) { ProxyType.SOCKS5 }
                remoteSocket = connectToUpstream(targetHost, targetPort, type)
                if (remoteSocket == null) {
                    clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    clientOut.flush()
                    return@withContext
                }

                remoteSocket.tcpNoDelay = true
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                clientOut.flush()

                val remoteIn = remoteSocket.getInputStream()
                val remoteOut = remoteSocket.getOutputStream()

                val job1 = launch(Dispatchers.IO) {
                    pipe(clientIn, remoteOut, remoteSocket, clientSocket)
                }
                val job2 = launch(Dispatchers.IO) {
                    pipe(remoteIn, clientOut, clientSocket, remoteSocket)
                }
                joinAll(job1, job2)
            } else {
                var hostHeader: String? = null
                var portHeader = 80
                val headers = mutableListOf<String>()

                while (true) {
                    val line = readLine(clientIn) ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Proxy-Connection:", ignoreCase = true)) continue
                    headers.add(line)
                    if (line.startsWith("Host:", ignoreCase = true)) {
                        val h = line.substring(5).trim()
                        val colon = h.lastIndexOf(':')
                        if (colon != -1) {
                            hostHeader = h.substring(0, colon)
                            portHeader = h.substring(colon + 1).toIntOrNull() ?: 80
                        } else {
                            hostHeader = h
                            portHeader = 80
                        }
                    }
                }

                val (uriHost, uriPort, relativePath) = parseUri(uri)
                targetHost = hostHeader ?: uriHost
                targetPort = if (hostHeader != null) portHeader else uriPort

                val type = try { detectedTypeDeferred.await() } catch (_: Exception) { ProxyType.SOCKS5 }
                remoteSocket = connectToUpstream(targetHost, targetPort, type)
                if (remoteSocket == null) {
                    clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    clientOut.flush()
                    return@withContext
                }

                remoteSocket.tcpNoDelay = true
                val remoteOut = remoteSocket.getOutputStream()
                val remoteIn = remoteSocket.getInputStream()

                // Пересылаем запрос с относительным путем (т.к. мы уже протуннелированы к targetHost:targetPort)
                remoteOut.write("$method $relativePath $version\r\n".toByteArray(Charsets.US_ASCII))
                for (h in headers) {
                    remoteOut.write("$h\r\n".toByteArray(Charsets.US_ASCII))
                }
                remoteOut.write("\r\n".toByteArray(Charsets.US_ASCII))
                remoteOut.flush()

                val job1 = launch(Dispatchers.IO) {
                    pipe(clientIn, remoteOut, remoteSocket, clientSocket)
                }
                val job2 = launch(Dispatchers.IO) {
                    pipe(remoteIn, clientOut, clientSocket, remoteSocket)
                }
                joinAll(job1, job2)
            }
        } catch (_: Exception) {
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { remoteSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun getProxyAddresses(): List<InetAddress> {
        val now = System.currentTimeMillis()
        if (cachedProxyAddresses.isNotEmpty() && now - cachedProxyResolvedAt < 60_000L) {
            return cachedProxyAddresses
        }
        return try {
            val resolved = ProxyDetector.resolveAddresses(proxyConfig.host, vpnService)
            cachedProxyAddresses = resolved
            cachedProxyResolvedAt = now
            resolved
        } catch (_: Exception) {
            cachedProxyAddresses
        }
    }

    private fun connectToUpstream(targetHost: String, targetPort: Int, type: ProxyType): Socket? {
        val addresses = getProxyAddresses()
        if (addresses.isEmpty()) {
            Log.e(tag, "Не удалось разрешить адрес прокси: ${proxyConfig.host}")
            return null
        }

        for (addr in addresses) {
            val socket = Socket()
            vpnService.protect(socket)
            socket.soTimeout = 12000

            try {
                socket.connect(InetSocketAddress(addr, proxyConfig.port), 8000)

                val ok = if (type == ProxyType.SOCKS5) {
                    handshakeSocks5(socket, targetHost, targetPort)
                } else {
                    handshakeHttp(socket, targetHost, targetPort)
                }

                if (ok) {
                    socket.soTimeout = 0
                    return socket
                } else {
                    socket.close()
                }
            } catch (e: Exception) {
                Log.e(tag, "Ошибка подключения к ${addr.hostAddress}:${proxyConfig.port} -> ${e.message}")
                try { socket.close() } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun handshakeSocks5(socket: Socket, targetHost: String, targetPort: Int): Boolean {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        out.flush()

        val greetingResp = ByteArray(2)
        readFully(input, greetingResp)
        if (greetingResp[0] != 0x05.toByte()) return false

        val authMethod = greetingResp[1].toInt() and 0xFF
        if (authMethod == 0x02) {
            val uBytes = (proxyConfig.username ?: "").toByteArray(Charsets.UTF_8)
            val pBytes = (proxyConfig.password ?: "").toByteArray(Charsets.UTF_8)

            val authPacket = ByteArray(3 + uBytes.size + pBytes.size)
            authPacket[0] = 0x01
            authPacket[1] = uBytes.size.toByte()
            System.arraycopy(uBytes, 0, authPacket, 2, uBytes.size)

            val pOffset = 2 + uBytes.size
            authPacket[pOffset] = pBytes.size.toByte()
            System.arraycopy(pBytes, 0, authPacket, pOffset + 1, pBytes.size)

            out.write(authPacket)
            out.flush()

            val authResp = ByteArray(2)
            readFully(input, authResp)
            if (authResp[1] != 0x00.toByte()) {
                Log.e(tag, "SOCKS5: Ошибка авторизации")
                return false
            }
        } else if (authMethod != 0x00) {
            return false
        }

        val cmdPacket: ByteArray
        if (ipv4Regex.matches(targetHost)) {
            val ipBytes = InetAddress.getByName(targetHost).address
            cmdPacket = ByteArray(10)
            cmdPacket[0] = 0x05
            cmdPacket[1] = 0x01
            cmdPacket[2] = 0x00
            cmdPacket[3] = 0x01 // IPv4
            System.arraycopy(ipBytes, 0, cmdPacket, 4, 4)
            cmdPacket[8] = ((targetPort ushr 8) and 0xFF).toByte()
            cmdPacket[9] = (targetPort and 0xFF).toByte()
        } else {
            val hostBytes = targetHost.toByteArray(Charsets.US_ASCII)
            cmdPacket = ByteArray(7 + hostBytes.size)
            cmdPacket[0] = 0x05
            cmdPacket[1] = 0x01
            cmdPacket[2] = 0x00
            cmdPacket[3] = 0x03 // Domain
            cmdPacket[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, cmdPacket, 5, hostBytes.size)
            val portOffset = 5 + hostBytes.size
            cmdPacket[portOffset] = ((targetPort ushr 8) and 0xFF).toByte()
            cmdPacket[portOffset + 1] = (targetPort and 0xFF).toByte()
        }

        out.write(cmdPacket)
        out.flush()

        val respHeader = ByteArray(4)
        readFully(input, respHeader)
        if (respHeader[1] != 0x00.toByte()) {
            Log.e(tag, "SOCKS5: Ошибка соединения с $targetHost (код ${respHeader[1]})")
            return false
        }

        val atyp = respHeader[3].toInt() and 0xFF
        val skipLen = when (atyp) {
            0x01 -> 4 + 2
            0x04 -> 16 + 2
            0x03 -> {
                val len = input.read()
                if (len < 0) throw EOFException()
                len + 2
            }
            else -> 6
        }
        val skipBuf = ByteArray(skipLen)
        readFully(input, skipBuf)

        return true
    }

    private fun handshakeHttp(socket: Socket, targetHost: String, targetPort: Int): Boolean {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        val sb = StringBuilder()
        sb.append("CONNECT $targetHost:$targetPort HTTP/1.1\r\n")
        sb.append("Host: $targetHost:$targetPort\r\n")

        if (proxyConfig.hasAuth) {
            val creds = "${proxyConfig.username}:${proxyConfig.password}"
            val b64 = Base64.encodeToString(creds.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            sb.append("Proxy-Authorization: Basic $b64\r\n")
        }
        sb.append("Proxy-Connection: Keep-Alive\r\n\r\n")

        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.flush()

        val statusLine = readLine(input) ?: return false
        val parts = statusLine.split(" ")
        if (parts.size >= 2 && (parts[1] == "200" || parts[1].startsWith("2"))) {
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
            }
            return true
        }
        return false
    }

    private fun pipe(
        input: InputStream,
        output: OutputStream,
        socketToShutdown: Socket,
        otherSocketToClose: Socket
    ) {
        val buffer = ByteArray(32768)
        try {
            while (isRunning) {
                val bytesRead = input.read(buffer)
                if (bytesRead <= 0) break
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { socketToShutdown.shutdownOutput() } catch (_: Exception) {}
            try { otherSocketToClose.close() } catch (_: Exception) {}
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        var bytesRead = 0
        while (bytesRead < length) {
            val count = input.read(buffer, offset + bytesRead, length - bytesRead)
            if (count < 0) throw EOFException("Преждевременный конец потока")
            bytesRead += count
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (sb.isEmpty()) return null
                break
            }
            val c = b.toChar()
            if (c == '\n') break
            if (c != '\r') sb.append(c)
        }
        return sb.toString()
    }

    private fun parseUri(uri: String): Triple<String, Int, String> {
        return try {
            val u = java.net.URI(uri)
            val host = u.host ?: ""
            val port = if (u.port != -1) u.port else 80
            val path = buildString {
                append(if (u.rawPath.isNullOrEmpty()) "/" else u.rawPath)
                if (!u.rawQuery.isNullOrEmpty()) append("?${u.rawQuery}")
            }
            Triple(host, port, path)
        } catch (_: Exception) {
            Triple("", 80, uri)
        }
    }
}
