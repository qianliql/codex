package com.example.lightledger.refund

import com.example.lightledger.data.LedgerTransaction
import com.example.lightledger.data.PaymentMethod
import com.example.lightledger.data.TransactionType
import org.junit.Assert.*
import org.junit.Test

class RefundMatcherTest {
    private val now = 1_800_000_000_000L
    private fun expense(id: Long, amount: Long, merchant: String?, time: Long = now - 86_400_000) =
        LedgerTransaction(id = id, amountCents = amount, type = TransactionType.EXPENSE,
            merchant = merchant, paymentMethod = PaymentMethod.ALIPAY, timestamp = time)
    private fun refund(amount: Long, merchant: String?) =
        LedgerTransaction(amountCents = amount, type = TransactionType.REFUND,
            merchant = merchant, paymentMethod = PaymentMethod.ALIPAY, timestamp = now)

    @Test fun uniqueExactMerchantAndAmountCanLink() {
        assertEquals(1L, RefundMatcher.confidentMatch(refund(19900, "淘宝退款"),
            listOf(expense(1, 19900, "淘宝"), expense(2, 19900, "其他商户")))?.id)
    }

    @Test fun ambiguousOrPartialRefundStaysUnlinked() {
        assertNull(RefundMatcher.confidentMatch(refund(19900, "淘宝"),
            listOf(expense(1, 19900, "淘宝"), expense(2, 19900, "淘宝"))))
        assertNull(RefundMatcher.confidentMatch(refund(5000, "淘宝"), listOf(expense(1, 19900, "淘宝"))))
        assertEquals(1, RefundMatcher.candidates(refund(5000, "淘宝"), listOf(expense(1, 19900, "淘宝"))).size)
    }
}
