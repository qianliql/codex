package com.example.lightledger.notification

import com.example.lightledger.LedgerApp
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = LedgerApp::class, sdk = [35])
class NotificationDiagnosticsHistoryTest {
    @Test fun lifecycleTimelineKeepsDismissalAndTaskRemovalWhenTheListenerReconnects() {
        val app = RuntimeEnvironment.getApplication() as LedgerApp
        val diagnostics = app.getSharedPreferences("notification_diagnostics", 0)
        val lifecycle = app.getSharedPreferences("notification_listener_lifecycle", 0)
        diagnostics.edit().clear().putString("listener_event", "监听已连接").putLong("listener_time", 1000L).commit()
        lifecycle.edit().clear().commit()
        val dismissed = "状态通知已划走，后台保护仍在运行"
        val taskRemoved = "已收到最近任务移除，已安排恢复检查"
        NotificationDiagnostics.lifecycle(app, dismissed)
        NotificationDiagnostics.lifecycle(app, taskRemoved)
        NotificationDiagnostics.lifecycle(app, "监听断开")
        repeat(8) { NotificationDiagnostics.lifecycle(app, "已请求监听连接") }
        NotificationDiagnostics.lifecycle(app, "监听已连接")

        val timeline = NotificationDiagnostics.summary(app).substringAfter("监听状态记录：")
        assertTrue(timeline.indexOf("监听已连接") in 0 until timeline.indexOf("监听断开"))
        assertTrue(timeline.indexOf("监听断开") in 0 until timeline.indexOf(taskRemoved))
        assertTrue(timeline.indexOf(taskRemoved) in 0 until timeline.indexOf(dismissed))
        assertEquals(6, JSONArray(lifecycle.getString("events", "[]")).length())

        repeat(40) { NotificationDiagnostics.lifecycle(app, "监听已连接") }
        assertEquals(32, JSONArray(lifecycle.getString("events", "[]")).length())
    }

    @Test fun laterNotificationDoesNotEraseEarlierProcessingOutcome() {
        val app = RuntimeEnvironment.getApplication() as LedgerApp
        app.getSharedPreferences("notification_diagnostics", 0).edit().clear().commit()
        NotificationDiagnostics.record(app, "com.tencent.mm", "已自动记账")
        NotificationDiagnostics.record(app, "com.tencent.mm", "没有可靠的单笔金额，已跳过")
        val details = NotificationDiagnostics.summary(app)
        assertTrue(details.contains("最近处理记录："))
        assertTrue(details.contains("已自动记账"))
        assertTrue(details.contains("没有可靠的单笔金额，已跳过"))
    }
}
