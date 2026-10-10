package com.example.lightledger.notification

import android.app.Notification
import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Explicitly enabled, private local samples; exporting never sends anything automatically. */
object NotificationTrace {
    private const val PREFS = "payment_trace"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("enabled", enabled).apply() }
    @Synchronized fun clear(context: Context) { prefs(context).edit().remove("events").apply() }
    private fun events(context: Context) = runCatching { JSONArray(prefs(context).getString("events", "[]")) }.getOrElse { JSONArray() }
    private fun save(context: Context, all: JSONArray) {
        val bounded = JSONArray()
        for (i in maxOf(0, all.length() - 80) until all.length()) bounded.put(all.getJSONObject(i))
        prefs(context).edit().putString("events", bounded.toString()).apply()
    }
    fun count(context: Context) = events(context).length()
    fun connection(context: Context, connected: Boolean) {
        prefs(context).edit().putLong(if (connected) "connectedAt" else "disconnectedAt", System.currentTimeMillis()).apply()
    }
    @Synchronized fun received(context: Context, sbn: StatusBarNotification): String? = runCatching {
        if (!enabled(context) || sbn.packageName !in listOf("com.tencent.mm", "com.eg.android.AlipayGphone")) return null
        val extras = sbn.notification.extras
        val content = NotificationTextReader.read(extras)
        val fields = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG, Notification.EXTRA_CONVERSATION_TITLE,
            Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_SUMMARY_TEXT)
        val raw = JSONObject()
        fields.forEach { key -> extras.getCharSequence(key)?.toString()?.let { raw.put(key, it.take(4000)) } }
        val related = Regex("支付|付款|转账|收款|退款|记账|账单").containsMatchIn(content.title + content.text + raw.toString())
        if (!related) return null
        val id = UUID.randomUUID().toString()
        val event = JSONObject().put("id", id).put("receivedAt", System.currentTimeMillis()).put("postTime", sbn.postTime)
            .put("package", sbn.packageName).put("flags", sbn.notification.flags)
            .put("groupSummary", sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            .put("selectedTitle", content.title).put("selectedText", content.text).put("notificationFields", raw)
            .put("messageCount", extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.size ?: 0)
            .put("textLines", JSONArray(extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString().take(4000) }.orEmpty().take(20)))
            .put("result", "已收到，正在处理")
        save(context, events(context).put(event))
        id
    }.getOrNull()
    @Synchronized fun result(context: Context, id: String?, result: String, type: String? = null, cents: Long? = null) {
        if (id == null) return
        runCatching {
            val all = events(context)
            for (i in 0 until all.length()) if (all.getJSONObject(i).optString("id") == id) {
                val event = all.getJSONObject(i).put("result", result).put("processedAt", System.currentTimeMillis())
                if (type != null) event.put("parsedType", type)
                if (cents != null) event.put("amountCents", cents)
                break
            }
            save(context, all)
        }
    }
    @Suppress("DEPRECATION")
    fun export(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return JSONObject().put("formatVersion", 1).put("exportedAt", System.currentTimeMillis())
            .put("appVersion", info.versionName).put("phoneModel", Build.MODEL).put("androidApi", Build.VERSION.SDK_INT)
            .put("notificationAccessEnabled", NotificationAccess.isEnabled(context))
            .put("listenerConnected", PaymentNotificationService.isConnected).put("postNotificationsEnabled", NotificationAccess.canPost(context))
            .put("lastConnectedAt", prefs(context).getLong("connectedAt", 0)).put("lastDisconnectedAt", prefs(context).getLong("disconnectedAt", 0))
            .put("diagnostic", NotificationDiagnostics.summary(context)).put("sampleCaptureEnabled", enabled(context))
            .put("events", events(context)).toString(2)
    }
}
