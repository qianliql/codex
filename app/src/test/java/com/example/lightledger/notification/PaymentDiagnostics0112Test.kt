package com.example.lightledger.notification

import android.app.Notification
import com.example.lightledger.LedgerApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = LedgerApp::class, sdk = [29])
class PaymentDiagnostics0112Test {
    private val app get() = RuntimeEnvironment.getApplication() as LedgerApp

    @Test fun paymentOutcomesSurviveLaterWeChatChatsWithoutSavingMessageText() {
        val prefs = app.getSharedPreferences("notification_diagnostics", 0)
        prefs.edit().clear().commit()
        val service = Robolectric.buildService(PaymentNotificationService::class.java).create().get()
        var id = 500
        fun send(title: String, body: String) {
            val notification = Notification.Builder(app, "synthetic")
                .setContentTitle(title).setContentText(body).build()
            val key = shadowOf(service).addActiveNotification("com.tencent.mm", id++, notification)
            service.onNotificationPosted(service.activeNotifications.single { it.key == key })
        }
        try {
            send("微信支付", "付款成功，支付金额 ¥12.34")
            repeat(40) { send("朋友", "第 $it 条普通聊天") }
            runBlocking { app.notificationScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            val history = JSONArray(prefs.getString("payment_results", "[]"))
            assertEquals(1, history.length())
            assertEquals("微信", history.getJSONObject(0).getString("source"))
            assertFalse(history.toString().contains("12.34"))
            assertFalse(history.toString().contains("付款成功"))
            assertTrue(NotificationDiagnostics.summary(app).contains("付款相关通知："))
        } finally { service.onDestroy() }
    }

    @Test fun ordinaryCurrencyFormatAndOfficialPrefixCountAsPaymentRelated() {
        assertTrue(NotificationDiagnostics.isPaymentRelated("com.tencent.mm", "微信支付", "支付金额 ¥12.34"))
        assertTrue(NotificationDiagnostics.isPaymentRelated("com.tencent.mm", "微信", "微信支付: 付款成功 ¥12.34"))
        assertFalse(NotificationDiagnostics.isPaymentRelated("com.tencent.mm", "朋友", "吃饭 ¥12.34"))
        assertFalse(NotificationDiagnostics.isPaymentRelated("other.app", "微信支付", "付款成功 ¥12.34"))
    }

    @Test fun activePaymentWithoutPostedCallbackIsInspectedOnlyOnce() {
        val prefs = app.getSharedPreferences("notification_diagnostics", 0)
        prefs.edit().clear().commit()
        val service = Robolectric.buildService(PaymentNotificationService::class.java).create().get()
        try {
            service.onListenerConnected()
            val notification = Notification.Builder(app, "synthetic")
                .setContentTitle("微信支付").setContentText("付款成功，支付金额 ¥14.78").build()
            shadowOf(service).addActiveNotification("com.tencent.mm", 1001, notification)
            assertTrue(PaymentNotificationService.inspectCurrent())
            runBlocking { app.notificationScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            val first = JSONArray(prefs.getString("payment_results", "[]"))
            assertEquals(1, first.length())
            assertEquals("已自动记账", first.getJSONObject(0).getString("result"))
            assertTrue(PaymentNotificationService.inspectCurrent())
            assertEquals(1, JSONArray(prefs.getString("payment_results", "[]")).length())
        } finally { service.onListenerDisconnected(); service.onDestroy() }
    }
}
