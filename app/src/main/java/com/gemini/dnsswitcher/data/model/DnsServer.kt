package com.gemini.dnsswitcher.data.model

data class DnsServer(
    val id: String,
    val name: String,
    val host: String, // Может быть как IP-адресом (1.1.1.1), так и доменом (xbox-dns.ru, dns.comss.one)
    val isEnabled: Boolean = true
)
