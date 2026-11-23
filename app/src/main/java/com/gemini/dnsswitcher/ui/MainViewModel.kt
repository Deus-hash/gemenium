package com.gemini.dnsswitcher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.gemini.dnsswitcher.data.model.DnsServer
import com.gemini.dnsswitcher.data.repository.DnsRepository
import com.gemini.dnsswitcher.service.DnsVpnService
import com.gemini.dnsswitcher.service.proxy.ProxyConfig
import com.gemini.dnsswitcher.service.proxy.ProxyDetector
import com.gemini.dnsswitcher.service.proxy.ProxyType
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DnsRepository.getInstance(application)
    val dnsList: StateFlow<List<DnsServer>> = repository.dnsList
    val selectedDnsId: StateFlow<String?> = repository.selectedDnsId
    val appMode: StateFlow<String> = repository.appMode
    val proxyString: StateFlow<String> = repository.proxyString

    fun setAppMode(mode: String) {
        repository.setAppMode(mode)
    }

    fun setProxyString(str: String) {
        repository.setProxyString(str)
    }

    suspend fun testProxy(): Long? {
        val config = repository.getProxyConfig() ?: return null
        return ProxyDetector.testConnection(config)
    }

    suspend fun detectProxyType(): ProxyType {
        val config = repository.getProxyConfig() ?: return ProxyType.AUTO
        return ProxyDetector.detectType(config)
    }

    fun selectDns(id: String) {
        repository.setSelectedDnsId(id)
        if (DnsVpnService.isServiceRunning.value && appMode.value == "DNS") {
            DnsVpnService.switchToDns(getApplication(), id)
        }
    }

    fun reorderDns(fromPosition: Int, toPosition: Int) {
        repository.reorder(fromPosition, toPosition)
    }

    fun addDns(name: String, host: String) {
        repository.addDns(name, host)
    }

    fun updateDns(id: String, name: String, host: String) {
        repository.updateDns(id, name, host)
    }

    fun deleteDns(id: String) {
        repository.deleteDns(id)
    }
}
