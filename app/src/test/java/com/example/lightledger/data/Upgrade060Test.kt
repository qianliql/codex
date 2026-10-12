package com.example.lightledger.data

import android.app.Application
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.lightledger.backup.BackupCodec
import com.example.lightledger.ui.StatisticsPeriod
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.util.Calendar

@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = Application::class, sdk = [29])
class Upgrade060Test {
    private fun database() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LedgerDatabase::class.java).allowMainThreadQueries().build()
    private fun date(year: Int, month: Int) = Calendar.getInstance().apply { clear(); set(year, month - 1, 15, 12, 0) }.timeInMillis

    @Test fun annualAverageUsesDistinctIncomeOrExpenseMonthsAndUpdatesWhenBillsChange() = runBlocking {
        val db = database()
        try {
            val dao = db.dao()
            val rows = listOf(
                Triple(1, TransactionType.EXPENSE, 12000L), Triple(1, TransactionType.EXPENSE, 6000L),
                Triple(9, TransactionType.INCOME, 5000L), Triple(5, TransactionType.REFUND, 3000L),
                Triple(7, TransactionType.TRANSFER, 10000L))
            dao.insertTransactions(rows.mapIndexed { i, (month, type, cents) -> LedgerTransaction(id = i + 1L, amountCents = cents, type = type, timestamp = date(2026, month)) })
            dao.insertTransaction(LedgerTransaction(id = 20, amountCents = 90000, type = TransactionType.EXPENSE, timestamp = date(2025, 12)))
            val (start, end) = StatisticsPeriod(2026, null).bounds()
            val totals = dao.totals(start, end)
            assertEquals(MonthTotals(18000, 3000, 2), value(totals) { it.recordedMonths == 2 })
            assertEquals(7500L, value(totals) { it.recordedMonths == 2 }.averageExpense())
            dao.insertTransaction(LedgerTransaction(amountCents = 3000, type = TransactionType.EXPENSE, timestamp = date(2026, 12)))
            assertEquals(6000L, value(totals) { it.recordedMonths == 3 }.averageExpense())
            val emptyYear = StatisticsPeriod(2027, null).bounds()
            assertEquals(0L, value(dao.totals(emptyYear.first, emptyYear.second)) { it.recordedMonths == 0 }.averageExpense())
            val refundMonth = StatisticsPeriod(2026, 5).bounds()
            assertEquals(0, value(dao.totals(refundMonth.first, refundMonth.second)) { it.refund == 3000L }.recordedMonths)
        } finally { db.close() }
    }

    @Test fun priorityLimitsOrderBackupAndOldHouseholdRestorePreserveReferences() = runBlocking {
        val db = database()
        try {
            val dao = db.dao()
            listOf("正餐", "零食", "日用").forEachIndexed { i, name -> dao.saveCategoryPreferences(Category(name = name, priority = 0, sortOrder = i)) }
            assertTrue(runCatching { dao.saveCategoryPreferences(Category(name = "交通", priority = 0)) }.isFailure)
            val group = CategoryPriority.notification(dao.categoryList())
            dao.moveCategory(group.last().id, -1)
            assertEquals(listOf("正餐", "日用", "零食"), CategoryPriority.notification(dao.categoryList()).map { it.name })
            (1..8).forEach { dao.saveCategoryPreferences(Category(name = "常用$it", priority = 1)) }
            assertTrue(runCatching { dao.saveCategoryPreferences(Category(name = "第九个", priority = 1)) }.isFailure)
            assertFalse(dao.categoryList().any { it.name == "第九个" })
            val category = dao.categoryList().first()
            dao.saveCategoryPreferences(category.copy(priority = 2, iconKey = "car"))
            dao.insertCategory(Category(name = "未分类", priority = 2))
            val encoded = BackupCodec.encode(dao.snapshot())
            assertEquals(dao.snapshot(), BackupCodec.decode(encoded))
            val old = LedgerSnapshot(
                listOf(LedgerTransaction(id = 1, amountCents = 1234, type = TransactionType.EXPENSE, timestamp = date(2026, 9), categoryId = 10, note = "虚构备注")),
                listOf(Category(10, "家居"), Category(11, "未分类")), emptyList(), emptyList(),
                listOf(MerchantRule(1, "虚构商户", 10)), emptyList())
            val oldJson = JSONObject(BackupCodec.encode(old)).put("schemaVersion", 1)
            oldJson.getJSONArray("categories").let { array -> (0 until array.length()).forEach { i -> array.getJSONObject(i).apply { remove("priority"); remove("sortOrder"); remove("iconKey") } } }
            dao.replaceAll(BackupCodec.decode(oldJson.toString()))
            val other = dao.categoryList().single { it.name == "其他" }
            assertFalse(dao.categoryList().any { it.name == "家居" })
            assertEquals(other.id, dao.transaction(1)!!.categoryId)
            assertEquals("虚构备注", dao.transaction(1)!!.note)
            assertEquals(1234L, dao.transaction(1)!!.amountCents)
            assertEquals(other.id, dao.merchantRules().single().categoryId)
            assertTrue(dao.categoryList().count { it.priority == 1 } <= 8)
        } finally { db.close() }
    }

    @Test fun actualSchema6MigratesTo8KeepingBillsTagsRefundsRulesAndSettings() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-060.db"
        context.deleteDatabase(name)
        val schemaPath = "schemas/com.example.lightledger.data.LedgerDatabase/6.json"
        val file = listOf(File(schemaPath), File("app/$schemaPath")).first { it.exists() }
        val schema = JSONObject(file.readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(6) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val entities = schema.getJSONArray("entities")
                (0 until entities.length()).forEach { i ->
                    val entity = entities.getJSONObject(i)
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                    val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                    (0 until indices.length()).forEach { n -> db.execSQL(indices.getJSONObject(n).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName"))) }
                }
                db.execSQL("INSERT INTO categories VALUES(10,'家居',0),(11,'其他',1),(12,'正餐',1),(13,'零食',1),(14,'日用',1),(15,'未分类',1)")
                db.execSQL("INSERT INTO transactions(id,amountCents,type,categoryId,paymentMethod,timestamp,source,note,classificationStatus,refundOfTransactionId,createdAt,updatedAt) VALUES(1,5000,'EXPENSE',10,'OTHER',1000,'MANUAL','虚构备注','CLASSIFIED',NULL,1000,1000),(2,1000,'REFUND',10,'OTHER',2000,'MANUAL',NULL,'CLASSIFIED',1,2000,2000)")
                db.execSQL("INSERT INTO tags VALUES(1,'标签')")
                db.execSQL("INSERT INTO transaction_tags VALUES(1,1)")
                db.execSQL("INSERT INTO merchant_rules VALUES(1,'虚构商户',10,1)")
                db.execSQL("INSERT INTO settings VALUES('example','kept')")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }).build())
        helper.writableDatabase
        helper.close()
        val db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).addMigrations(LedgerDatabase.migration6To7, LedgerDatabase.migration7To8, LedgerDatabase.migration8To9, LedgerDatabase.migration9To10).allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            assertEquals(11L, dao.transaction(1)!!.categoryId)
            assertEquals(11L, dao.transaction(2)!!.categoryId)
            assertEquals(1L, dao.transaction(2)!!.refundOfTransactionId)
            assertEquals("虚构备注", dao.transaction(1)!!.note)
            assertEquals(11L, dao.merchantRules().single().categoryId)
            assertEquals(listOf(TransactionTagCrossRef(1, 1)), dao.allTransactionTags())
            assertEquals("kept", dao.setting("example"))
            assertFalse(dao.categoryList().any { it.name == "家居" })
            assertEquals(listOf("正餐", "零食", "日用"), CategoryPriority.notification(dao.categoryList()).map { it.name })
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun <T> value(live: LiveData<T>, ready: (T) -> Boolean): T {
        var result: T? = null
        val observer = Observer<T> { if (it != null && ready(it)) result = it }
        live.observeForever(observer)
        try {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (result == null && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(10) }
            return result ?: error("Statistics query timed out")
        } finally { live.removeObserver(observer) }
    }
}
