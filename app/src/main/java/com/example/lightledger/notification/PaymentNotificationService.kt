package com.example.lightledger.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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
        if (sbn == null || sbn.packageName !in setOf("com.tencent.mm", "com.eg.android.AlipayGphone")) return
        val context = applicationContext
        NotificationDiagnostics.record(context, sbn.packageName, "已收到，正在识别")
        val notification = sbn.notification ?: return
        fun outcome(result: String) {
            NotificationDiagnostics.record(context, sbn.packageName, result)
        }
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            outcome("汇总通知，已跳过")
            return
        }
        val extras = notification.extras ?: run {
            outcome("通知没有可读取的单笔正文")
            return
        }
        val (title, text) = NotificationTextReader.read(extras)
        if (text.isBlank()) {
            outcome("通知没有可读取的文字")
            return
        }
        val parsed = NotificationParserRegistry.parse(sbn.packageName, title, text) ?: run {
            outcome(PaymentTextParser.rejectionReason(title, text))
            return
        }
        DetectedPaymentMethod.remember(context, parsed.paymentMethod,
            sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis())
        val keyInput = "${sbn.packageName}|${sbn.key}|${sbn.postTime}"
        val key = MessageDigest.getInstance("SHA-256").digest(keyInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val status = when {
            parsed.type == TransactionType.REFUND -> ClassificationStatus.NEED_CONFIRM
            else -> ClassificationStatus.UNCLASSIFIED
        }
        val record = LedgerTransaction(
            amountCents = parsed.amountCents,
            type = parsed.type,
            merchant = parsed.merchant,
            paymentMethod = parsed.paymentMethod,
            platform = parsed.platform,
            timestamp = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
            source = TransactionSource.NOTIFICATION,
            classificationStatus = status,
            notificationKey = key,
        )
        val app = application as LedgerApp
        app.notificationScope.launch {
            try {
                val repository = app.repository
                val id = repository.saveNotification(record)
                val saved = id?.let { repository.transaction(it) }
                outcome(if (saved == null) "重复通知，已跳过" else "已自动记账")
                if (saved != null && saved.type == TransactionType.EXPENSE && saved.amountCents > 0 &&
                    saved.classificationStatus != ClassificationStatus.CLASSIFIED) {
                    QuickClassificationManager.show(app, saved.id, saved.amountCents, repository.categoryList())
                }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                outcome("保存失败，请重试或手动记录")
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
