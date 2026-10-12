package com.example.lightledger.data

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.lightledger.backup.BackupCodec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = Application::class, sdk = [29, 35])
class AmountConfirmation0102Test {
    @Test fun categoryChoiceDoesNotConfirmAmountAndReplayedNotificationDoesNotUndoConfirmation() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LedgerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.dao(); val repo = LedgerRepository(dao)
            val category = dao.insertCategory(Category(name = "正餐"))
            repo.addMerchantRule("虚构餐厅", category, true)
            val event = LedgerTransaction(amountCents = 1250, type = TransactionType.EXPENSE, timestamp = 1_800_000_000_000,
                merchant = "虚构餐厅", source = TransactionSource.NOTIFICATION, paymentMethod = PaymentMethod.WECHAT, notificationKey = "synthetic-0102", note = "保留备注")
            val id = repo.saveNotification(event)!!
            val saved = repo.transaction(id)!!
            assertEquals(ClassificationStatus.CLASSIFIED, saved.classificationStatus)
            assertFalse(saved.amountConfirmed); assertTrue(saved.requiresAmountConfirmation())
            assertEquals(1250L, saved.amountCents)
            assertEquals(listOf(id), repo.searchTransactions("", 0, Long.MAX_VALUE, -1, 0, "", "", "", true, 100, 0).map { it.transaction.id })
            assertTrue(repo.classifyExpense(id, category).not()) // Already classified by the merchant rule.
            dao.updateTransaction(saved.copy(classificationStatus = ClassificationStatus.UNCLASSIFIED))
            assertTrue(repo.classifyExpense(id, category))
            assertFalse(repo.transaction(id)!!.amountConfirmed)
            repo.save(repo.transaction(id)!!.copy(amountConfirmed = true))
            assertNull(repo.saveNotification(event))
            assertTrue(repo.transaction(id)!!.amountConfirmed)
            assertEquals("保留备注", repo.transaction(id)!!.note)
            listOf(TransactionType.INCOME, TransactionType.TRANSFER, TransactionType.REFUND).forEachIndexed { i, type ->
                repo.saveNotification(event.copy(type = type, merchant = null, timestamp = event.timestamp + i + 1,
                    paymentMethod = PaymentMethod.ALIPAY, notificationKey = "synthetic-type-$i"))
            }
            repo.save(event.copy(source = TransactionSource.MANUAL, notificationKey = null))
            repo.save(event.copy(source = TransactionSource.SCREENSHOT, notificationKey = "synthetic-import"))
            val pending = repo.searchTransactions("", 0, Long.MAX_VALUE, -1, 0, "", "", "", false, 100, 0, true)
            assertEquals(3, pending.size)
            assertTrue(pending.all { it.transaction.source == TransactionSource.NOTIFICATION && it.transaction.paymentMethod == PaymentMethod.ALIPAY })
            assertEquals(1, repo.searchTransactions("", 0, Long.MAX_VALUE, -1, 0, "", "", "INCOME", false, 100, 0, true).size)
        } finally { db.close() }
    }

    @Test fun schema8MigrationOnlyAddsConfirmationStateAndPreservesFinancialReferences() = runBlocking {
        val context = RuntimeEnvironment.getApplication(); val name = "amount-migration-0102.db"
        context.deleteDatabase(name)
        context.getDatabasePath(name).parentFile!!.mkdirs()
        val path = "schemas/com.example.lightledger.data.LedgerDatabase/8.json"
        val schema = JSONObject(listOf(File(path), File("app/$path")).first { it.exists() }.readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(8) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val entities = schema.getJSONArray("entities")
                (0 until entities.length()).forEach { i ->
                    val entity = entities.getJSONObject(i)
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                    val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                    (0 until indices.length()).forEach { n -> db.execSQL(indices.getJSONObject(n).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName"))) }
                }
                db.execSQL("INSERT INTO categories VALUES(10,'正餐',1,0,7,'meal','EXPENSE')")
                db.execSQL("INSERT INTO transactions(id,amountCents,type,categoryId,paymentMethod,timestamp,source,note,classificationStatus,refundOfTransactionId,notificationKey,createdAt,updatedAt) VALUES(1,5000,'EXPENSE',10,'WECHAT',1000,'NOTIFICATION','虚构备注','CLASSIFIED',NULL,'synthetic-migration',1000,1000),(2,1000,'REFUND',10,'WECHAT',2000,'MANUAL',NULL,'CLASSIFIED',1,NULL,2000,2000),(3,2000,'EXPENSE',10,'OTHER',3000,'SCREENSHOT','导入备注','CLASSIFIED',NULL,'history:synthetic',3000,3000)")
                db.execSQL("INSERT INTO tags VALUES(1,'虚构标签')")
                db.execSQL("INSERT INTO transaction_tags VALUES(1,1)")
                db.execSQL("INSERT INTO merchant_rules VALUES(1,'虚构商户',10,1)")
                db.execSQL("INSERT INTO settings VALUES('example','kept')")
                db.execSQL("INSERT INTO transactions(id,amountCents,type,categoryId,paymentMethod,timestamp,source,note,classificationStatus,createdAt,updatedAt) VALUES(4,3456,'EXPENSE',10,'ALIPAY',4000,'NOTIFICATION','用户已修改','CLASSIFIED',4000,5000)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }).build())
        helper.writableDatabase; helper.close()
        // The Windows native SQLite fixture cannot switch a separately created file to WAL.
        val db = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(LedgerDatabase.migration8To9, LedgerDatabase.migration9To10).allowMainThreadQueries().build()
        try {
            val dao = db.dao(); val bills = dao.allTransactionsOnce().associateBy { it.id }
            assertFalse(bills.getValue(1).amountConfirmed)
            assertTrue(bills.getValue(2).amountConfirmed); assertTrue(bills.getValue(3).amountConfirmed)
            assertTrue(bills.getValue(4).amountConfirmed)
            assertEquals(3456L, bills.getValue(4).amountCents); assertEquals("用户已修改", bills.getValue(4).note)
            assertEquals(5000L, bills.getValue(4).updatedAt)
            assertEquals(5000L, bills.getValue(1).amountCents); assertEquals("虚构备注", bills.getValue(1).note)
            assertEquals(1000L, bills.getValue(1).updatedAt); assertEquals("synthetic-migration", bills.getValue(1).notificationKey)
            assertEquals(ClassificationStatus.CLASSIFIED, bills.getValue(1).classificationStatus)
            assertEquals(1L, bills.getValue(2).refundOfTransactionId)
            assertEquals(listOf(TransactionTagCrossRef(1, 1)), dao.allTransactionTags())
            assertEquals(10L, dao.merchantRules().single().categoryId); assertEquals("kept", dao.setting("example"))
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun preReleaseSchema9RepairRemovesOnlyPreviouslyEditedAutomaticBills() = runBlocking {
        val context = RuntimeEnvironment.getApplication(); val name = "repair-0102.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).allowMainThreadQueries().build()
        val untouched = LedgerTransaction(id = 1, amountCents = 1234, type = TransactionType.EXPENSE, timestamp = 1000,
            source = TransactionSource.NOTIFICATION, note = "未修改", amountConfirmed = false, createdAt = 1000, updatedAt = 1000)
        db.dao().insertTransactions(listOf(untouched, untouched.copy(id = 2, note = "已修改", updatedAt = 2000),
            untouched.copy(id = 3, source = TransactionSource.SCREENSHOT)))
        // Schema9 and schema10 have identical columns and identity hashes.
        // Reopen the same real WAL file with the earlier version number to exercise 9 -> 10.
        db.openHelper.writableDatabase.version = 9
        db.close()
        val repaired = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
            .addMigrations(LedgerDatabase.migration9To10).allowMainThreadQueries().build()
        try {
            assertFalse(repaired.dao().transaction(1)!!.amountConfirmed)
            assertTrue(repaired.dao().transaction(2)!!.amountConfirmed)
            assertFalse(repaired.dao().transaction(3)!!.amountConfirmed)
            assertEquals("已修改", repaired.dao().transaction(2)!!.note)
            assertEquals(1234L, repaired.dao().transaction(2)!!.amountCents)
        } finally { repaired.close(); context.deleteDatabase(name) }
    }

    @Test fun backupPreservesConfirmedAndPendingStatesAndReadsLegacyWithoutLosingBills() {
        val pending = LedgerTransaction(id = 1, amountCents = 5000, type = TransactionType.EXPENSE, timestamp = 1000,
            source = TransactionSource.NOTIFICATION, note = "虚构备注", amountConfirmed = false, createdAt = 1000, updatedAt = 1000)
        val data = LedgerSnapshot(listOf(pending, pending.copy(id = 2, amountConfirmed = true),
            pending.copy(id = 3, source = TransactionSource.SCREENSHOT, amountConfirmed = true),
            pending.copy(id = 4, updatedAt = 2000)), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val encoded = BackupCodec.encode(data)
        assertEquals(4, JSONObject(encoded).getInt("schemaVersion"))
        assertEquals(data, BackupCodec.decode(encoded))
        (1..3).forEach { version ->
            val old = JSONObject(encoded).put("schemaVersion", version)
            val rows = old.getJSONArray("transactions")
            (0 until rows.length()).forEach { rows.getJSONObject(it).remove("amountConfirmed") }
            val restored = BackupCodec.decode(old.toString())
            assertEquals(data.transactions.map { it.amountCents }, restored.transactions.map { it.amountCents })
            assertEquals(data.transactions.map { it.note }, restored.transactions.map { it.note })
            assertEquals(listOf(false, false, true, true), restored.transactions.map { it.amountConfirmed })
        }
        val broken = JSONObject(encoded).apply { getJSONArray("transactions").getJSONObject(0).remove("amountConfirmed") }
        assertTrue(runCatching { BackupCodec.decode(broken.toString()) }.isFailure)
    }
}
