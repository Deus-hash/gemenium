package com.gemini.dnsswitcher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.gemini.dnsswitcher.R
import com.gemini.dnsswitcher.data.model.DnsServer
import com.gemini.dnsswitcher.data.repository.DnsRepository
import com.gemini.dnsswitcher.ui.MainActivity
import com.gemini.dnsswitcher.widget.DnsWidgetProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

class DnsVpnService : VpnService() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)

    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetRelay: DnsPacketRelay? = null
    private val healthChecker by lazy { DnsHealthChecker(this) }
    private lateinit var repository: DnsRepository

    private var currentDnsIndex = 0
    private var dnsList: List<DnsServer> = emptyList()

    @Volatile private var isRunning = false

    companion object {
        const val ACTION_START = "com.gemini.dnsswitcher.ACTION_START"
        const val ACTION_STOP = "com.gemini.dnsswitcher.ACTION_STOP"
        const val ACTION_SWITCH_DNS = "com.gemini.dnsswitcher.ACTION_SWITCH_DNS"
        const val EXTRA_DNS_ID = "EXTRA_DNS_ID"

        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "gemini_dns_vpn_channel"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _currentActiveDns = MutableStateFlow<DnsServer?>(null)
        val currentActiveDns: StateFlow<DnsServer?> = _currentActiveDns.asStateFlow()

        private val _currentResolvedIp = MutableStateFlow<String?>(null)
        val currentResolvedIp: StateFlow<String?> = _currentResolvedIp.asStateFlow()

        private val _currentPingMs = MutableStateFlow<Long?>(null)
        val currentPingMs: StateFlow<Long?> = _currentPingMs.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun switchToDns(context: Context, dnsId: String) {
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = ACTION_SWITCH_DNS
                putExtra(EXTRA_DNS_ID, dnsId)
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = DnsRepository.getInstance(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            ACTION_START -> {
                startInForeground()
                startVpn()
            }
            ACTION_SWITCH_DNS -> {
                val targetId = intent.getStringExtra(EXTRA_DNS_ID)
                if (targetId != null) {
                    switchDnsById(targetId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val initialNotification = buildNotification("Инициализация туннеля...")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
    }

    private fun startVpn() {
        if (isRunning) return
        isRunning = true
        _isServiceRunning.value = true

        scope.launch {
            dnsList = repository.dnsList.value
            val selectedId = repository.getSelectedDnsId()
            val foundIndex = dnsList.indexOfFirst { it.id == selectedId }
            currentDnsIndex = if (foundIndex >= 0) foundIndex else 0

            initVpnInterface()
            applyCurrentDns()
            startHealthCheckLoop()
        }
    }

    private fun switchDnsById(id: String) {
        scope.launch {
            dnsList = repository.dnsList.value
            val idx = dnsList.indexOfFirst { it.id == id }
            if (idx >= 0) {
                currentDnsIndex = idx
                repository.setSelectedDnsId(id)
                applyCurrentDns()
            }
        }
    }

    @Synchronized
    private fun initVpnInterface() {
        if (vpnInterface != null) return

        try {
            val builder = Builder().apply {
                setSession("GeminiDNS")
                addAddress("10.0.0.2", 24)
                addDnsServer("10.0.0.1")
                addRoute("10.0.0.1", 32)
                allowBypass()
                setBlocking(false)
            }

            vpnInterface = builder.establish()
            val pfd = vpnInterface
            if (pfd != null) {
                packetRelay?.stop()
                packetRelay = DnsPacketRelay(this, pfd).also { it.start() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopVpn()
        }
    }

    private suspend fun applyCurrentDns() {
        if (!isRunning || dnsList.isEmpty()) return

        val targetDns = dnsList[currentDnsIndex]
        _currentActiveDns.value = targetDns

        packetRelay?.setUpstream(targetDns.host)

        val resolvedIp = withContext(Dispatchers.IO) {
            try {
                InetAddress.getByName(targetDns.host).hostAddress
            } catch (_: Exception) {
                targetDns.host
            }
        }
        _currentResolvedIp.value = resolvedIp

        // Первичный замер пинга
        val quickPing = healthChecker.measurePing(targetDns.host, timeoutMs = 2000)
        _currentPingMs.value = quickPing

        val pingStr = if (quickPing != null) " • ${quickPing}мс" else ""
        updateNotification("Активен: ${targetDns.name} (${resolvedIp ?: targetDns.host})$pingStr")
        DnsWidgetProvider.updateAllWidgets(this, true, targetDns.name)
    }

    private fun startHealthCheckLoop() = scope.launch {
        var consecutiveFailures = 0
        while (isActive && isRunning) {
            delay(15_000) // Проверка каждые 15 секунд вместо частых 8 сек
            val active = dnsList.getOrNull(currentDnsIndex) ?: break

            // Если реальный пользовательский трафик успешно проходил за последние 20 секунд,
            // сервер гарантированно жив и отлично работает! Не мучаем лишними ложными тревогами.
            val lastTrafficTime = packetRelay?.getLastSuccessfulTrafficTime() ?: 0L
            val isTrafficRecentlyActive = (System.currentTimeMillis() - lastTrafficTime) < 20_000L

            val ping = healthChecker.measurePing(active.host, timeoutMs = 3500)
            if (ping != null) {
                _currentPingMs.value = ping
            }

            if (ping == null && !isTrafficRecentlyActive) {
                consecutiveFailures++
                // Требуется 4 последовательных сбоя (более минуты без ответа), чтобы переключиться
                if (consecutiveFailures >= 4) {
                    consecutiveFailures = 0
                    failoverToNextDns()
                }
            } else {
                consecutiveFailures = 0
            }
        }
    }

    private fun failoverToNextDns() = scope.launch {
        if (dnsList.size <= 1) return@launch
        currentDnsIndex = (currentDnsIndex + 1) % dnsList.size
        val nextServer = dnsList[currentDnsIndex]
        repository.setSelectedDnsId(nextServer.id)
        applyCurrentDns()
    }

    private fun stopVpn() {
        isRunning = false
        _isServiceRunning.value = false
        _currentActiveDns.value = null
        _currentResolvedIp.value = null
        _currentPingMs.value = null
        serviceJob.cancelChildren()

        packetRelay?.stop()
        packetRelay = null

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        DnsWidgetProvider.updateAllWidgets(this, false, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DNS Tunnel Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Отображает статус активного DNS-туннеля"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, DnsVpnService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gemini DNS Switcher")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
