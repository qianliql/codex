package com.example.lightledger.notification

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29, 35])
class NotificationAccessAndTraceTest {
    class RecordingActivity : Activity() {
        val launched = mutableListOf<Intent>()
        var rejectDetail = false
        override fun startActivity(intent: Intent) {
            launched.add(intent)
            if (rejectDetail && intent.action == Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS) throw ActivityNotFoundException()
        }
    }
    @Test fun permissionPageTargetsThisAppAndFallsBackWhenUnsupported() {
        val controller = Robolectric.buildActivity(RecordingActivity::class.java).setup()
        try {
            val activity = controller.get()
            NotificationAccess.openSettings(activity)
            if (Build.VERSION.SDK_INT >= 30) {
                val intent = activity.launched.single()
                assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, intent.action)
                assertEquals(ComponentName(activity, PaymentNotificationService::class.java).flattenToString(), intent.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME))
                activity.launched.clear(); activity.rejectDetail = true
                NotificationAccess.openSettings(activity)
                assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, activity.launched.last().action)
            } else assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, activity.launched.single().action)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun samplesRequireOptInIgnoreUnrelatedAppsAndStayBounded() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(NotificationTrace.enabled(context))
        assertNull(NotificationTrace.received(context, sample("com.tencent.mm", "付款成功 ¥18.60")))
        NotificationTrace.setEnabled(context, true)
        assertNull(NotificationTrace.received(context, sample("unrelated.app", "付款成功 ¥18.60")))
        assertNull(NotificationTrace.received(context, sample("com.tencent.mm", "今晚一起吃饭", "朋友")))
        val first = NotificationTrace.received(context, sample("com.tencent.mm", "转账成功 ¥18.60"))!!
        NotificationTrace.result(context, first, "已自动记账", "TRANSFER", 1860)
        val report = JSONObject(NotificationTrace.export(context))
        assertEquals("已自动记账", report.getJSONArray("events").getJSONObject(0).getString("result"))
        assertEquals(1860L, report.getJSONArray("events").getJSONObject(0).getLong("amountCents"))
        assertFalse(report.has("imei"))
        repeat(85) { NotificationTrace.received(context, sample("com.tencent.mm", "付款成功 ¥1.00")) }
        assertEquals(80, NotificationTrace.count(context))
        NotificationTrace.setEnabled(context, false)
        assertNull(NotificationTrace.received(context, sample("com.tencent.mm", "付款成功 ¥18.60")))
        NotificationTrace.clear(context); assertEquals(0, NotificationTrace.count(context))
    }
    private fun sample(pkg: String, body: String, title: String = "微信支付") = StatusBarNotification(pkg, pkg, 1, null, 1000, 0, 0,
        Notification().apply { extras = Bundle().apply { putCharSequence(Notification.EXTRA_TITLE, title); putCharSequence(Notification.EXTRA_TEXT, body) } }, android.os.Process.myUserHandle(), System.currentTimeMillis())
}
