package com.example.lightledger.notification

import android.content.Context
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Stores only source, time and processing outcome; never stores notification content. */
object NotificationDiagnostics {
    data class ProcessingTimes(val totalMs: Long, val parseMs: Long, val saveMs: Long, val promptMs: Long)
    private const val PREFS = "notification_diagnostics"
    private const val TIME = "last_time"
    private const val SOURCE = "last_source"
    private const val RESULT = "last_result"

    fun lifecycle(context: Context, event: String) {
        context.getSharedPreferences(PREFS, 0).edit()
            .putString("listener_event", event).putLong("listener_time", System.currentTimeMillis()).apply()
    }

    fun record(context: Context, packageName: String, result: String, times: ProcessingTimes? = null) {
        val source = when (packageName) {
            "com.tencent.mm" -> "微信"
            "com.eg.android.AlipayGphone" -> "支付宝"
            else -> return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(TIME, System.currentTimeMillis())
            .putString(SOURCE, source)
            .putString(RESULT, result)
            .putLong("${source}_time", System.currentTimeMillis())
            .putString("${source}_result", result)
            .apply {
                if (result == "已自动记账") putLong("${source}_saved", System.currentTimeMillis())
                if (times != null) {
                    putLong("${source}_total_ms", times.totalMs)
                    putLong("${source}_parse_ms", times.parseMs)
                    putLong("${source}_save_ms", times.saveMs)
                    putLong("${source}_prompt_ms", times.promptMs)
                } else if (result == "已收到，正在识别") {
                    remove("${source}_total_ms")
                    remove("${source}_parse_ms")
                    remove("${source}_save_ms")
                    remove("${source}_prompt_ms")
                }
            }
            .apply()
    }

    fun summary(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.CHINA)
        val state = when {
            !NotificationAccess.isEnabled(context) -> "通知读取权限未开启"
            PaymentNotificationService.isConnected -> "监听已连接"
            ListenerRecovery.checkingInitialConnection -> "正在检查监听连接，尚未确认已连接"
            else -> "监听未连接，正在尝试恢复"
        }
        val eventTime = prefs.getLong("listener_time", 0)
        val event = if (eventTime > 0) "\n最近状态 ${format.format(Date(eventTime))}：${prefs.getString("listener_event", "未知")}" else ""
        val visible = runCatching { context.getSystemService(android.app.NotificationManager::class.java)
            .activeNotifications.any { it.id == ListenerProtectionService.NOTIFICATION_ID } }.getOrNull()
        val protection = when {
            !ListenerProtection.isEnabled(context) -> "后台保护：已关闭"
            ListenerProtectionService.isRunning && visible == false -> "后台保护：运行中（通知栏未显示）"
            ListenerProtectionService.isRunning -> "后台保护：运行中"
            else -> "后台保护：当前未运行"
        }
        return state + "\n" + protection + event + "\n\n" + ProcessExitDiagnostics.summary(context) + "\n\n" + listOf("微信", "支付宝").joinToString("\n\n") { source ->
            val time = prefs.getLong("${source}_time", 0L)
            if (time == 0L) "$source：尚未收到通知" else {
                val saved = prefs.getLong("${source}_saved", 0L)
                val timing = prefs.getLong("${source}_total_ms", -1L).takeIf { it >= 0L }?.let { total ->
                    "\n本次处理 ${total}ms（识别 ${prefs.getLong("${source}_parse_ms", 0)}ms，入库 ${prefs.getLong("${source}_save_ms", 0)}ms，提示 ${prefs.getLong("${source}_prompt_ms", 0)}ms）"
                }.orEmpty()
                "$source · 最近处理 ${format.format(Date(time))}\n${prefs.getString("${source}_result", "未知")}" +
                    timing +
                    if (saved > 0) "\n最近记账：${format.format(Date(saved))}" else "\n尚无成功记录"
            }
        }
    }
}
