package com.example.lightledger.data

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = Application::class, sdk = [29])
class TransactionAggregationTest {
    private fun bill(id: Long, cents: Long, time: Long, method: PaymentMethod,
        type: TransactionType = TransactionType.EXPENSE, note: String? = null) =
        LedgerTransaction(id = id, amountCents = cents, type = type, timestamp = time,
            paymentMethod = method, note = note)

    @Test fun previewSumsAmountsAndUsesLatestTimeAndMostFrequentPaymentCount() {
        val preview = TransactionAggregation.preview(listOf(
            bill(1, 125, 1000, PaymentMethod.ALIPAY, note = "早餐"),
            bill(2, 225, 3000, PaymentMethod.WECHAT, note = "午餐"),
            bill(3, 350, 2000, PaymentMethod.ALIPAY, note = "早餐"),
        ))
        assertEquals(700L, preview.amountCents)
        assertEquals(3000L, preview.timestamp)
        assertEquals(PaymentMethod.ALIPAY, preview.paymentMethod)
        assertEquals("早餐；午餐", preview.note)
        val tie = TransactionAggregation.preview(listOf(
            bill(4, 100, 1000, PaymentMethod.WECHAT), bill(5, 100, 2000, PaymentMethod.ALIPAY)))
        assertEquals(PaymentMethod.ALIPAY, tie.paymentMethod)
    }

    @Test fun rejectsMixedTypesUnconfirmedAndRefunds() {
        assertTrue(runCatching { TransactionAggregation.preview(listOf(
            bill(1, 100, 1, PaymentMethod.WECHAT), bill(2, 100, 2, PaymentMethod.WECHAT, TransactionType.INCOME))) }.isFailure)
        assertTrue(runCatching { TransactionAggregation.preview(listOf(
            bill(1, 100, 1, PaymentMethod.WECHAT), bill(2, 100, 2, PaymentMethod.WECHAT).copy(amountConfirmed = false))) }.isFailure)
        assertTrue(runCatching { TransactionAggregation.preview(listOf(
            bill(1, 100, 1, PaymentMethod.WECHAT), bill(2, 100, 2, PaymentMethod.WECHAT).copy(refundOfTransactionId = 9))) }.isFailure)
    }

    @Test fun replacementIsAtomicKeepsTagsAndBlocksLinkedRefunds() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LedgerDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            dao.insertTransactions(listOf(bill(1, 125, 1000, PaymentMethod.WECHAT)
                .copy(source = TransactionSource.NOTIFICATION, notificationKey = "sample-key"),
                bill(2, 225, 3000, PaymentMethod.ALIPAY), bill(3, 500, 4000, PaymentMethod.WECHAT)))
            val tag = dao.insertTag(Tag(name = "日常"))
            val secondTag = dao.insertTag(Tag(name = "购物"))
            dao.replaceTransactionTags(1, listOf(tag))
            dao.replaceTransactionTags(2, listOf(secondTag))
            val replacement = bill(0, 350, 3000, PaymentMethod.ALIPAY, note = "聚合")
            val mergedId = dao.replaceWithAggregate(listOf(1, 2), listOf(dao.transaction(1)!!, dao.transaction(2)!!),
                replacement, listOf(tag, secondTag))
            assertEquals(setOf(mergedId, 3L), dao.allTransactionsOnce().map { it.id }.toSet())
            assertEquals(setOf(tag, secondTag), dao.tagIdsForTransaction(mergedId).toSet())
            assertEquals(-1L, dao.insertNotificationUnlessRetired(bill(0, 125, 1000, PaymentMethod.WECHAT)
                .copy(source = TransactionSource.NOTIFICATION, notificationKey = "sample-key")))
            assertTrue(runCatching { dao.replaceWithAggregate(listOf(mergedId, 3),
                listOf(dao.transaction(mergedId)!!, dao.transaction(3)!!), replacement, listOf(999L)) }.isFailure)
            assertEquals(2, dao.allTransactionsOnce().size)
            val refund = bill(100, 100, 5000, PaymentMethod.WECHAT, TransactionType.REFUND)
                .copy(refundOfTransactionId = 3)
            dao.insertTransaction(refund)
            assertTrue(runCatching { dao.replaceWithAggregate(listOf(mergedId, 3),
                listOf(dao.transaction(mergedId)!!, dao.transaction(3)!!), replacement, emptyList()) }.isFailure)
            assertEquals(3L, dao.transaction(100)!!.refundOfTransactionId)
        } finally { db.close() }
    }
}
