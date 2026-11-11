package com.gemini.dnsswitcher.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import com.gemini.dnsswitcher.R
import com.gemini.dnsswitcher.service.DnsVpnService
import com.gemini.dnsswitcher.ui.MainActivity

class DnsWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_VPN = "com.gemini.dnsswitcher.ACTION_TOGGLE_VPN"

        fun updateAllWidgets(context: Context, isVpnActive: Boolean, currentDnsName: String? = null) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, DnsWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(componentName)

            for (widgetId in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_dns).apply {
                    if (isVpnActive) {
                        setTextViewText(R.id.widget_status_text, "ВКЛ: ${currentDnsName ?: "DNS"}")
                        setTextColor(R.id.widget_status_text, 0xFF22C55E.toInt())
                        setTextViewText(R.id.widget_toggle_btn, "Отключить")
                    } else {
                        setTextViewText(R.id.widget_status_text, "Статус: ВЫКЛ")
                        setTextColor(R.id.widget_status_text, 0xFFEF4444.toInt())
                        setTextViewText(R.id.widget_toggle_btn, "Включить")
                    }

                    val toggleIntent = Intent(context, DnsWidgetProvider::class.java).apply {
                        action = ACTION_TOGGLE_VPN
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        widgetId,
                        toggleIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    setOnClickPendingIntent(R.id.widget_toggle_btn, pendingIntent)

                    val openAppIntent = Intent(context, MainActivity::class.java)
                    val openAppPendingIntent = PendingIntent.getActivity(
                        context,
                        widgetId,
                        openAppIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)
                }
                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val isRunning = DnsVpnService.isServiceRunning.value
        val activeName = DnsVpnService.currentActiveDns.value?.name
        updateAllWidgets(context, isRunning, activeName)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_VPN) {
            val isRunning = DnsVpnService.isServiceRunning.value
            if (isRunning) {
                DnsVpnService.stop(context)
            } else {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    // VPN разрешение еще не дано — запускаем Activity для диалога подтверждения
                    val actIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(MainActivity.EXTRA_START_VPN, true)
                    }
                    context.startActivity(actIntent)
                } else {
                    DnsVpnService.start(context)
                }
            }
        }
    }
}
