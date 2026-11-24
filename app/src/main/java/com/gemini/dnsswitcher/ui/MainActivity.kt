package com.gemini.dnsswitcher.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gemini.dnsswitcher.R
import com.gemini.dnsswitcher.data.model.DnsServer
import com.gemini.dnsswitcher.databinding.ActivityMainBinding
import com.gemini.dnsswitcher.databinding.DialogEditDnsBinding
import com.gemini.dnsswitcher.service.DnsVpnService
import com.gemini.dnsswitcher.service.proxy.ProxyConfig
import com.gemini.dnsswitcher.service.proxy.ProxyType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: DnsAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            DnsVpnService.start(this)
        } else {
            Toast.makeText(this, "Разрешение на создание VPN отклонено", Toast.LENGTH_SHORT).show()
            binding.switchVpn.isChecked = false
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Proceed regardless
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        setupRecyclerView()
        setupListeners()
        observeState()

        if (intent.getBooleanExtra(EXTRA_START_VPN, false)) {
            checkAndStartVpn()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START_VPN, false)) {
            checkAndStartVpn()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = DnsAdapter(
            onSelectClick = { server ->
                viewModel.selectDns(server.id)
                Toast.makeText(this, "Выбран: ${server.name}", Toast.LENGTH_SHORT).show()
            },
            onStartDrag = { holder ->
                itemTouchHelper.startDrag(holder)
            },
            onEditClick = { server -> showEditDnsDialog(server) },
            onDeleteClick = { server -> showDeleteConfirmDialog(server) }
        )

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (from != RecyclerView.NO_POSITION && to != RecyclerView.NO_POSITION) {
                    adapter.onItemMove(from, to)
                    viewModel.reorderDns(from, to)
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })

        binding.rvDns.layoutManager = LinearLayoutManager(this)
        binding.rvDns.adapter = adapter
        itemTouchHelper.attachToRecyclerView(binding.rvDns)
    }

    private fun setupListeners() {
        // Переключатель режимов DNS / Прокси
        binding.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnModeDns) {
                    viewModel.setAppMode("DNS")
                } else if (checkedId == R.id.btnModeProxy) {
                    viewModel.setAppMode("PROXY")
                }
            }
        }

        binding.btnSaveProxy.setOnClickListener {
            val text = binding.etProxyInput.text?.toString().orEmpty().trim()
            if (text.isNotEmpty() && ProxyConfig.parse(text) == null) {
                Toast.makeText(this, "Неверный формат прокси", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.setProxyString(text)
            Toast.makeText(this, "Прокси сохранен", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestProxy.setOnClickListener {
            val text = binding.etProxyInput.text?.toString().orEmpty().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Сначала введите прокси", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.setProxyString(text)

            binding.btnTestProxy.isEnabled = false
            binding.tvProxyTestResult.visibility = View.VISIBLE
            binding.tvProxyTestResult.text = "Проверка подключения и авторизации..."
            binding.tvProxyTestResult.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

            lifecycleScope.launch {
                val res = viewModel.diagnoseProxy()
                binding.btnTestProxy.isEnabled = true

                if (res.success) {
                    val ipPart = if (res.resolvedIp != null) " (${res.resolvedIp})" else ""
                    binding.tvProxyTestResult.text =
                        "✓ Подключено! Пинг: ${res.pingMs}мс • ${res.detectedType}$ipPart • Авторизация ОК"
                    binding.tvProxyTestResult.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                    binding.tvProxyDetectedType.text = "Протокол: ${res.detectedType} (Определен автоматически)"
                } else {
                    if (res.detectedType != ProxyType.AUTO) {
                        binding.tvProxyDetectedType.text = "Протокол: ${res.detectedType} (Определен автоматически)"
                    }
                    binding.tvProxyTestResult.text = "✗ ${res.errorMessage ?: "Сервер не отвечает"}"
                    binding.tvProxyTestResult.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_red))
                }
            }
        }

        binding.switchVpn.setOnClickListener {
            val isChecked = binding.switchVpn.isChecked
            val isRunning = DnsVpnService.isServiceRunning.value
            if (isChecked && !isRunning) {
                if (viewModel.appMode.value == "PROXY") {
                    val currentInput = binding.etProxyInput.text?.toString().orEmpty().trim()
                    if (currentInput.isNotEmpty()) {
                        if (ProxyConfig.parse(currentInput) == null) {
                            Toast.makeText(this, "Неверный формат прокси", Toast.LENGTH_SHORT).show()
                            binding.switchVpn.isChecked = false
                            return@setOnClickListener
                        }
                        viewModel.setProxyString(currentInput)
                    }
                    if (viewModel.proxyString.value.isEmpty()) {
                        Toast.makeText(this, "Введите прокси перед включением", Toast.LENGTH_SHORT).show()
                        binding.switchVpn.isChecked = false
                        return@setOnClickListener
                    }
                }
                checkAndStartVpn()
            } else if (!isChecked && isRunning) {
                DnsVpnService.stop(this)
            }
        }

        binding.fabAddDns.setOnClickListener {
            showAddDnsDialog()
        }
    }

    private fun checkAndStartVpn() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            DnsVpnService.start(this)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.appMode.collectLatest { mode ->
                        if (mode == "PROXY") {
                            binding.toggleMode.check(R.id.btnModeProxy)
                            binding.layoutDnsContainer.visibility = View.GONE
                            binding.layoutProxyContainer.visibility = View.VISIBLE
                            binding.fabAddDns.visibility = View.GONE
                        } else {
                            binding.toggleMode.check(R.id.btnModeDns)
                            binding.layoutDnsContainer.visibility = View.VISIBLE
                            binding.layoutProxyContainer.visibility = View.GONE
                            binding.fabAddDns.visibility = View.VISIBLE
                        }
                    }
                }

                launch {
                    viewModel.proxyString.collectLatest { pStr ->
                        if (binding.etProxyInput.text?.toString() != pStr) {
                            binding.etProxyInput.setText(pStr)
                        }
                    }
                }

                launch {
                    DnsVpnService.isServiceRunning.collectLatest { running ->
                        binding.switchVpn.isChecked = running
                        if (running) {
                            binding.tvStatus.text = getString(R.string.vpn_active)
                            binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                        } else {
                            binding.tvStatus.text = getString(R.string.vpn_inactive)
                            binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_red))
                            binding.tvActiveDns.text = "Текущий сервер: Не активен"
                        }
                    }
                }

                launch {
                    combine(
                        DnsVpnService.currentActiveDns,
                        DnsVpnService.currentResolvedIp,
                        DnsVpnService.currentPingMs
                    ) { dns, ip, ping -> Triple(dns, ip, ping) }.collectLatest { (dns, ip, ping) ->
                        if (dns != null && DnsVpnService.isServiceRunning.value) {
                            val ipDisplay = if (ip != null && ip != dns.host) " [IP: $ip]" else ""
                            val pingDisplay = if (ping != null) " • Пинг: ${ping}мс" else ""
                            binding.tvActiveDns.text = "Текущий: ${dns.name}$ipDisplay$pingDisplay"
                        }
                    }
                }

                launch {
                    combine(
                        viewModel.dnsList,
                        viewModel.selectedDnsId,
                        DnsVpnService.currentActiveDns
                    ) { list, selectedId, activeDns ->
                        Triple(list, selectedId, activeDns?.id)
                    }.collectLatest { (list, selectedId, activeId) ->
                        adapter.submitData(list, selectedId, activeId)
                    }
                }
            }
        }
    }

    private fun showAddDnsDialog() {
        val dialogBinding = DialogEditDnsBinding.inflate(layoutInflater)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_dns)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = dialogBinding.etDnsName.text.toString().trim()
                val host = dialogBinding.etDnsIp.text.toString().trim()
                if (name.isNotEmpty() && host.isNotEmpty()) {
                    viewModel.addDns(name, host)
                } else {
                    Toast.makeText(this, "Заполните имя и IP/Домен", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditDnsDialog(server: DnsServer) {
        val dialogBinding = DialogEditDnsBinding.inflate(layoutInflater)
        dialogBinding.etDnsName.setText(server.name)
        dialogBinding.etDnsIp.setText(server.host)

        AlertDialog.Builder(this)
            .setTitle(R.string.edit_dns)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = dialogBinding.etDnsName.text.toString().trim()
                val host = dialogBinding.etDnsIp.text.toString().trim()
                if (name.isNotEmpty() && host.isNotEmpty()) {
                    viewModel.updateDns(server.id, name, host)
                } else {
                    Toast.makeText(this, "Поля не могут быть пустыми", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirmDialog(server: DnsServer) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage("Удалить DNS-сервер \"${server.name}\"?")
            .setPositiveButton("Удалить") { _, _ ->
                viewModel.deleteDns(server.id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_START_VPN = "EXTRA_START_VPN"
    }
}
