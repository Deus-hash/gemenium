package com.gemini.dnsswitcher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.gemini.dnsswitcher.data.model.DnsServer
import com.gemini.dnsswitcher.data.repository.DnsRepository
import com.gemini.dnsswitcher.service.DnsVpnService
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DnsRepository.getInstance(application)
    val dnsList: StateFlow<List<DnsServer>> = repository.dnsList
    val selectedDnsId: StateFlow<String?> = repository.selectedDnsId

    fun selectDns(id: String) {
        repository.setSelectedDnsId(id)
        if (DnsVpnService.isServiceRunning.value) {
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
