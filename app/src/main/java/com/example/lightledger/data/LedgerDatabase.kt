package com.example.lightledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [LedgerTransaction::class, Category::class, Tag::class, TransactionTagCrossRef::class, MerchantRule::class, AppSetting::class], version = 6, exportSchema = true)
@TypeConverters(LedgerConverters::class)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN notificationKey TEXT")
                db.execSQL("CREATE UNIQUE INDEX index_transactions_notificationKey ON transactions(notificationKey)")
            }
        }
        private val migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN ocrText TEXT")
            }
        }
        private val migration3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS tags (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tags_name ON tags(name)")
                db.execSQL("CREATE TABLE IF NOT EXISTS transaction_tags (transactionId INTEGER NOT NULL, tagId INTEGER NOT NULL, PRIMARY KEY(transactionId, tagId), FOREIGN KEY(transactionId) REFERENCES transactions(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(tagId) REFERENCES tags(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transaction_tags_tagId ON transaction_tags(tagId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS merchant_rules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, keyword TEXT NOT NULL, categoryId INTEGER NOT NULL, exactMatch INTEGER NOT NULL, FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_rules_keyword ON merchant_rules(keyword)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_rules_categoryId ON merchant_rules(categoryId)")
            }
        }
        private val migration4To5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS settings (`key` TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(`key`))")
            }
        }
        private val migration5To6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE transactions SET categoryId=NULL, classificationStatus='UNCLASSIFIED' WHERE categoryId IN (SELECT id FROM categories WHERE name IN ('餐饮','交通','购物','生活','医疗','通讯','住房'))")
                db.execSQL("DELETE FROM categories WHERE name IN ('餐饮','交通','购物','生活','医疗','通讯','住房')")
                CategoryCatalog.primary
                    .forEach { name ->
                        db.execSQL("INSERT OR IGNORE INTO categories(name, isSystem) VALUES(?, 1)", arrayOf(name))
                    }
            }
        }
        @Volatile private var instance: LedgerDatabase? = null
        fun get(context: Context): LedgerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, "ledger.db")
                .addMigrations(migration1To2, migration2To3, migration3To4, migration4To5, migration5To6)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        (CategoryCatalog.primary + CategoryCatalog.unclassified)
                            .forEach { name ->
                                db.execSQL("INSERT INTO categories(name, isSystem) VALUES(?, 1)", arrayOf(name))
                            }
                    }
                }).build().also { instance = it }
        }
    }
}
