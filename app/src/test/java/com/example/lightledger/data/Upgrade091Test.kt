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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = Application::class, sdk = [29])
class Upgrade091Test {
    @Test fun schema7MigrationPreservesExistingIdsAndFinancialReferencesAndEmptyCategories() = runBlocking {
        listOf(false, true).forEach { empty ->
            val context = RuntimeEnvironment.getApplication(); val name = "migration-091-$empty.db"
            context.deleteDatabase(name)
            val schemaPath = "schemas/com.example.lightledger.data.LedgerDatabase/7.json"
            val schema = JSONObject(listOf(File(schemaPath), File("app/$schemaPath")).first { it.exists() }.readText()).getJSONObject("database")
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    (0 until entities.length()).forEach { i ->
                        val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        (0 until indices.length()).forEach { n -> db.execSQL(indices.getJSONObject(n).getString("createSql").replace("\${TABLE_NAME}", table)) }
                    }
                    if (!empty) {
                        db.execSQL("INSERT INTO categories VALUES(10,'工资',0,0,7,'work'),(11,'其他',1,1,8,'auto'),(12,'正餐',1,0,0,'food')")
                        db.execSQL("INSERT INTO merchant_rules VALUES(1,'虚构商户',12,1)")
                    }
                    val category = if (empty) "NULL" else "12"
                    db.execSQL("INSERT INTO transactions(id,amountCents,type,categoryId,paymentMethod,timestamp,source,note,classificationStatus,refundOfTransactionId,createdAt,updatedAt) VALUES(1,5000,'EXPENSE',$category,'WECHAT',1000,'MANUAL','虚构备注','CLASSIFIED',NULL,1000,1000),(2,1000,'REFUND',$category,'WECHAT',2000,'MANUAL',NULL,'CLASSIFIED',1,2000,2000)")
                    db.execSQL("INSERT INTO tags VALUES(1,'标签')"); db.execSQL("INSERT INTO transaction_tags VALUES(1,1)")
                    db.execSQL("INSERT INTO settings VALUES('example','kept')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
            helper.writableDatabase; helper.close()
            val db = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
                .addMigrations(LedgerDatabase.migration7To8).allowMainThreadQueries().build()
            try {
                val dao = db.dao(); val categories = dao.categoryList()
                if (empty) assertTrue(categories.isEmpty()) else {
                    assertEquals(10L, categories.single { it.name == "工资" }.id)
                    assertEquals(CategoryScope.INCOME, categories.single { it.name == "工资" }.scope)
                    assertEquals(7, categories.single { it.name == "工资" }.sortOrder)
                    assertEquals(CategoryScope.BOTH, categories.single { it.name == "其他" }.scope)
                    assertEquals(listOf("奖金", "生活费", "工资", "其他"), CategoryCatalog.choices(categories, TransactionType.INCOME).map { it.name })
                    assertEquals(listOf("正餐"), CategoryPriority.notification(categories).map { it.name })
                    assertEquals(12L, dao.merchantRules().single().categoryId)
                    dao.moveCategory(10, -1)
                    assertEquals(listOf("奖金", "工资", "生活费", "其他"), CategoryCatalog.choices(dao.categoryList(), TransactionType.INCOME).map { it.name })
                }
                assertEquals(listOf(1000L, 5000L), dao.allTransactionsOnce().map { it.amountCents })
                assertEquals("虚构备注", dao.transaction(1)!!.note)
                assertEquals(1L, dao.transaction(2)!!.refundOfTransactionId)
                assertEquals(listOf(TransactionTagCrossRef(1, 1)), dao.allTransactionTags())
                assertEquals("kept", dao.setting("example"))
                db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun renamedIncomeScopeSurvivesBackupAndDeleteDoesNotReappearOrDamageBill() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LedgerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.dao(); val repository = LedgerRepository(dao)
            val id = dao.insertCategory(Category(name = "工资", priority = 0))
            val category = dao.categoryList().single()
            dao.insertTransaction(LedgerTransaction(id = 1, amountCents = 12345, type = TransactionType.INCOME, categoryId = id, timestamp = 1000, note = "收入备注"))
            repository.renameCategory(category, "薪资")
            assertEquals(CategoryScope.INCOME, dao.categoryList().single().scope)
            assertTrue(CategoryCatalog.choices(dao.categoryList(), TransactionType.EXPENSE).isEmpty())
            assertTrue(CategoryPriority.notification(dao.categoryList()).isEmpty())
            val before = dao.snapshot(); val encoded = BackupCodec.encode(before)
            assertEquals(3, JSONObject(encoded).getInt("schemaVersion"))
            repository.restore(BackupCodec.decode(encoded)); assertEquals(before, dao.snapshot())
            dao.insertCategory(Category(name = "其他"))
            repository.deleteCategory(dao.categoryList().single { it.id == id })
            val after = dao.snapshot(); val result = after.transactions.single()
            assertNull(result.categoryId); assertEquals(ClassificationStatus.UNCLASSIFIED, result.classificationStatus)
            assertEquals(12345L, result.amountCents); assertEquals("收入备注", result.note)
            repository.restore(BackupCodec.decode(BackupCodec.encode(after)))
            assertEquals(after, dao.snapshot()); assertFalse(dao.categoryList().any { it.scope == CategoryScope.INCOME })
            val malformed = JSONObject(encoded).apply { getJSONArray("categories").getJSONObject(0).put("scope", "INVALID") }
            assertTrue(runCatching { BackupCodec.decode(malformed.toString()) }.isFailure)
            assertEquals(after, dao.snapshot())
        } finally { db.close() }
    }

    @Test fun legacyBackupsAddIncomeDefaultsOnceAndRespectEmptyLists() {
        val original = LedgerSnapshot(emptyList(), listOf(Category(20, "其他"), Category(21, "工资")), emptyList(), emptyList(), emptyList(), emptyList())
        listOf(1, 2).forEach { version ->
            val old = JSONObject(BackupCodec.encode(original)).put("schemaVersion", version)
            old.getJSONArray("categories").let { rows -> (0 until rows.length()).forEach { rows.getJSONObject(it).remove("scope") } }
            val migrated = BackupCodec.decode(old.toString())
            assertEquals(21L, migrated.categories.single { it.name == "工资" }.id)
            assertEquals(4, migrated.categories.size)
            assertEquals(migrated, BackupCodec.decode(BackupCodec.encode(migrated)))
            old.put("categories", org.json.JSONArray())
            assertTrue(BackupCodec.decode(old.toString()).categories.isEmpty())
        }
    }
}
