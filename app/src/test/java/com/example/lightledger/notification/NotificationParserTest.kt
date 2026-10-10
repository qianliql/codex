package com.example.lightledger.notification

import com.example.lightledger.data.TransactionType
import org.junit.Assert.*
import org.junit.Test

class NotificationParserTest {
    @Test fun labeledAmountsAndCompletedPaymentsAreSupported() {
        val wx = NotificationParserRegistry.parse("com.tencent.mm", "微信", "[2条]微信支付: 付款完成，付款金额：18.60")
        assertEquals(1860L, wx?.amountCents)
        assertEquals(TransactionType.EXPENSE, wx?.type)
        val ali = NotificationParserRegistry.parse("com.eg.android.AlipayGphone", "支付宝", "支付完成\n支付金额：8.80")
        assertEquals(880L, ali?.amountCents)
        assertEquals(TransactionType.EXPENSE, ali?.type)
    }

    @Test fun receiverLabelDoesNotChangeOutgoingPaymentToIncome() {
        val result = NotificationParserRegistry.parse("com.tencent.mm", "微信支付", "付款成功 ¥18.60\n收款方：测试商户")
        assertEquals(TransactionType.EXPENSE, result?.type)
        assertEquals("测试商户", result?.merchant)
    }

    @Test fun incompleteFailedAndPromotionalPaymentsAreIgnored() {
        listOf("付款失败 ¥18.60", "支付处理中 ¥18.60", "请支付 ¥18.60", "交易关闭 ¥18.60", "领取支付优惠券 ¥18.60").forEach {
            assertNull(it, NotificationParserRegistry.parse("com.tencent.mm", "微信支付", it))
            assertNull(it, NotificationParserRegistry.parse("com.eg.android.AlipayGphone", "支付宝交易提醒", it))
        }
    }

    @Test fun numericOrderAndTimeCannotBeUsedAsAmount() {
        listOf("付款完成，订单号：202610091860", "支付成功，时间：18:60", "付款金额：1.234，支付成功", "支付成功 ¥1.234", "付款金额：0，付款完成").forEach {
            assertNull(it, NotificationParserRegistry.parse("com.tencent.mm", "微信支付", it))
        }
    }

    @Test fun wechatAmountOnlyStaysUnclassified() {
        val result = NotificationParserRegistry.parse("com.tencent.mm", "微信支付", "微信支付 ¥26.50")
        assertNotNull(result)
        assertEquals(2650L, result!!.amountCents)
        assertEquals(TransactionType.EXPENSE, result.type)
        assertNull(result.merchant)
    }

    @Test fun alipayPrefersActualPaymentOverOriginalAndDiscount() {
        val result = NotificationParserRegistry.parse(
            "com.eg.android.AlipayGphone", "支付宝交易提醒",
            "向蜜雪冰城付款成功\n原价 ¥35 优惠 ¥10 实付 ¥25"
        )
        assertNotNull(result)
        assertEquals(2500L, result!!.amountCents)
        assertEquals("蜜雪冰城", result.merchant)
    }

    @Test fun ambiguousAmountsAreIgnored() {
        val result = NotificationParserRegistry.parse("com.tencent.mm", "微信支付", "付款成功 ¥25.00，另有 ¥26.00")
        assertNull(result)
    }

    @Test fun paymentNotificationsWithoutAnAmountAreIgnored() {
        assertNull(NotificationParserRegistry.parse("com.tencent.mm", "微信支付", "付款成功，点击查看详情"))
        assertNull(NotificationParserRegistry.parse("com.eg.android.AlipayGphone", "支付宝交易提醒", "付款成功，点击查看详情"))
        assertNull(NotificationParserRegistry.parse("com.tencent.mm", "微信支付", "第2笔支付成功，订单号123456，2026年10月9日"))
        assertNull(NotificationParserRegistry.parse("com.tencent.mm", "微信支付", "付款成功 ¥0.00"))
    }

    @Test fun wechatDailyAndMonthlyReportsAreIgnored() {
        assertNull(NotificationParserRegistry.parse("com.tencent.mm", "微信支付", "[2条]微信支付: 昨日记账日报，点击查看详情"))
        assertNull(NotificationParserRegistry.parse("com.tencent.mm", "微信支付", "记账月报，点击查看详情"))
        assertNull(NotificationParserRegistry.parse("com.tencent.mm", "微信支付", "昨日记账日报：昨日支出 ¥26.50，点击查看详情"))
        assertNull(NotificationParserRegistry.parse("com.tencent.mm", "微信支付", "月度账单：本月支出 ¥2650.00"))
    }

    @Test fun merchantNamesContainingReportWordsRemainValidPayments() {
        val result = NotificationParserRegistry.parse("com.tencent.mm", "微信支付", "向人民日报付款成功 ¥26.50")
        assertEquals(2650L, result?.amountCents)
        assertEquals("人民日报", result?.merchant)
    }

    @Test fun unrelatedNotificationsAreIgnored() {
        assertNull(NotificationParserRegistry.parse("com.tencent.mm", "好友", "微信支付 ¥26.50"))
        assertNull(NotificationParserRegistry.parse("com.eg.android.AlipayGphone", "支付宝", "红包 ¥10"))
        assertNull(NotificationParserRegistry.parse("other.package", "微信支付", "¥26.50"))
    }

    @Test fun refundIsDistinctFromIncome() {
        val result = NotificationParserRegistry.parse("com.eg.android.AlipayGphone", "支付宝交易提醒", "退款成功 ¥199")
        assertNotNull(result)
        assertEquals(TransactionType.REFUND, result!!.type)
        assertEquals(19900L, result.amountCents)
    }

    @Test fun wechatServiceNoticeWithExplicitPaymentIsRecognized() {
        val result = NotificationParserRegistry.parse("com.tencent.mm", "服务通知", "付款成功，金额 ¥18.60")
        assertEquals(1860L, result?.amountCents)
        assertEquals(TransactionType.EXPENSE, result?.type)
    }

    @Test fun alipayGenericTitleWithExplicitPaymentIsRecognized() {
        val result = NotificationParserRegistry.parse("com.eg.android.AlipayGphone", "服务通知", "付款成功 ¥8.80")
        assertEquals(880L, result?.amountCents)
        assertEquals(TransactionType.EXPENSE, result?.type)
    }

    @Test fun genericPromotionWithoutPaymentOutcomeIsIgnored() {
        assertNull(NotificationParserRegistry.parse("com.eg.android.AlipayGphone", "服务通知", "领取消费券 ¥8.80"))
        assertNull(NotificationParserRegistry.parse("com.tencent.mm", "服务通知", "满减优惠 ¥8.80"))
    }

    @Test fun officialTransfersAcceptLabeledAmountsButNotReportsOrMissingAmounts() {
        val wx = NotificationParserRegistry.parse("com.tencent.mm", "微信转账", "转账成功，转账金额：18.60")
        assertEquals(1860L, wx?.amountCents)
        assertEquals(TransactionType.TRANSFER, wx?.type)
        val ali = NotificationParserRegistry.parse("com.eg.android.AlipayGphone", "支付宝", "转账成功，转账金额：28.80")
        assertEquals(2880L, ali?.amountCents)
        assertEquals(TransactionType.TRANSFER, ali?.type)
        assertNull(NotificationParserRegistry.parse("com.tencent.mm", "微信转账", "转账成功，订单123456"))
        assertNull(NotificationParserRegistry.parse("com.tencent.mm", "微信支付", "转账记账日报 ¥28.80"))
        assertNull(NotificationParserRegistry.parse("com.eg.android.AlipayGphone", "支付宝", "转账失败 ¥28.80"))
    }
}
