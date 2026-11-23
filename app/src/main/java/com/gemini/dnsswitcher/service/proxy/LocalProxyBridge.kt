package com.gemini.dnsswitcher.service.proxy

import android.net.VpnService
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Локальный мост-прокси на 127.0.0.1:18080.
 * Поддерживает любые сайты, Google-сервисы, веб-приложения и внешние ссылки.
 * Гарантирует корректную передачу TLS-рукопожатий и завершение TCP-потоков.
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
            if (detectedType == ProxyType.AUTO) {
                detectedType = ProxyDetector.detectType(proxyConfig, vpnService)
                Log.d(tag, "Автоопределение прокси: $detectedType")
            }

            try {
                serverSocket = ServerSocket(localPort, 256, InetAddress.getByName("127.0.0.1"))
                Log.d(tag, "Локальный прокси-мост запущен на 127.0.0.1:$localPort")

                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    scope.launch {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(tag, "Ошибка в ServerSocket: ${e.message}")
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
            clientSocket.tcpNoDelay = true
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            val requestLine = readLine(clientIn) ?: return@withContext
            if (requestLine.isEmpty()) return@withContext

            val parts = requestLine.split(" ")
            if (parts.size < 2) return@withContext

            val method = parts[0]
            val uri = parts[1]

            val targetHost: String
            val targetPort: Int

            if (method.equals("CONNECT", ignoreCase = true)) {
                // HTTPS туннель: CONNECT domain.com:443 HTTP/1.1
                val colon = uri.lastIndexOf(':')
                if (colon != -1) {
                    targetHost = uri.substring(0, colon)
                    targetPort = uri.substring(colon + 1).toIntOrNull() ?: 443
                } else {
                    targetHost = uri
                    targetPort = 443
                }

                // Считываем заголовки клиента
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

                remoteSocket.tcpNoDelay = true

                // Отправляем подтверждение установки соединения клиенту
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
                // Обычный HTTP запрос (GET / POST)
                var hostHeader: String? = null
                var portHeader = 80
                val headers = mutableListOf<String>()

                while (true) {
                    val line = readLine(clientIn) ?: break
                    if (line.isEmpty()) break
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

                val finalHost = hostHeader ?: parseHostFromUri(uri)
                targetHost = finalHost
                targetPort = portHeader

                remoteSocket = connectToUpstream(targetHost, targetPort)
                if (remoteSocket == null) {
                    clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    clientOut.flush()
                    return@withContext
                }

                remoteSocket.tcpNoDelay = true
                val remoteOut = remoteSocket.getOutputStream()
                val remoteIn = remoteSocket.getInputStream()

                // Отправляем оригинальный HTTP запрос
                remoteOut.write("$requestLine\r\n".toByteArray(Charsets.US_ASCII))
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

    private fun connectToUpstream(targetHost: String, targetPort: Int): Socket? {
        val socket = Socket()
        vpnService.protect(socket)
        socket.soTimeout = 12000

        try {
            socket.connect(InetSocketAddress(proxyConfig.host, proxyConfig.port), 10000)

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

            socket.soTimeout = 0
            return socket
        } catch (e: Exception) {
            Log.e(tag, "Ошибка подключения к удаленному прокси для $targetHost:$targetPort -> ${e.message}")
            try { socket.close() } catch (_: Exception) {}
            return null
        }
    }

    private fun handshakeSocks5(socket: Socket, targetHost: String, targetPort: Int): Boolean {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        // 1. Приветствие
        out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        out.flush()

        val greetingResp = ByteArray(2)
        readFully(input, greetingResp)
        if (greetingResp[0] != 0x05.toByte()) {
            return false
        }

        val authMethod = greetingResp[1].toInt() and 0xFF

        // 2. Авторизация RFC 1929
        if (authMethod == 0x02) {
            val user = proxyConfig.username ?: ""
            val pass = proxyConfig.password ?: ""

            val uBytes = user.toByteArray(Charsets.UTF_8)
            val pBytes = pass.toByteArray(Charsets.UTF_8)

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
                Log.e(tag, "SOCKS5: Неверный логин или пароль прокси!")
                return false
            }
        } else if (authMethod != 0x00) {
            return false
        }

        // 3. Команда CONNECT (используем доменное имя ATYP 0x03, чтобы удаленный сервер сам разрешил DNS)
        val hostBytes = targetHost.toByteArray(Charsets.US_ASCII)
        val cmdPacket = ByteArray(7 + hostBytes.size)
        cmdPacket[0] = 0x05
        cmdPacket[1] = 0x01
        cmdPacket[2] = 0x00
        cmdPacket[3] = 0x03
        cmdPacket[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, cmdPacket, 5, hostBytes.size)

        val portOffset = 5 + hostBytes.size
        cmdPacket[portOffset] = ((targetPort ushr 8) and 0xFF).toByte()
        cmdPacket[portOffset + 1] = (targetPort and 0xFF).toByte()

        out.write(cmdPacket)
        out.flush()

        // 4. Считываем ответ SOCKS5 сервера
        val respHeader = ByteArray(4)
        readFully(input, respHeader)
        if (respHeader[1] != 0x00.toByte()) {
            Log.e(tag, "SOCKS5: Ошибка соединения с хостом $targetHost (Код: ${respHeader[1]})")
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
            if (count < 0) throw EOFException("Преждевременный конец потока при чтении $length байт")
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

    private fun parseHostFromUri(uri: String): String {
        return try {
            val u = java.net.URI(uri)
            u.host ?: uri
        } catch (_: Exception) {
            uri
        }
    }
}
