package com.gemini.dnsswitcher.service.proxy

enum class ProxyType {
    AUTO,
    SOCKS5,
    HTTP
}

data class ProxyConfig(
    val rawString: String,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val type: ProxyType = ProxyType.AUTO
) {
    val hasAuth: Boolean
        get() = !username.isNullOrEmpty()

    companion object {
        /**
         * Парсит строки вида:
         * - логин:пароль@хост:порт
         * - socks5://логин:пароль@хост:порт
         * - http://логин:пароль@хост:порт
         * - хост:порт
         * - логин:пароль@хост (порт по умолчанию 1080)
         */
        fun parse(input: String): ProxyConfig? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null

            var type = ProxyType.AUTO
            var workStr = trimmed

            if (workStr.startsWith("socks5://", ignoreCase = true)) {
                type = ProxyType.SOCKS5
                workStr = workStr.substring(9)
            } else if (workStr.startsWith("socks://", ignoreCase = true)) {
                type = ProxyType.SOCKS5
                workStr = workStr.substring(8)
            } else if (workStr.startsWith("http://", ignoreCase = true)) {
                type = ProxyType.HTTP
                workStr = workStr.substring(7)
            } else if (workStr.startsWith("https://", ignoreCase = true)) {
                type = ProxyType.HTTP
                workStr = workStr.substring(8)
            }

            var username: String? = null
            var password: String? = null
            val atIndex = workStr.lastIndexOf('@')

            val hostPortStr: String
            if (atIndex != -1) {
                val authPart = workStr.substring(0, atIndex)
                hostPortStr = workStr.substring(atIndex + 1)

                val colonIndex = authPart.indexOf(':')
                if (colonIndex != -1) {
                    username = authPart.substring(0, colonIndex)
                    password = authPart.substring(colonIndex + 1)
                } else {
                    username = authPart
                }
            } else {
                hostPortStr = workStr
            }

            val colonHostIndex = hostPortStr.lastIndexOf(':')
            val host: String
            val port: Int

            if (colonHostIndex != -1) {
                host = hostPortStr.substring(0, colonHostIndex).trim()
                val portStr = hostPortStr.substring(colonHostIndex + 1).trim()
                port = portStr.toIntOrNull() ?: if (type == ProxyType.HTTP) 8080 else 1080
            } else {
                host = hostPortStr.trim()
                port = if (type == ProxyType.HTTP) 8080 else 1080
            }

            if (host.isEmpty()) return null

            return ProxyConfig(
                rawString = trimmed,
                host = host,
                port = port,
                username = username,
                password = password,
                type = type
            )
        }
    }
}
