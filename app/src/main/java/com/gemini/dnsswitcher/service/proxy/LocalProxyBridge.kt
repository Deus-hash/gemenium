package com.gemini.dnsswitcher.service.proxy

import android.net.VpnService
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Локальный мост-прокси на 127.0.0.1:18080.
 * Принимает входящие HTTP/HTTPS CONNECT запросы от системы,
 * авторизуется на удаленном сервере (SOCKS5 / HTTP с логином и паролем)
 * и пересылает трафик без разрыва шифрования (End-to-End TLS).
 */
class LocalProxyBridge(
    private val vpnService: VpnService,
    private val proxyConfig: ProxyConfig
) {
    private val tag = "LocalProxyBridge"

    @Volatile
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    var detectedType: ProxyType = proxyConfig.type

    val localPort = 18080

    fun start() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            // Если тип AUTO, определяем протокол
            if (detectedType == ProxyType.AUTO) {
                detectedType = ProxyDetector.detectType(proxyConfig, vpnService)
                Log.d(tag, "Автоопределение прокси: $detectedType")
            }

            try {
                serverSocket = ServerSocket(localPort, 128, InetAddress.getByName("127.0.0.1"))
                Log.d(tag, "Локальный прокси-мост запущен на 127.0.0.1:$localPort")

                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
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
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (_: Exception) {}
        scope.cancel()
    }

    private suspend fun handleClient(clientSocket: Socket) = withContext(Dispatchers.IO) {
        var remoteSocket: Socket? = null
        try {
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            // Считываем первую строку HTTP-запроса
            val requestLine = readLine(clientIn) ?: return@withContext
            if (requestLine.isEmpty()) return@withContext

            val parts = requestLine.split(" ")
            if (parts.size < 2) return@withContext

            val method = parts[0]
            val uri = parts[1]

            val targetHost: String
            val targetPort: Int

            if (method.equals("CONNECT", ignoreCase = true)) {
                // CONNECT gemini.google.com:443 HTTP/1.1
                val colon = uri.lastIndexOf(':')
                if (colon != -1) {
                    targetHost = uri.substring(0, colon)
                    targetPort = uri.substring(colon + 1).toIntOrNull() ?: 443
                } else {
                    targetHost = uri
                    targetPort = 443
                }

                // Читаем остальные заголовки до пустой строки \r\n
                while (true) {
                    val line = readLine(clientIn) ?: break
                    if (line.isEmpty()) break
                }

                remoteSocket = connectToUpstream(targetHost, targetPort)
                if (remoteSocket == null) {
                    clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    clientOut.flush()
                    return@withContext
                }

                // Сообщаем клиенту, что туннель установлен
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                clientOut.flush()

                // Двусторонняя перекачка данных
                val remoteIn = remoteSocket.getInputStream()
                val remoteOut = remoteSocket.getOutputStream()

                val job1 = launch { pipe(clientIn, remoteOut) }
                val job2 = launch { pipe(remoteIn, clientOut) }
                joinAll(job1, job2)

            } else {
                // Обычный HTTP запрос (GET/POST и т.д.)
                // Преобразуем абсолютный URI в хост
                val hostHeader = findHostHeader(clientIn, uri)
                val colon = hostHeader.lastIndexOf(':')
                if (colon != -1) {
                    targetHost = hostHeader.substring(0, colon)
                    targetPort = hostHeader.substring(colon + 1).toIntOrNull() ?: 80
                } else {
                    targetHost = hostHeader
                    targetPort = 80
                }

                remoteSocket = connectToUpstream(targetHost, targetPort) ?: return@withContext
                val remoteOut = remoteSocket.getOutputStream()
                val remoteIn = remoteSocket.getInputStream()

                // Отправляем исходную строку запроса
                remoteOut.write("$requestLine\r\n".toByteArray(Charsets.US_ASCII))
                remoteOut.flush()

                val job1 = launch { pipe(clientIn, remoteOut) }
                val job2 = launch { pipe(remoteIn, clientOut) }
                joinAll(job1, job2)
            }
        } catch (_: Exception) {
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { remoteSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun connectToUpstream(targetHost: String, targetPort: Int): Socket? {
        val socket = Socket()
        vpnService.protect(socket) // Защищаем сокет от зацикливания в VPN
        socket.soTimeout = 10000

        try {
            socket.connect(InetSocketAddress(proxyConfig.host, proxyConfig.port), 8000)

            val type = if (detectedType == ProxyType.AUTO) ProxyType.SOCKS5 else detectedType

            if (type == ProxyType.SOCKS5) {
                if (!handshakeSocks5(socket, targetHost, targetPort)) {
                    socket.close()
                    return null
                }
            } else {
                if (!handshakeHttp(socket, targetHost, targetPort)) {
                    socket.close()
                    return null
                }
            }

            socket.soTimeout = 0 // Сбрасываем таймаут для непрерывной передачи
            return socket
        } catch (e: Exception) {
            Log.e(tag, "Ошибка подключения к удаленному прокси: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
            return null
        }
    }

    private fun handshakeSocks5(socket: Socket, targetHost: String, targetPort: Int): Boolean {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        // 1. Приветствие (поддерживаем No Auth и User/Pass)
        out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        out.flush()

        val greetingResp = ByteArray(2)
        if (input.read(greetingResp) != 2 || greetingResp[0] != 0x05.toByte()) {
            return false
        }

        val authMethod = greetingResp[1].toInt() and 0xFF

        // 2. Авторизация при необходимости (RFC 1929)
        if (authMethod == 0x02) {
            val user = proxyConfig.username ?: ""
            val pass = proxyConfig.password ?: ""

            val uBytes = user.toByteArray(Charsets.UTF_8)
            val pBytes = pass.toByteArray(Charsets.UTF_8)

            val authPacket = ByteArray(3 + uBytes.size + pBytes.size)
            authPacket[0] = 0x01 // Subnegotiation version
            authPacket[1] = uBytes.size.toByte()
            System.arraycopy(uBytes, 0, authPacket, 2, uBytes.size)

            val pOffset = 2 + uBytes.size
            authPacket[pOffset] = pBytes.size.toByte()
            System.arraycopy(pBytes, 0, authPacket, pOffset + 1, pBytes.size)

            out.write(authPacket)
            out.flush()

            val authResp = ByteArray(2)
            if (input.read(authResp) != 2 || authResp[1] != 0x00.toByte()) {
                Log.e(tag, "SOCKS5: Ошибка авторизации логин/пароль!")
                return false
            }
        } else if (authMethod != 0x00) {
            return false
        }

        // 3. Запрос CONNECT
        val hostBytes = targetHost.toByteArray(Charsets.US_ASCII)
        val cmdPacket = ByteArray(7 + hostBytes.size)
        cmdPacket[0] = 0x05 // Ver
        cmdPacket[1] = 0x01 // CMD: Connect
        cmdPacket[2] = 0x00 // RSV
        cmdPacket[3] = 0x03 // ATYP: Domain name
        cmdPacket[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, cmdPacket, 5, hostBytes.size)

        val portOffset = 5 + hostBytes.size
        cmdPacket[portOffset] = ((targetPort ushr 8) and 0xFF).toByte()
        cmdPacket[portOffset + 1] = (targetPort and 0xFF).toByte()

        out.write(cmdPacket)
        out.flush()

        // 4. Читаем ответ SOCKS5 сервера
        val respHeader = ByteArray(4)
        if (input.read(respHeader) != 4 || respHeader[1] != 0x00.toByte()) {
            return false
        }

        // Пропускаем оставшийся BND.ADDR и BND.PORT
        val atyp = respHeader[3].toInt() and 0xFF
        val skipLen = when (atyp) {
            0x01 -> 4 + 2 // IPv4 (4) + Port (2)
            0x04 -> 16 + 2 // IPv6 (16) + Port (2)
            0x03 -> {
                val len = input.read()
                len + 2
            }
            else -> 6
        }
        val skipBuf = ByteArray(skipLen)
        input.read(skipBuf)

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
            // Читаем заголовки до пустой строки
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
            }
            return true
        }
        return false
    }

    private fun pipe(inputStream: InputStream, outputStream: OutputStream) {
        val buffer = ByteArray(16384)
        try {
            while (isRunning) {
                val read = inputStream.read(buffer)
                if (read == -1) break
                outputStream.write(buffer, 0, read)
                outputStream.flush()
            }
        } catch (_: Exception) {}
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

    private fun findHostHeader(input: InputStream, uri: String): String {
        return try {
            val u = java.net.URI(uri)
            val h = u.host
            if (!h.isNullOrEmpty()) {
                val p = if (u.port != -1) ":${u.port}" else ""
                "$h$p"
            } else {
                uri
            }
        } catch (_: Exception) {
            uri
        }
    }
}
