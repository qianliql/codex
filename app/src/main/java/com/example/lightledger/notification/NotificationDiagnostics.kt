package com.example.lightledger.notification

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Stores only event times and diagnostic outcomes; never stores notification content. */
object NotificationDiagnostics {
    data class ProcessingTimes(val totalMs: Long, val parseMs: Long, val saveMs: Long, val promptMs: Long)
    private const val PREFS = "notification_diagnostics"
    private const val TIME = "last_time"
    private const val SOURCE = "last_source"
    private const val RESULT = "last_result"
    private const val HISTORY = "recent_results"
    private const val HISTORY_LIMIT = 24
    private const val PAYMENT_HISTORY = "payment_results"
    private const val PAYMENT_HISTORY_LIMIT = 96
    private const val LIFECYCLE_PREFS = "notification_listener_lifecycle"
    private const val LIFECYCLE_EVENTS = "events"
    private const val LIFECYCLE_LIMIT = 32
    private const val LIFECYCLE_DISPLAY_LIMIT = 12

    /** A chat message cannot displace a payment notification from the diagnostic history. */
    internal fun isPaymentRelated(packageName: String, title: String, text: String): Boolean = when (packageName) {
        "com.tencent.mm" -> listOf("微信支付", "微信收款", "微信退款", "微信转账").any(title::contains) ||
            PaymentTextParser.hasOfficialWeChatPrefix(text) ||
            (title.contains("服务通知") && listOf("支付", "付款", "收款", "退款", "转账").any(text::contains))
        "com.eg.android.AlipayGphone" -> listOf("交易提醒", "支付提醒", "收款提醒", "转账提醒").any(title::contains) ||
            listOf("支付", "付款", "支出", "收款", "退款", "转账", "交易").any(text::contains)
        else -> false
    }

    @Synchronized fun lifecycle(context: Context, event: String) {
        val prefs = context.getSharedPreferences(LIFECYCLE_PREFS, Context.MODE_PRIVATE)
        val previous = lifecycleEvents(context)
        val latest = previous.optJSONObject(previous.length() - 1)
        // A sequence of identical retries adds no new state to the timeline.
        if (latest?.optString("event") == event &&
            (event == "已请求监听连接" || event == "系统未接受连接请求")) return
        val events = JSONArray()
        val first = (previous.length() - LIFECYCLE_LIMIT + 1).coerceAtLeast(0)
        for (index in first until previous.length()) previous.optJSONObject(index)?.let { events.put(it) }
        events.put(JSONObject().put("time", System.currentTimeMillis()).put("event", event))
        // A task-removal callback can be followed immediately by process termination.
        prefs.edit().putString(LIFECYCLE_EVENTS, events.toString()).commit()
    }

    private fun lifecycleEvents(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(LIFECYCLE_PREFS, Context.MODE_PRIVATE)
        val events = runCatching { JSONArray(prefs.getString(LIFECYCLE_EVENTS, "[]")) }.getOrDefault(JSONArray())
        if (events.length() == 0) {
            val legacy = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val time = legacy.getLong("listener_time", 0)
            if (time > 0) events.put(JSONObject().put("time", time).put("event", legacy.getString("listener_event", "未知")))
        }
        return events
    }

