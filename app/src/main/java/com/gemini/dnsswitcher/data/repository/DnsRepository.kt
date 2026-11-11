package com.gemini.dnsswitcher.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.gemini.dnsswitcher.data.model.DnsServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.UUID

class DnsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gemini_dns_prefs", Context.MODE_PRIVATE)

    private val _dnsList = MutableStateFlow<List<DnsServer>>(emptyList())
    val dnsList: StateFlow<List<DnsServer>> = _dnsList.asStateFlow()

    private val _selectedDnsId = MutableStateFlow<String?>(null)
    val selectedDnsId: StateFlow<String?> = _selectedDnsId.asStateFlow()

    init {
        loadServers()
    }

    private fun loadServers() {
        val jsonString = prefs.getString(KEY_DNS_LIST, null)
        val list = mutableListOf<DnsServer>()

        if (!jsonString.isNullOrEmpty()) {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val host = if (obj.has("host")) obj.getString("host") else obj.optString("ipAddress", "")
                list.add(
                    DnsServer(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        host = host,
                        isEnabled = obj.optBoolean("isEnabled", true)
                    )
                )
            }
        }
        _dnsList.value = list

        val savedSelectedId = prefs.getString(KEY_SELECTED_ID, null)
        if (savedSelectedId != null && list.any { it.id == savedSelectedId }) {
            _selectedDnsId.value = savedSelectedId
        } else {
            val fallbackId = list.firstOrNull()?.id
            _selectedDnsId.value = fallbackId
            prefs.edit().putString(KEY_SELECTED_ID, fallbackId).apply()
        }
    }

    private fun saveListInternal(list: List<DnsServer>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("host", item.host)
                put("isEnabled", item.isEnabled)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_DNS_LIST, array.toString()).apply()
        _dnsList.value = list
    }

    fun setSelectedDnsId(id: String) {
        prefs.edit().putString(KEY_SELECTED_ID, id).apply()
        _selectedDnsId.value = id
    }

    fun getSelectedDnsId(): String? = _selectedDnsId.value

    fun addDns(name: String, host: String) {
        val current = _dnsList.value.toMutableList()
        val newDns = DnsServer(UUID.randomUUID().toString(), name.trim(), host.trim())
        current.add(newDns)
        saveListInternal(current)
        if (_selectedDnsId.value == null) {
            setSelectedDnsId(newDns.id)
        }
    }

    fun updateDns(id: String, name: String, host: String) {
        val updated = _dnsList.value.map {
            if (it.id == id) it.copy(name = name.trim(), host = host.trim()) else it
        }
        saveListInternal(updated)
    }

    fun deleteDns(id: String) {
        val filtered = _dnsList.value.filter { it.id != id }
        saveListInternal(filtered)
        if (_selectedDnsId.value == id) {
            val nextId = filtered.firstOrNull()?.id
            setSelectedDnsId(nextId ?: "")
        }
    }

    fun reorder(fromPosition: Int, toPosition: Int) {
        val list = _dnsList.value.toMutableList()
        if (fromPosition in list.indices && toPosition in list.indices) {
            Collections.swap(list, fromPosition, toPosition)
            saveListInternal(list)
        }
    }

    companion object {
        private const val KEY_DNS_LIST = "key_dns_list"
        private const val KEY_SELECTED_ID = "key_selected_id"

        @Volatile
        private var INSTANCE: DnsRepository? = null

        fun getInstance(context: Context): DnsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DnsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
