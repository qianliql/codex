package com.example.lightledger.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.SystemClock
import android.util.Log
import com.example.lightledger.LedgerApp
import com.example.lightledger.data.ClassificationStatus
import com.example.lightledger.data.LedgerTransaction
import com.example.lightledger.data.TransactionSource
import com.example.lightledger.data.TransactionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.security.MessageDigest

class PaymentNotificationService : NotificationListenerService() {
    companion object {
        private val GROUP_COUNT = Regex("\\[?\\d+条\\]?")
        @Volatile var isConnected = false; private set
        @Volatile private var current: PaymentNotificationService? = null
        fun inspectCurrent(): Boolean {
            val listener = current ?: return false
            return runCatching { listener.activeNotifications.orEmpty().forEach(listener::onNotificationPosted) }.isSuccess
        }
    }
    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        current = this
        NotificationDiagnostics.lifecycle(this, "监听已连接")
        ListenerRecovery.check(this)
        runCatching {
            activeNotifications.orEmpty()
                .filter { it.packageName == "com.tencent.mm" || it.packageName == "com.eg.android.AlipayGphone" }
                .forEach(::onNotificationPosted)
        }.onFailure { Log.w("PaymentNotification", "Could not inspect active notifications", it) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // An older instance can be destroyed after the replacement has connected.
        if (current != null && current !== this) return
        isConnected = false
        current = null
        ListenerRecovery.disconnected(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val sourcePackage = sbn.packageName
        // Android calls this for every app's notification. Avoid allocating a set on that hot path.
        if (sourcePackage != "com.tencent.mm" && sourcePackage != "com.eg.android.AlipayGphone") return
        val context = applicationContext
        val receivedAt = SystemClock.elapsedRealtimeNanos()
        NotificationDiagnostics.record(context, sbn.packageName, "已收到，正在识别")
        fun outcome(result: String) {
            val elapsed = ((SystemClock.elapsedRealtimeNanos() - receivedAt) / 1_000_000L).coerceAtLeast(0L)
            NotificationDiagnostics.record(context, sbn.packageName, result,
                NotificationDiagnostics.ProcessingTimes(elapsed, elapsed, 0, 0))
        }
        val notification = sbn.notification ?: run {
            outcome("通知没有可读取的内容")
            return
        }
        val extras = notification.extras ?: run {
            outcome("通知没有可读取的单笔正文")
            return
        }
        val (title, text) = runCatching { NotificationTextReader.read(extras) }.getOrElse {
            outcome("通知内容读取失败")
            Log.w("PaymentNotification", "Could not read notification extras", it)
            return
        }
        if (text.isBlank()) {
            outcome("通知没有可读取的文字")
            return
        }
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            if (sourcePackage != "com.eg.android.AlipayGphone") {
                outcome("汇总通知，已跳过")
                return
            }
            val multiple = (extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.size ?: 0) > 1 ||
                (extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.size ?: 0) > 1 ||
                GROUP_COUNT.containsMatchIn("$title $text")
            val childPresent = runCatching { activeNotifications.orEmpty().any {
                it.packageName == sourcePackage && it.key != sbn.key && it.groupKey == sbn.groupKey &&
                    it.notification?.flags?.and(Notification.FLAG_GROUP_SUMMARY) == 0
            } }.getOrDefault(false)
            if (multiple || childPresent || !PaymentTextParser.hasStrongOutcomeWithAmount("$title\n$text")) {
                outcome(if (childPresent) "已有单条通知，汇总已跳过" else "汇总通知没有明确的单笔付款，已跳过")
                return
            }
        }
        // Copy the small immutable inputs before leaving Android's listener callback.
        val timestamp = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        val keyInput = "${sbn.packageName}|${sbn.key}|${sbn.postTime}"
        val app = application as LedgerApp
        app.notificationScope.launch {
            var parsedAt = receivedAt
            var savedAt = receivedAt
            fun elapsedMs(start: Long, end: Long) = ((end - start) / 1_000_000L).coerceAtLeast(0L)
            fun finish(result: String, promptedAt: Long = SystemClock.elapsedRealtimeNanos()) {
                NotificationDiagnostics.record(context, sourcePackage, result,
                    NotificationDiagnostics.ProcessingTimes(
                        totalMs = elapsedMs(receivedAt, promptedAt),
                        parseMs = elapsedMs(receivedAt, parsedAt),
                        saveMs = elapsedMs(parsedAt, savedAt),
                        promptMs = elapsedMs(savedAt, promptedAt),
                    ))
            }
            try {
                val parsed = NotificationParserRegistry.parse(sourcePackage, title, text)
                parsedAt = SystemClock.elapsedRealtimeNanos()
                if (parsed == null) {
                    savedAt = parsedAt
                    finish(PaymentTextParser.rejectionReason(title, text))
                    return@launch
                }
                DetectedPaymentMethod.remember(context, parsed.paymentMethod, timestamp)
                val key = MessageDigest.getInstance("SHA-256").digest(keyInput.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val status = if (parsed.type == TransactionType.REFUND) ClassificationStatus.NEED_CONFIRM
                    else ClassificationStatus.UNCLASSIFIED
                val record = LedgerTransaction(
                    amountCents = parsed.amountCents,
                    type = parsed.type,
                    merchant = parsed.merchant,
                    paymentMethod = parsed.paymentMethod,
                    platform = parsed.platform,
                    timestamp = timestamp,
                    source = TransactionSource.NOTIFICATION,
                    classificationStatus = status,
                    notificationKey = key,
                )
                val repository = app.repository
                val saved = repository.saveNotificationRecord(record)
                savedAt = SystemClock.elapsedRealtimeNanos()
                if (saved == null) {
                    finish("重复通知，已跳过")
                    return@launch
                }
                if (saved.type == TransactionType.EXPENSE && saved.amountCents > 0 &&
                    saved.classificationStatus != ClassificationStatus.CLASSIFIED) {
                    runCatching { QuickClassificationManager.show(app, saved.id, saved.amountCents, repository.categoryList()) }
                        .onFailure { Log.w("PaymentNotification", "Could not show quick classification", it) }
                }
                finish("已自动记账")
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                savedAt = SystemClock.elapsedRealtimeNanos()
                finish("保存失败，请重试或手动记录")
                Log.w("PaymentNotification", "Could not save notification", e)
            }
        }
    }

    override fun onDestroy() {
        if (current == null || current === this) {
            val wasConnected = current === this && isConnected
            isConnected = false
            current = null
            if (wasConnected) ListenerRecovery.disconnected(this) else ListenerRecovery.check(this)
        }
        super.onDestroy()
    }
}
