package com.example.lightledger.ocr

import com.example.lightledger.data.PaymentMethod
import com.example.lightledger.data.TransactionType
import org.junit.Assert.*
import org.junit.Test

class ScreenshotParserTest {
    @Test fun wechatPaymentUsesActualAmount() {
        val result = ScreenshotParserRegistry.parse("微信支付\n商户：麦当劳\n原价 ¥35\n优惠 ¥10\n实付 ¥25\n2026-09-30 18:32")!!
        assertEquals(2500L, result.amountCents)
        assertEquals("麦当劳", result.merchant)
        assertEquals(PaymentMethod.WECHAT, result.paymentMethod)
        assertEquals(TransactionType.EXPENSE, result.type)
        assertNotNull(result.timestamp)
    }

    @Test fun ambiguousGenericImageRequiresAmountConfirmation() {
        val result = ScreenshotParserRegistry.parse("交易详情\n¥35\n¥25")!!
        assertNull(result.amountCents)
        assertEquals(PaymentMethod.OTHER, result.paymentMethod)
    }

    @Test fun refundKeepsItsOwnType() {
        val result = ScreenshotParserRegistry.parse("支付宝\n退款成功\n退款金额 ¥199.00")!!
        assertEquals(TransactionType.REFUND, result.type)
        assertEquals(19900L, result.amountCents)
    }
}
