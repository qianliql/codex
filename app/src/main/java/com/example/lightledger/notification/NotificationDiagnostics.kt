package com.example.lightledger.notification

import android.content.Context
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Stores only source, time and processing outcome; never stores notification content. */
object NotificationDiagnostics {
    private const val PREFS = "notification_diagnostics"
    private const val TIME = "last_time"
    private const val SOURCE = "last_source"
    private const val RESULT = "last_result"

    fun record(context: Context, packageName: String, result: String) {
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
            }
            .apply()
    }

    fun summary(context: Context): String {
        if (!NotificationAccess.isEnabled(context)) return "通知读取权限未开启。请点“通知访问设置”。"
        if (!PaymentNotificationService.isConnected) return "读取权限已开启，正在尝试重连。请在“我的”开启后台保护、允许后台运行；仍未连接时可重新开启通知读取权限。"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.CHINA)
        return "监听已连接\n" + listOf("微信", "支付宝").joinToString("\n\n") { source ->
            val time = prefs.getLong("${source}_time", 0L)
            if (time == 0L) "$source：尚未收到通知" else {
                val saved = prefs.getLong("${source}_saved", 0L)
                "$source · 最近处理 ${format.format(Date(time))}\n${prefs.getString("${source}_result", "未知")}" +
                    if (saved > 0) "\n最近记账：${format.format(Date(saved))}" else "\n本版本诊断暂无成功记录"
            }
        }
    }
}
