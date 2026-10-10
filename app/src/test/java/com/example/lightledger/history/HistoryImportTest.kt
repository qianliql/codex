package com.example.lightledger.history

import android.app.Application
import androidx.room.Room
import com.example.lightledger.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class HistoryImportTest {
    private fun bill(key: String, note: String? = "测试备注…", category: String = "购物") =
        HistoryBill(key, 1788758400000L, 1100, TransactionType.EXPENSE, category, note, "09/07 16:26 | 测试备注…")
    private fun packet(rows: List<HistoryBill>) = HistoryImport("历史账单", rows.size, rows.sumOf { it.amountCents }, rows)
    private fun database() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LedgerDatabase::class.java).allowMainThreadQueries().build()
    private fun encode(data: HistoryImport): JSONObject = JSONObject().put("format", "lightledger.history.v1").put("title", data.title)
        .put("expectedCount", data.expectedCount).put("expectedTotalCents", data.expectedTotalCents).put("records", JSONArray().apply {
            data.records.forEach { r -> put(JSONObject().put("importId", r.importId).put("timestamp", r.timestamp)
                .put("amountCents", r.amountCents).put("type", r.type.name).put("category", r.category)
                .put("note", r.note ?: JSONObject.NULL).put("sourceText", r.sourceText ?: JSONObject.NULL)) }
        })

    @Test fun decoderPreservesNotesAndRejectsBrokenTotalsOrIdentifiers() {
        val data = packet(listOf(bill("a", "购买纸巾 | 留作备用"), bill("b", "原图截断...")))
        assertEquals(data, HistoryImportCodec.decode(encode(data).toString()))
        assertTrue(runCatching { HistoryImportCodec.decode(encode(data).put("expectedTotalCents", 999).toString()) }.isFailure)
        assertTrue(runCatching { HistoryImportCodec.decode(encode(data).put("expectedCount", 3).toString()) }.isFailure)
        val decimal = encode(data); decimal.getJSONArray("records").getJSONObject(0).put("amountCents", 11.5)
        assertTrue(runCatching { HistoryImportCodec.decode(decimal.toString()) }.isFailure)
        val duplicate = packet(listOf(bill("a"), bill("a")))
        assertTrue(runCatching { HistoryImportCodec.decode(encode(duplicate).toString()) }.isFailure)
        val negative = packet(listOf(bill("a").copy(amountCents = -100)))
        assertTrue(runCatching { HistoryImportCodec.decode(encode(negative).toString()) }.isFailure)
    }

    @Test fun appendRetainsExistingBillsTagsRulesSettingsAndExactNotes() = runBlocking {
        val db = database()
        try {
            val dao = db.dao()
            val category = dao.insertCategory(Category(name = "正餐", isSystem = true))
            val existing = LedgerTransaction(id = 50, amountCents = 900, type = TransactionType.EXPENSE,
                categoryId = category, timestamp = 1791529200000L, note = "已有备注", notificationKey = "existing")
            dao.insertTransaction(existing)
            val tag = dao.insertTag(Tag(name = "保留标签")); dao.replaceTransactionTags(50, listOf(tag))
            dao.insertMerchantRule(MerchantRule(keyword = "原商户", categoryId = category))
            dao.putSetting(AppSetting("hide_amounts", "true"))
            val before = dao.snapshot()
            val data = packet(listOf(bill("a", "购买纸巾"), bill("b", null, "正餐")))
            assertEquals(HistoryImportResult(2, 0, 2200), dao.appendHistory(data))
            assertEquals(existing, dao.transaction(50))
            assertEquals(before.tags, dao.tags()); assertEquals(before.merchantRules, dao.merchantRules())
            assertEquals(before.settings, dao.settings()); assertEquals(before.transactionTags, dao.allTransactionTags())
            val imported = dao.transactionForKey("history:a")!!
            assertEquals("购买纸巾", imported.note); assertNull(imported.merchant)
            assertEquals(PaymentMethod.OTHER, imported.paymentMethod)
            assertEquals("购物", dao.categoryList().first { it.id == imported.categoryId }.name)
            assertEquals(HistoryImportResult(0, 2, 0), dao.appendHistory(data))
            assertEquals(3, dao.allTransactionsOnce().size)
        } finally { db.close() }
    }

    @Test fun separateOriginalRowsWithSameMinuteAmountAndNoteStaySeparate() = runBlocking {
        val db = database()
        try {
            val data = packet(listOf(bill("first", "公交", "交通"), bill("second", "公交", "交通")))
            assertEquals(2, db.dao().appendHistory(data).imported)
            assertEquals(2, db.dao().allTransactionsOnce().size)
            assertEquals(1, db.dao().categoryList().size)
            assertEquals(2, db.dao().appendHistory(data).skipped)
        } finally { db.close() }
    }

    @Test fun laterFailureRollsBackBillsAndNewCategories() = runBlocking {
        val db = database()
        try {
            val dao = db.dao()
            dao.insertCategory(Category(name = "原分类"))
            dao.insertTransaction(LedgerTransaction(id = 1, amountCents = 100, type = TransactionType.EXPENSE, timestamp = 100))
            val before = dao.snapshot()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON transactions WHEN NEW.notificationKey = 'history:second' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            assertTrue(runCatching { dao.appendHistory(packet(listOf(bill("first"), bill("second", category = "交通")))) }.isFailure)
            assertEquals(before, dao.snapshot())
        } finally { db.close() }
    }

    @Test fun inconsistentConstructedPacketCannotWrite() = runBlocking {
        val db = database()
        try {
            val invalid = packet(listOf(bill("a"))).copy(expectedCount = 2)
            assertTrue(runCatching { db.dao().appendHistory(invalid) }.isFailure)
            assertTrue(db.dao().allTransactionsOnce().isEmpty()); assertTrue(db.dao().categoryList().isEmpty())
        } finally { db.close() }
    }
}
