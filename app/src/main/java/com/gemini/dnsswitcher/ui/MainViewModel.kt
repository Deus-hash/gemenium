package com.gemini.dnsswitcher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.gemini.dnsswitcher.data.model.DnsServer
import com.gemini.dnsswitcher.data.repository.DnsRepository
import com.gemini.dnsswitcher.service.DnsVpnService
import com.gemini.dnsswitcher.service.proxy.ProxyCheckResult
import com.gemini.dnsswitcher.service.proxy.ProxyDetector
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DnsRepository.getInstance(application)
    val dnsList: StateFlow<List<DnsServer>> = repository.dnsList
    val selectedDnsId: StateFlow<String?> = repository.selectedDnsId
    val appMode: StateFlow<String> = repository.appMode
    val proxyString: StateFlow<String> = repository.proxyString

    fun setAppMode(mode: String) {
        if (repository.appMode.value != mode) {
            repository.setAppMode(mode)
            DnsVpnService.reloadIfRunning(getApplication())
        }
    }

    fun setProxyString(str: String) {
        val trimmed = str.trim()
        if (repository.proxyString.value != trimmed) {
            repository.setProxyString(trimmed)
            if (repository.appMode.value == "PROXY") {
                DnsVpnService.reloadIfRunning(getApplication())
            }
        }
    }

    suspend fun diagnoseProxy(): ProxyCheckResult {
        val config = repository.getProxyConfig()
            ?: return ProxyCheckResult(
                success = false,
                errorMessage = "Неверный формат прокси. Используйте: логин:пароль@адрес:порт"
            )
        return ProxyDetector.diagnoseProxy(config, DnsVpnService.activeInstance)
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
