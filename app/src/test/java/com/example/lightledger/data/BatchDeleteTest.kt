package com.example.lightledger.data

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class BatchDeleteTest {
    private fun transaction(id: Long, type: TransactionType = TransactionType.EXPENSE, refundOf: Long? = null) =
        LedgerTransaction(id = id, amountCents = 100, type = type, timestamp = id,
            refundOfTransactionId = refundOf, classificationStatus = ClassificationStatus.CLASSIFIED)

    private fun database() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LedgerDatabase::class.java).allowMainThreadQueries().build()

    @Test fun selectedBillsOnlyAreDeletedAndRemainingRefundsAndTagsStayValid() = runBlocking {
        val db = database()
        try {
            val dao = db.dao()
            dao.insertTransactions(listOf(transaction(1), transaction(2), transaction(3, TransactionType.REFUND, 1), transaction(4, TransactionType.REFUND, 2)))
            val tag = dao.insertTag(Tag(name = "测试标签"))
            dao.replaceTransactionTags(1, listOf(tag))
            dao.replaceTransactionTags(2, listOf(tag))
            assertEquals(1, dao.deleteTransactionsSafely(listOf(1, 1, 0, -1)))
            assertNull(dao.transaction(1))
            assertNotNull(dao.transaction(2))
            assertNull(dao.transaction(3)!!.refundOfTransactionId)
            assertEquals(ClassificationStatus.NEED_CONFIRM, dao.transaction(3)!!.classificationStatus)
            assertEquals(2L, dao.transaction(4)!!.refundOfTransactionId)
            assertEquals(listOf(TransactionTagCrossRef(2, tag)), dao.allTransactionTags())
            assertEquals(0, dao.deleteTransactionsSafely(emptyList()))
            assertEquals(2, dao.deleteTransactionsSafely(listOf(2, 4)))
            assertEquals(listOf(3L), dao.allTransactionsOnce().map { it.id })
        } finally { db.close() }
    }

    @Test fun moreThanSqliteBindingLimitCanBeDeleted() = runBlocking {
        val db = database()
        try {
            val dao = db.dao()
            dao.insertTransactions((1L..1005L).map { transaction(it) })
            assertEquals(1001, dao.deleteTransactionsSafely((1L..1001L).toList()))
            assertEquals(setOf(1002L, 1003L, 1004L, 1005L), dao.allTransactionsOnce().map { it.id }.toSet())
        } finally { db.close() }
    }

    @Test fun failureInLaterChunkRollsBackAllDeletesAndRefundUnlinking() = runBlocking {
        val db = database()
        try {
            val dao = db.dao()
            dao.insertTransactions((1L..401L).map { transaction(it) } + transaction(402, TransactionType.REFUND, 1))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_delete BEFORE DELETE ON transactions WHEN OLD.id = 401 BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            assertTrue(runCatching { dao.deleteTransactionsSafely((1L..401L).toList()) }.isFailure)
            assertEquals(402, dao.allTransactionsOnce().size)
            assertEquals(1L, dao.transaction(402)!!.refundOfTransactionId)
            assertEquals(ClassificationStatus.CLASSIFIED, dao.transaction(402)!!.classificationStatus)
        } finally { db.close() }
    }

    @Test fun categoryRenamePreservesBillAndMerchantRuleReferences() = runBlocking {
        val db = database()
        try {
            val dao = db.dao()
            val category = Category(id = 1, name = "正餐", isSystem = true)
            dao.insertCategory(category)
            dao.insertTransaction(transaction(1).copy(categoryId = 1))
            dao.insertMerchantRule(MerchantRule(keyword = "测试商户", categoryId = 1))
            LedgerRepository(dao).renameCategory(category, "三餐")
            assertEquals("三餐", dao.categoryList().single().name)
            assertEquals(1L, dao.transaction(1)!!.categoryId)
            assertEquals(1L, dao.merchantRules().single().categoryId)
        } finally { db.close() }
    }
}
