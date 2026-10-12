package com.example.lightledger.notification

import android.app.Application
import android.app.Notification
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = Application::class, sdk = [29])
class NotificationTextReaderTest {
    @Test fun conversationLabelMustNotHideOfficialPaymentTitle() {
        val extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, "微信支付")
            putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "消息")
            putCharSequence(Notification.EXTRA_TEXT, "转账成功 ¥18.60")
        }
        val content = NotificationTextReader.read(extras)
        assertEquals(com.example.lightledger.data.TransactionType.TRANSFER,
            NotificationParserRegistry.parse("com.tencent.mm", content.title, content.text)?.type)
    }

    @Test fun expandedPlaceholderMustNotHideCurrentCompactTransfer() {
        val extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, "支付宝")
            putCharSequence(Notification.EXTRA_TEXT, "转账成功 ¥18.60")
            putCharSequence(Notification.EXTRA_BIG_TEXT, "2条新消息")
        }
        val content = NotificationTextReader.read(extras)
        assertEquals(1860L, NotificationParserRegistry.parse("com.eg.android.AlipayGphone", content.title, content.text)?.amountCents)
    }
    @Test fun multipleInboxHistoryLinesAreNotGuessedAsOneNewPayment() {
        val extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, "微信支付")
            putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf("付款成功 ¥6.00", "付款成功 ¥18.60"))
        }
        assertTrue(NotificationTextReader.read(extras).text.isEmpty())
    }
    @Test fun expandedPaymentDoesNotConflictWithCompactOrSummaryAmounts() {
        val extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, "微信支付")
            putCharSequence(Notification.EXTRA_TEXT, "付款成功 ¥18.60")
            putCharSequence(Notification.EXTRA_BIG_TEXT, "付款成功 ¥18.60，收款方：测试商户")
            putCharSequence(Notification.EXTRA_SUMMARY_TEXT, "累计支出 ¥59.20")
            putCharSequence(Notification.EXTRA_SUB_TEXT, "余额 ¥100.00")
        }
        val content = NotificationTextReader.read(extras)
        assertEquals(1860L, NotificationParserRegistry.parse("com.tencent.mm", content.title, content.text)?.amountCents)
        assertFalse(content.text.contains("59.20"))
        assertFalse(content.text.contains("100.00"))
    }

    @Test fun onlyLatestMessageIsParsedWhenOldPaymentsAreRetainedInHistory() {
        val old = Bundle().apply { putLong("time", 10); putCharSequence("text", "付款成功 ¥6.00") }
        val recent = Bundle().apply { putLong("time", 20); putCharSequence("text", "付款完成，付款金额：18.60") }
        val extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, "微信")
            putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "微信支付")
            putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(recent, old))
            putCharSequence(Notification.EXTRA_TEXT, "[2条]微信支付")
        }
        val content = NotificationTextReader.read(extras)
        assertEquals("微信支付", content.title)
        assertEquals(1860L, NotificationParserRegistry.parse("com.tencent.mm", content.title, content.text)?.amountCents)
        assertFalse(content.text.contains("6.00"))
    }

    @Test fun latestReportDoesNotReusePreviousPaymentAmount() {
        val extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, "微信支付")
            putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(
                Bundle().apply { putLong("time", 10); putCharSequence("text", "付款成功 ¥6.00") },
                Bundle().apply { putLong("time", 20); putCharSequence("text", "昨日记账日报，点击查看详情") }))
        }
        val content = NotificationTextReader.read(extras)
        assertNull(NotificationParserRegistry.parse("com.tencent.mm", content.title, content.text))
    }

    @Test fun diagnosticResultsForBothAppsAreRetainedWithoutNotificationText() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        NotificationDiagnostics.record(context, "com.tencent.mm", "已自动记账")
        NotificationDiagnostics.record(context, "com.eg.android.AlipayGphone", "没有可靠金额，已跳过")
        val prefs = context.getSharedPreferences("notification_diagnostics", 0)
        assertEquals("已自动记账", prefs.getString("微信_result", null))
        assertEquals("没有可靠金额，已跳过", prefs.getString("支付宝_result", null))
        assertTrue(prefs.getLong("微信_saved", 0) > 0)
    }
}