    @Synchronized fun record(context: Context, packageName: String, result: String,
                             times: ProcessingTimes? = null, paymentRelated: Boolean = false,
                             notificationTime: Long = 0L) {
        val source = when (packageName) {
            "com.tencent.mm" -> "微信"
            "com.eg.android.AlipayGphone" -> "支付宝"
            else -> return
        }
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putLong(TIME, now)
            .putString(SOURCE, source)
            .putString(RESULT, result)
            .putLong("${source}_time", now)
            .putString("${source}_result", result)
            .apply {
                if (result == "已自动记账" || result == "已恢复断联前收到的付款") putLong("${source}_saved", now)
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
        if (result != "已收到，正在识别") {
            // Keep outcomes, not notification text or amounts, so a later chat message cannot
            // erase the evidence of how an earlier payment notification was handled.
            val previous = runCatching { JSONArray(prefs.getString(HISTORY, "[]")) }.getOrDefault(JSONArray())
            val recent = JSONArray()
            val start = (previous.length() - HISTORY_LIMIT + 1).coerceAtLeast(0)
            for (index in start until previous.length()) recent.put(previous.optJSONObject(index))
            recent.put(JSONObject().put("time", now).put("source", source).put("result", result))
            editor.putString(HISTORY, recent.toString())
            if (paymentRelated) {
                val previousPayments = runCatching { JSONArray(prefs.getString(PAYMENT_HISTORY, "[]")) }.getOrDefault(JSONArray())
                val payments = JSONArray()
                val first = (previousPayments.length() - PAYMENT_HISTORY_LIMIT + 1).coerceAtLeast(0)
                for (index in first until previousPayments.length()) payments.put(previousPayments.optJSONObject(index))
                payments.put(JSONObject().put("time", now).put("notificationTime", notificationTime)
                    .put("source", source).put("result", result))
                editor.putString(PAYMENT_HISTORY, payments.toString())
            }
        }
        editor.apply()
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
        val lifecycle = lifecycleEvents(context)
        val lifecycleFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale.CHINA)
        val latest = lifecycle.optJSONObject(lifecycle.length() - 1)
        val event = latest?.let {
            "\n最近状态 ${lifecycleFormat.format(Date(it.optLong("time")))}：${it.optString("event")}"
        }.orEmpty()
        val lifecycleHistory = ((lifecycle.length() - LIFECYCLE_DISPLAY_LIMIT).coerceAtLeast(0) until lifecycle.length()).reversed()
            .mapNotNull { lifecycle.optJSONObject(it) }
            .joinToString("\n") { "${lifecycleFormat.format(Date(it.optLong("time")))} · ${it.optString("event")}" }
        val timeline = if (lifecycle.length() > 1) "\n\n监听状态记录：\n$lifecycleHistory" else ""
        val visible = runCatching { context.getSystemService(android.app.NotificationManager::class.java)
            .activeNotifications.any { it.id == ListenerProtectionService.NOTIFICATION_ID } }.getOrNull()
        val protection = when {
            !ListenerProtection.isEnabled(context) -> "后台保护：已关闭"
            ListenerProtectionService.isRunning && visible == false -> "后台保护：运行中（通知栏未显示）"
            ListenerProtectionService.isRunning -> "后台保护：运行中"
            else -> "后台保护：当前未运行"
        }
        val recent = runCatching { JSONArray(prefs.getString(HISTORY, "[]")) }.getOrDefault(JSONArray())
        val payments = runCatching { JSONArray(prefs.getString(PAYMENT_HISTORY, "[]")) }.getOrDefault(JSONArray())
        return state + "\n" + protection + event + "\n\n" + ProcessExitDiagnostics.summary(context) + timeline + "\n\n" + listOf("微信", "支付宝").joinToString("\n\n") { source ->
            val time = prefs.getLong("${source}_time", 0L)
            if (time == 0L) "$source：尚未收到通知" else {
                val saved = prefs.getLong("${source}_saved", 0L)
                val timing = prefs.getLong("${source}_total_ms", -1L).takeIf { it >= 0L }?.let { total ->
                    "\n本次处理 ${total}ms（识别 ${prefs.getLong("${source}_parse_ms", 0)}ms，入库 ${prefs.getLong("${source}_save_ms", 0)}ms，提示 ${prefs.getLong("${source}_prompt_ms", 0)}ms）"
                }.orEmpty()
                val history = (0 until recent.length()).mapNotNull { recent.optJSONObject(it) }
                    .filter { it.optString("source") == source }.takeLast(6).asReversed()
                    .joinToString("\n") { "${format.format(Date(it.optLong("time")))} · ${it.optString("result")}" }
                val paymentHistory = (0 until payments.length()).mapNotNull { payments.optJSONObject(it) }
                    .filter { it.optString("source") == source }.takeLast(12).asReversed()
                    .joinToString("\n") {
                        val posted = it.optLong("notificationTime").takeIf { time -> time > 0L }
                        val received = it.optLong("time")
                        "${format.format(Date(posted ?: received))} · ${it.optString("result")}" +
                            if (posted != null && kotlin.math.abs(received - posted) > 60_000L)
                                "（处理于 ${format.format(Date(received))}）" else ""
                    }
                "$source · 最近处理 ${format.format(Date(time))}\n${prefs.getString("${source}_result", "未知")}" +
                    timing +
                    (if (saved > 0) "\n最近记账：${format.format(Date(saved))}" else "\n尚无成功记录") +
                    (if (paymentHistory.isNotEmpty()) "\n付款相关通知：\n$paymentHistory" else "") +
                    (if (history.isNotEmpty()) "\n最近处理记录：\n$history" else "")
            }
        }
    }
}
