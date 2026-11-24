package com.gemini.dnsswitcher.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.gemini.dnsswitcher.data.repository.DnsRepository
import com.gemini.dnsswitcher.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/**
 * Плитка быстрых настроек (значок в шторке уведомлений Android).
 * Позволяет включать и отключать туннель DNS/Прокси в одно нажатие из любого экрана.
 */
class DnsTileService : TileService() {

    private var listeningScope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        listeningScope?.cancel()
        val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        listeningScope = scope

        updateTileState()

        scope.launch {
            combine(
                DnsVpnService.isServiceRunning,
                DnsVpnService.currentActiveDns,
                DnsVpnService.currentPingMs
            ) { running, activeDns, ping ->
                Triple(running, activeDns, ping)
            }.collect {
                updateTileState()
            }
        }
    }

    override fun onStopListening() {
        listeningScope?.cancel()
        listeningScope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = DnsVpnService.isServiceRunning.value

        if (isRunning) {
            DnsVpnService.stop(this)
            applyTile(isActive = false, mode = DnsRepository.getInstance(this).appMode.value, subtitleText = "Выключен")
            return
        }

        val repo = DnsRepository.getInstance(this)
        val mode = repo.appMode.value

        // Проверяем, заполнены ли настройки выбранного режима
        val missingConfig = if (mode == "PROXY") {
            repo.getProxyConfig() == null
        } else {
            repo.dnsList.value.isEmpty()
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null || missingConfig) {
            val actIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!missingConfig) {
                    putExtra(MainActivity.EXTRA_START_VPN, true)
                }
            }
            openActivitySafely(actIntent)
            return
        }

        DnsVpnService.start(this)
        applyTile(isActive = true, mode = mode, subtitleText = "Подключение...")
    }

    private fun openActivitySafely(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                100,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTileState() {
        val repo = DnsRepository.getInstance(this)
        val mode = repo.appMode.value
        val isRunning = DnsVpnService.isServiceRunning.value
        val activeDns = DnsVpnService.currentActiveDns.value
        val ping = DnsVpnService.currentPingMs.value

        val subtitle = if (isRunning) {
            val name = activeDns?.name ?: if (mode == "PROXY") "Прокси" else "DNS"
            if (ping != null) "$name • ${ping}мс" else name
        } else {
            if (mode == "PROXY") "Прокси • Выкл" else "DNS • Выкл"
        }

        applyTile(isActive = isRunning, mode = mode, subtitleText = subtitle)
    }

    private fun applyTile(isActive: Boolean, mode: String, subtitleText: String) {
        val tile = qsTile ?: return
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (mode == "PROXY") "Gemini Прокси" else "Gemini DNS"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitleText
        }
        tile.updateTile()
    }

    companion object {
        fun requestTileUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, DnsTileService::class.java))
            } catch (_: Exception) {}
        }
    }
}
