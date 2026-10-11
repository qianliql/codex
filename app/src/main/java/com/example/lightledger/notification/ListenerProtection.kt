package com.example.lightledger.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.lightledger.R
import com.example.lightledger.ui.MainActivity

object ListenerProtection {
    fun isEnabled(context: Context) = context.getSharedPreferences("listener_protection", 0).getBoolean("enabled", false)
    fun enable(context: Context): Boolean {
        context.getSharedPreferences("listener_protection", 0).edit().putBoolean("enabled", true).apply()
        val started = startIfEnabled(context)
        if (!started) context.getSharedPreferences("listener_protection", 0).edit().putBoolean("enabled", false).apply()
        return started
    }
    fun disable(context: Context) {
        context.getSharedPreferences("listener_protection", 0).edit().putBoolean("enabled", false).apply()
        stop(context)
    }
    fun startIfEnabled(context: Context): Boolean {
        if (!isEnabled(context) || !NotificationAccess.isEnabled(context)) return false
        return runCatching {
            ContextCompat.startForegroundService(context, Intent(context, ListenerProtectionService::class.java))
            true
        }.getOrElse {
            // OEM / background-start restrictions must not crash notification delivery.
            Log.w("ListenerProtection", "System refused foreground protection start", it)
            false
        }
    }
    fun stop(context: Context) { context.stopService(Intent(context, ListenerProtectionService::class.java)) }
}

/** User-enabled, visible foreground work. No network, alarms or wake locks. */
class ListenerProtectionService : Service() {
    companion object {
        internal const val CHANNEL_ID = "listener_protection"
        internal const val NOTIFICATION_ID = -9203
        @Volatile var isRunning = false; private set
    }
    private val handler = Handler(Looper.getMainLooper())
    private var lastStatus: String? = null
    private val check = object : Runnable {
        override fun run() {
            if (!ListenerProtection.isEnabled(this@ListenerProtectionService) || !NotificationAccess.isEnabled(this@ListenerProtectionService)) {
                stopSelf()
                return
            }
            ListenerRecovery.check(this@ListenerProtectionService)
            updateNotification()
            handler.postDelayed(this, 30_000L)
        }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "自动记账后台保护", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示监听连接状态，后台断开时尝试恢复"
                setShowBadge(false)
            })
        try {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification)
            isRunning = true
        } catch (e: RuntimeException) {
            Log.w("ListenerProtection", "Could not enter foreground", e)
            stopSelf()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning || !ListenerProtection.isEnabled(this) || !NotificationAccess.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(check)
        check.run()
        return START_STICKY
    }
    private fun status() = when {
        PaymentNotificationService.isConnected -> "监听已连接，等待支付通知"
        ListenerRecovery.checkingInitialConnection -> "正在检查监听连接"
        else -> "监听未连接，正在自动恢复"
    }
    private fun notification(): android.app.Notification {
        val open = PendingIntent.getActivity(this, 9203, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismissed = PendingIntent.getBroadcast(this, 9204,
            Intent(this, ProtectionNotificationReceiver::class.java).setAction(ProtectionNotificationReceiver.DISMISSED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val status = status()
        lastStatus = status
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield).setContentTitle("轻账本 · 后台保护")
            .setContentText(status).setContentIntent(open).setDeleteIntent(dismissed).setOngoing(true).setOnlyAlertOnce(true)
            .setSilent(true).setShowWhen(false).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }
    private fun updateNotification() {
        if (lastStatus != status()) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        NotificationDiagnostics.lifecycle(this, "最近任务已划掉，后台保护继续运行")
        handler.removeCallbacks(check)
        check.run()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

/** Android14+ allows swiping away ongoing notifications without stopping their service. */
class ProtectionNotificationReceiver : BroadcastReceiver() {
    companion object { const val DISMISSED = "com.example.lightledger.PROTECTION_NOTIFICATION_DISMISSED" }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DISMISSED) return
        NotificationDiagnostics.lifecycle(context, if (ListenerProtectionService.isRunning)
            "状态通知已划走，后台保护仍在运行" else "状态通知已划走，后台保护未运行")
        // A dismissed notification is not an instruction to stop the listener or change its grant.
    }
}
