package com.example.lightledger.notification

import com.example.lightledger.LedgerApp
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = LedgerApp::class, sdk = [35])
class NotificationDiagnosticsHistoryTest {
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
