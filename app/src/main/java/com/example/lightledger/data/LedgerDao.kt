package com.example.lightledger.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface LedgerDao {
    @Query("SELECT * FROM transactions WHERE notificationKey = :key LIMIT 1")
    suspend fun transactionForKey(key: String): LedgerTransaction?

    @Transaction suspend fun appendHistory(data: com.example.lightledger.history.HistoryImport): com.example.lightledger.history.HistoryImportResult {
        com.example.lightledger.history.HistoryImportCodec.validate(data)
        val categoryIds = categoryList().associate { it.name to it.id }.toMutableMap()
        var imported = 0
        var skipped = 0
        var addedCents = 0L
        data.records.forEach { row ->
            val key = "history:${row.importId}"
            if (transactionForKey(key) != null) {
                skipped++
            } else {
                val categoryId = categoryIds.getOrPut(row.category) { 0L }.let { existing ->
                    if (existing > 0) existing else insertCategory(Category(name = row.category)).also { categoryIds[row.category] = it }
                }
                insertTransaction(LedgerTransaction(amountCents = row.amountCents, type = row.type, merchant = null,
                    categoryId = categoryId, paymentMethod = PaymentMethod.OTHER, timestamp = row.timestamp,
                    source = TransactionSource.SCREENSHOT, note = row.note, notificationKey = key, ocrText = row.sourceText,
                    classificationStatus = if (row.category == CategoryCatalog.unclassified) ClassificationStatus.UNCLASSIFIED else ClassificationStatus.CLASSIFIED))
                imported++
                addedCents += row.amountCents
            }
        }
        return com.example.lightledger.history.HistoryImportResult(imported, skipped, addedCents)
    }
    @Query("SELECT * FROM categories ORDER BY CASE name WHEN '正餐' THEN 0 WHEN '零食' THEN 1 WHEN '娱乐' THEN 2 WHEN '日用' THEN 3 WHEN '数码' THEN 4 WHEN '洗澡' THEN 5 WHEN '教育' THEN 6 WHEN '其他' THEN 7 WHEN '未分类' THEN 999 ELSE 100 END, id")
    fun categories(): LiveData<List<Category>>

    @Query("SELECT * FROM categories ORDER BY CASE name WHEN '正餐' THEN 0 WHEN '零食' THEN 1 WHEN '娱乐' THEN 2 WHEN '日用' THEN 3 WHEN '数码' THEN 4 WHEN '洗澡' THEN 5 WHEN '教育' THEN 6 WHEN '其他' THEN 7 WHEN '未分类' THEN 999 ELSE 100 END, id")
    suspend fun categoryList(): List<Category>

    @Insert suspend fun insertCategory(category: Category): Long
    @Update suspend fun updateCategory(category: Category)
    @Delete suspend fun deleteCategory(category: Category)
    @Query("UPDATE transactions SET categoryId = NULL, classificationStatus = 'UNCLASSIFIED', updatedAt = :now WHERE categoryId = :categoryId")
    suspend fun unclassifyCategoryTransactions(categoryId: Long, now: Long)
    @Transaction suspend fun removeCategory(category: Category) {
        unclassifyCategoryTransactions(category.id, System.currentTimeMillis())
        deleteCategory(category)
    }

    @Query("SELECT t.*, c.name AS categoryName FROM transactions t LEFT JOIN categories c ON t.categoryId = c.id ORDER BY t.timestamp DESC, t.id DESC LIMIT :limit")
    fun recentTransactions(limit: Int): LiveData<List<TransactionRow>>

    @Query("SELECT t.*, c.name AS categoryName FROM transactions t LEFT JOIN categories c ON t.categoryId = c.id ORDER BY t.timestamp DESC, t.id DESC")
    fun allTransactions(): LiveData<List<TransactionRow>>

    @Query("SELECT COUNT(*) FROM transactions")
    fun transactionChangeSignal(): LiveData<Int>

    @Query("""SELECT t.*, c.name AS categoryName FROM transactions t
        LEFT JOIN categories c ON t.categoryId = c.id
        WHERE (:search = '' OR instr(COALESCE(t.merchant, ''), :search) > 0 OR instr(COALESCE(t.note, ''), :search) > 0)
        AND t.timestamp >= :start AND t.timestamp < :end
        AND (:categoryId = -1 OR (:categoryId = 0 AND t.categoryId IS NULL) OR t.categoryId = :categoryId)
        AND (:tagId = 0 OR EXISTS (SELECT 1 FROM transaction_tags tt WHERE tt.transactionId = t.id AND tt.tagId = :tagId))
        AND (:method = '' OR t.paymentMethod = :method)
        AND (:source = '' OR t.source = :source)
        AND (:type = '' OR t.type = :type)
        AND (:pendingOnly = 0 OR t.classificationStatus != 'CLASSIFIED')
        ORDER BY t.timestamp DESC, t.id DESC LIMIT :limit OFFSET :offset""")
    suspend fun searchTransactions(search: String, start: Long, end: Long, categoryId: Long,
        tagId: Long, method: String, source: String, type: String, pendingOnly: Boolean,
        limit: Int, offset: Int): List<TransactionRow>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun transaction(id: Long): LedgerTransaction?

    @Query("SELECT * FROM transactions WHERE amountCents = :amountCents AND type = :type AND timestamp BETWEEN :start AND :end AND (:method = 'OTHER' OR paymentMethod = :method OR paymentMethod = 'OTHER') ORDER BY ABS(timestamp - :targetTime)")
    suspend fun possibleDuplicates(amountCents: Long, type: TransactionType, method: PaymentMethod,
        start: Long, end: Long, targetTime: Long): List<LedgerTransaction>

    @Query("SELECT * FROM transactions e WHERE e.type = 'EXPENSE' AND e.timestamp BETWEEN :start AND :end AND e.amountCents >= :refundAmount + COALESCE((SELECT SUM(r.amountCents) FROM transactions r WHERE r.type = 'REFUND' AND r.refundOfTransactionId = e.id AND r.id != :refundId), 0) ORDER BY e.timestamp DESC")
    suspend fun refundCandidates(refundAmount: Long, start: Long, end: Long, refundId: Long): List<LedgerTransaction>
    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM transactions WHERE type = 'REFUND' AND refundOfTransactionId = :expenseId")
    suspend fun linkedRefundTotal(expenseId: Long): Long

    @Query("SELECT * FROM tags ORDER BY name")
    suspend fun tags(): List<Tag>
    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun tag(id: Long): Tag?
    @Insert suspend fun insertTag(tag: Tag): Long
    @Delete suspend fun deleteTag(tag: Tag)
    @Query("SELECT tagId FROM transaction_tags WHERE transactionId = :transactionId")
    suspend fun tagIdsForTransaction(transactionId: Long): List<Long>
    @Query("DELETE FROM transaction_tags WHERE transactionId = :transactionId")
    suspend fun clearTransactionTags(transactionId: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransactionTags(refs: List<TransactionTagCrossRef>)
    @Transaction suspend fun replaceTransactionTags(transactionId: Long, tagIds: List<Long>) {
        clearTransactionTags(transactionId)
        insertTransactionTags(tagIds.map { TransactionTagCrossRef(transactionId, it) })
    }
    @Query("SELECT tags.id, tags.name, COALESCE(SUM(CASE WHEN transactions.type = 'EXPENSE' THEN transactions.amountCents WHEN transactions.type = 'REFUND' THEN -transactions.amountCents ELSE 0 END), 0) AS expense FROM tags LEFT JOIN transaction_tags ON tags.id = transaction_tags.tagId LEFT JOIN transactions ON transactions.id = transaction_tags.transactionId GROUP BY tags.id ORDER BY tags.name")
    suspend fun tagTotals(): List<TagTotal>

    @Query("SELECT * FROM merchant_rules ORDER BY exactMatch DESC, length(keyword) DESC")
    suspend fun merchantRules(): List<MerchantRule>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMerchantRule(rule: MerchantRule): Long
    @Delete suspend fun deleteMerchantRule(rule: MerchantRule)

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC, id DESC")
    suspend fun allTransactionsOnce(): List<LedgerTransaction>
    @Query("SELECT * FROM transaction_tags")
    suspend fun allTransactionTags(): List<TransactionTagCrossRef>
    @Query("SELECT * FROM settings")
    suspend fun settings(): List<AppSetting>
    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun setting(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSetting(setting: AppSetting)
    @Transaction suspend fun snapshot(): LedgerSnapshot = LedgerSnapshot(
        allTransactionsOnce(), categoryList(), tags(), allTransactionTags(), merchantRules(), settings())

    @Query("DELETE FROM transaction_tags") suspend fun clearAllTransactionTags()
    @Query("DELETE FROM merchant_rules") suspend fun clearAllMerchantRules()
    @Query("DELETE FROM transactions") suspend fun clearAllTransactions()
    @Query("DELETE FROM tags") suspend fun clearAllTags()
    @Query("DELETE FROM categories") suspend fun clearAllCategories()
    @Query("DELETE FROM settings") suspend fun clearAllSettings()
    @Insert suspend fun insertCategories(values: List<Category>)
    @Insert suspend fun insertTransactions(values: List<LedgerTransaction>)
    @Insert suspend fun insertTags(values: List<Tag>)
    @Insert suspend fun insertRules(values: List<MerchantRule>)
    @Insert suspend fun insertSettings(values: List<AppSetting>)
    @Transaction suspend fun replaceAll(snapshot: LedgerSnapshot) {
        clearAllTransactionTags()
        clearAllMerchantRules()
        clearAllTransactions()
        clearAllTags()
        clearAllCategories()
        clearAllSettings()
        insertCategories(snapshot.categories)
        insertTransactions(snapshot.transactions)
        insertTags(snapshot.tags)
        insertRules(snapshot.merchantRules)
        insertTransactionTags(snapshot.transactionTags)
        insertSettings(snapshot.settings)
        val names = snapshot.categories.map { it.name }.toSet()
        (CategoryCatalog.primary + CategoryCatalog.unclassified)
            .filterNot { it in names }
            .forEach { insertCategory(Category(name = it, isSystem = true)) }
    }

    @Insert suspend fun insertTransaction(transaction: LedgerTransaction): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNotification(transaction: LedgerTransaction): Long
    @Query("UPDATE transactions SET categoryId = :categoryId, classificationStatus = 'CLASSIFIED', updatedAt = :now WHERE id = :transactionId AND type = 'EXPENSE' AND amountCents > 0 AND classificationStatus IN ('UNCLASSIFIED', 'NEED_CONFIRM') AND EXISTS (SELECT 1 FROM categories WHERE id = :categoryId)")
    suspend fun classifyExpense(transactionId: Long, categoryId: Long, now: Long): Int
    @Update suspend fun updateTransaction(transaction: LedgerTransaction)
    @Delete suspend fun deleteTransaction(transaction: LedgerTransaction)
    @Query("UPDATE transactions SET refundOfTransactionId = NULL, classificationStatus = 'NEED_CONFIRM' WHERE refundOfTransactionId = :expenseId")
    suspend fun unlinkRefunds(expenseId: Long)
    @Transaction suspend fun deleteTransactionSafely(transaction: LedgerTransaction) {
        if (transaction.type == TransactionType.EXPENSE) unlinkRefunds(transaction.id)
        deleteTransaction(transaction)
    }
    @Query("UPDATE transactions SET refundOfTransactionId = NULL, classificationStatus = 'NEED_CONFIRM' WHERE refundOfTransactionId IN (:ids)")
    suspend fun unlinkRefundsForDeleted(ids: List<Long>)
    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteTransactionsByIds(ids: List<Long>): Int
    @Transaction suspend fun deleteTransactionsSafely(ids: List<Long>): Int {
        val targets = ids.filter { it > 0 }.distinct()
        if (targets.isEmpty()) return 0
        // Chunk bindings below SQLite limits; every chunk belongs to this one transaction.
        targets.chunked(400).forEach { unlinkRefundsForDeleted(it) }
        return targets.chunked(400).sumOf { deleteTransactionsByIds(it) }
    }

    @Query("SELECT COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amountCents ELSE 0 END),0) AS expense, COALESCE(SUM(CASE WHEN type = 'REFUND' THEN amountCents ELSE 0 END),0) AS refund FROM transactions WHERE timestamp >= :start AND timestamp < :end")
    fun totals(start: Long, end: Long): LiveData<MonthTotals>

    @Query("SELECT COALESCE(c.name, '未分类') AS label, SUM(t.amountCents) AS amountCents FROM transactions t LEFT JOIN categories c ON t.categoryId = c.id WHERE t.timestamp >= :start AND t.timestamp < :end AND t.type = 'EXPENSE' GROUP BY t.categoryId ORDER BY amountCents DESC")
    fun categoryTotals(start: Long, end: Long): LiveData<List<CategoryTotal>>

    @Query("SELECT paymentMethod, SUM(amountCents) AS amountCents FROM transactions WHERE timestamp >= :start AND timestamp < :end AND type = 'EXPENSE' GROUP BY paymentMethod ORDER BY amountCents DESC")
    fun paymentTotals(start: Long, end: Long): LiveData<List<PaymentTotal>>

    @Query("SELECT strftime('%d', timestamp / 1000, 'unixepoch', 'localtime') AS period, COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amountCents ELSE 0 END),0) AS expense, COALESCE(SUM(CASE WHEN type = 'REFUND' THEN amountCents ELSE 0 END),0) AS refund FROM transactions WHERE timestamp >= :start AND timestamp < :end AND type IN ('EXPENSE','REFUND') GROUP BY period ORDER BY period")
    fun dailyTotals(start: Long, end: Long): LiveData<List<TimeTotal>>

    @Query("SELECT strftime('%m', timestamp / 1000, 'unixepoch', 'localtime') AS period, COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amountCents ELSE 0 END),0) AS expense, COALESCE(SUM(CASE WHEN type = 'REFUND' THEN amountCents ELSE 0 END),0) AS refund FROM transactions WHERE timestamp >= :start AND timestamp < :end AND type IN ('EXPENSE','REFUND') GROUP BY period ORDER BY period")
    fun monthByMonthTotals(start: Long, end: Long): LiveData<List<TimeTotal>>
}
