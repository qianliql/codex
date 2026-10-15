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
                val categoryName = if (row.category == "家居") "其他" else row.category
                val categoryId = categoryIds.getOrPut(categoryName) { 0L }.let { existing ->
                    if (existing > 0) existing else insertCategory(Category(name = categoryName, priority = CategoryPriority.defaultFor(categoryName))).also { categoryIds[categoryName] = it }
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
    @Query("SELECT * FROM categories ORDER BY priority, sortOrder, id")
    fun categories(): LiveData<List<Category>>

    @Query("SELECT * FROM categories ORDER BY priority, sortOrder, id")
    suspend fun categoryList(): List<Category>

    @Insert suspend fun insertCategory(category: Category): Long
    @Update suspend fun updateCategory(category: Category)
    @Transaction suspend fun enforceCommonCategoryLimit() {
        val existing = CategoryPriority.ordered(categoryList())
        val common = existing.filter { it.scope != CategoryScope.INCOME && it.name != CategoryCatalog.unclassified && it.priority == CategoryPriority.COMMON }
        val overflow = common.drop(CategoryPriority.COMMON_LIMIT)
        val firstOrder = (existing.filter { it.scope != CategoryScope.INCOME && it.priority == CategoryPriority.OCCASIONAL }
            .maxOfOrNull { it.sortOrder } ?: -1) + 1
        overflow.forEachIndexed { index, category ->
            updateCategory(category.copy(priority = CategoryPriority.OCCASIONAL, sortOrder = firstOrder + index))
        }
    }
    @Transaction suspend fun saveCategoryPreferences(category: Category) {
        require(category.name.isNotBlank() && category.name != "家居") { "请使用其他分类名称" }
        require(category.priority in 0..2) { "分类优先级无效" }
        val existing = categoryList()
        require(existing.none { it.name == category.name && it.id != category.id }) { "这个名称已存在，请换一个" }
        require(category.scope == CategoryScope.INCOME || existing.count { it.scope != CategoryScope.INCOME && it.id != category.id && it.name != CategoryCatalog.unclassified && it.priority == category.priority } < CategoryPriority.limit(category.priority)) {
            "${CategoryPriority.label(category.priority)}最多${CategoryPriority.limit(category.priority)}个，请先把已有分类移到其他分组"
        }
        val previous = existing.firstOrNull { it.id == category.id }
        val order = if (previous?.priority == category.priority) previous.sortOrder
            else (existing.filter { it.priority == category.priority }.maxOfOrNull { it.sortOrder } ?: -1) + 1
        if (category.id == 0L) insertCategory(category.copy(sortOrder = order)) else updateCategory(category.copy(sortOrder = order))
    }
    @Transaction suspend fun moveCategory(id: Long, delta: Int) {
        val all = categoryList()
        val selected = all.firstOrNull { it.id == id } ?: return
        val group = (if (selected.scope == CategoryScope.INCOME) all.filter { it.scope == CategoryScope.INCOME }
            .sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.id }) else
            CategoryPriority.ordered(all).filter { it.scope != CategoryScope.INCOME && it.priority == selected.priority && it.name != CategoryCatalog.unclassified }).toMutableList()
        val from = group.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in group.indices) return
        java.util.Collections.swap(group, from, to)
        group.forEachIndexed { index, category -> updateCategory(category.copy(sortOrder = index)) }
    }
    @Transaction suspend fun reorderCategories(orderedIds: List<Long>) {
        require(orderedIds.isNotEmpty() && orderedIds.size == orderedIds.distinct().size) { "分类排序无效" }
        val all = categoryList()
        val selected = all.firstOrNull { it.id == orderedIds.first() } ?: error("分类不存在")
        require(selected.name != CategoryCatalog.unclassified) { "未分类不能排序" }
        val group = if (selected.scope == CategoryScope.INCOME) all.filter { it.scope == CategoryScope.INCOME }
            else all.filter { it.scope != CategoryScope.INCOME && it.priority == selected.priority && it.name != CategoryCatalog.unclassified }
        require(group.size == orderedIds.size && group.map { it.id }.toSet() == orderedIds.toSet()) { "只能在同一分组内排序" }
        val byId = group.associateBy { it.id }
        orderedIds.forEachIndexed { index, id ->
            val category = byId.getValue(id)
            if (category.sortOrder != index) updateCategory(category.copy(sortOrder = index))
        }
    }
    @Delete suspend fun deleteCategory(category: Category)
    @Query("SELECT (SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId) AS billCount, (SELECT COUNT(*) FROM merchant_rules WHERE categoryId = :categoryId) AS ruleCount")
    suspend fun categoryUsage(categoryId: Long): CategoryUsage
    @Query("UPDATE transactions SET categoryId = NULL, classificationStatus = 'UNCLASSIFIED', updatedAt = :now WHERE categoryId = :categoryId")
    suspend fun unclassifyCategoryTransactions(categoryId: Long, now: Long)
    @Transaction suspend fun removeCategory(category: Category) {
        unclassifyCategoryTransactions(category.id, System.currentTimeMillis())
        deleteCategory(category)
    }

    @Query("SELECT t.*, c.name AS categoryName, c.iconKey AS categoryIconKey FROM transactions t LEFT JOIN categories c ON t.categoryId = c.id ORDER BY t.timestamp DESC, t.id DESC LIMIT :limit")
    fun recentTransactions(limit: Int): LiveData<List<TransactionRow>>

    @Query("SELECT t.*, c.name AS categoryName, c.iconKey AS categoryIconKey FROM transactions t LEFT JOIN categories c ON t.categoryId = c.id ORDER BY t.timestamp DESC, t.id DESC")
    fun allTransactions(): LiveData<List<TransactionRow>>

    @Query("SELECT COUNT(*) FROM transactions")
    fun transactionChangeSignal(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM transactions WHERE amountConfirmed = 0 OR (amountCents = 0 AND classificationStatus = 'NEED_CONFIRM')")
    fun unconfirmedAmountCount(): LiveData<Int>

    @Query("""SELECT t.*, c.name AS categoryName, c.iconKey AS categoryIconKey FROM transactions t
        LEFT JOIN categories c ON t.categoryId = c.id
        WHERE (:search = '' OR instr(COALESCE(t.merchant, ''), :search) > 0 OR instr(COALESCE(t.note, ''), :search) > 0)
        AND t.timestamp >= :start AND t.timestamp < :end
        AND (:categoryId = -1 OR (:categoryId = 0 AND t.categoryId IS NULL) OR t.categoryId = :categoryId)
        AND (:tagId = 0 OR EXISTS (SELECT 1 FROM transaction_tags tt WHERE tt.transactionId = t.id AND tt.tagId = :tagId))
        AND (:method = '' OR t.paymentMethod = :method)
        AND (:source = '' OR t.source = :source)
        AND (:type = '' OR t.type = :type)
        AND (:pendingOnly = 0 OR t.classificationStatus != 'CLASSIFIED' OR t.amountConfirmed = 0)
        AND (:amountPendingOnly = 0 OR t.amountConfirmed = 0 OR (t.amountCents = 0 AND t.classificationStatus = 'NEED_CONFIRM'))
        ORDER BY t.timestamp DESC, t.id DESC LIMIT :limit OFFSET :offset""")
    suspend fun searchTransactions(search: String, start: Long, end: Long, categoryId: Long,
        tagId: Long, method: String, source: String, type: String, pendingOnly: Boolean,
        limit: Int, offset: Int, amountPendingOnly: Boolean = false): List<TransactionRow>

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
    @Transaction suspend fun replaceAll(original: LedgerSnapshot) {
        val snapshot = CategoryNormalization.withoutHousehold(original)
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
    }

    @Insert suspend fun insertTransaction(transaction: LedgerTransaction): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNotification(transaction: LedgerTransaction): Long
    @Query("UPDATE transactions SET categoryId = :categoryId, classificationStatus = 'CLASSIFIED', updatedAt = :now WHERE id = :transactionId AND type = 'EXPENSE' AND amountCents > 0 AND classificationStatus IN ('UNCLASSIFIED', 'NEED_CONFIRM') AND EXISTS (SELECT 1 FROM categories WHERE id = :categoryId AND scope != 'INCOME')")
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

    @Query("SELECT COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amountCents ELSE 0 END),0) AS expense, COALESCE(SUM(CASE WHEN type = 'REFUND' THEN amountCents ELSE 0 END),0) AS refund, COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amountCents ELSE 0 END),0) AS income, COUNT(DISTINCT CASE WHEN type IN ('EXPENSE','INCOME') THEN strftime('%Y-%m', timestamp / 1000, 'unixepoch', 'localtime') END) AS recordedMonths FROM transactions WHERE timestamp >= :start AND timestamp < :end")
    fun totals(start: Long, end: Long): LiveData<MonthTotals>

    @Query("SELECT COALESCE(c.name, '未分类') AS label, c.iconKey AS iconKey, SUM(t.amountCents) AS amountCents FROM transactions t LEFT JOIN categories c ON t.categoryId = c.id WHERE t.timestamp >= :start AND t.timestamp < :end AND t.type = 'EXPENSE' GROUP BY t.categoryId ORDER BY amountCents DESC")
    fun categoryTotals(start: Long, end: Long): LiveData<List<CategoryTotal>>

    @Query("SELECT CASE WHEN c.id IS NULL OR c.name = '未分类' THEN 0 ELSE c.id END AS categoryId, COALESCE(c.name, '未分类') AS label, c.iconKey AS iconKey, SUM(t.amountCents) AS amountCents FROM transactions t LEFT JOIN categories c ON t.categoryId = c.id WHERE t.timestamp >= :start AND t.timestamp < :end AND t.type = 'EXPENSE' AND t.amountCents > 0 GROUP BY CASE WHEN c.id IS NULL OR c.name = '未分类' THEN 0 ELSE c.id END ORDER BY amountCents DESC, categoryId")
    fun expenseCategories(start: Long, end: Long): LiveData<List<ExpenseCategoryTotal>>
    @Query("SELECT CASE WHEN c.id IS NULL OR c.name = '未分类' THEN 0 ELSE c.id END AS categoryId, COALESCE(c.name, '未分类') AS label, c.iconKey AS iconKey, SUM(t.amountCents) AS amountCents FROM transactions t LEFT JOIN categories c ON t.categoryId = c.id WHERE t.timestamp >= :start AND t.timestamp < :end AND t.type = 'INCOME' AND t.amountCents > 0 GROUP BY CASE WHEN c.id IS NULL OR c.name = '未分类' THEN 0 ELSE c.id END ORDER BY amountCents DESC, categoryId")
    fun incomeCategories(start: Long, end: Long): LiveData<List<ExpenseCategoryTotal>>

    @Query("SELECT t.*, c.name AS categoryName, c.iconKey AS categoryIconKey FROM transactions t LEFT JOIN categories c ON t.categoryId = c.id WHERE t.timestamp >= :start AND t.timestamp < :end AND t.type = 'EXPENSE' AND t.amountCents > 0 AND ((:categoryId = 0 AND (c.id IS NULL OR c.name = '未分类')) OR (:categoryId > 0 AND t.categoryId = :categoryId)) ORDER BY t.amountCents DESC, t.timestamp DESC, t.id DESC LIMIT :limit OFFSET :offset")
    suspend fun expenseRank(start: Long, end: Long, categoryId: Long, limit: Int, offset: Int): List<TransactionRow>

    @Query("SELECT COUNT(*) AS billCount, COALESCE(SUM(t.amountCents),0) AS amountCents FROM transactions t LEFT JOIN categories c ON t.categoryId = c.id WHERE t.timestamp >= :start AND t.timestamp < :end AND t.type = 'EXPENSE' AND t.amountCents > 0 AND ((:categoryId = 0 AND (c.id IS NULL OR c.name = '未分类')) OR (:categoryId > 0 AND t.categoryId = :categoryId))")
    fun expenseRankSummary(start: Long, end: Long, categoryId: Long): LiveData<ExpenseRankSummary>
    @Query("SELECT t.*, c.name AS categoryName, c.iconKey AS categoryIconKey FROM transactions t LEFT JOIN categories c ON t.categoryId = c.id WHERE t.timestamp >= :start AND t.timestamp < :end AND t.type = 'INCOME' AND t.amountCents > 0 AND ((:categoryId = 0 AND (c.id IS NULL OR c.name = '未分类')) OR (:categoryId > 0 AND t.categoryId = :categoryId)) ORDER BY t.amountCents DESC, t.timestamp DESC, t.id DESC LIMIT :limit OFFSET :offset")
    suspend fun incomeRank(start: Long, end: Long, categoryId: Long, limit: Int, offset: Int): List<TransactionRow>
    @Query("SELECT COUNT(*) AS billCount, COALESCE(SUM(t.amountCents),0) AS amountCents FROM transactions t LEFT JOIN categories c ON t.categoryId = c.id WHERE t.timestamp >= :start AND t.timestamp < :end AND t.type = 'INCOME' AND t.amountCents > 0 AND ((:categoryId = 0 AND (c.id IS NULL OR c.name = '未分类')) OR (:categoryId > 0 AND t.categoryId = :categoryId))")
    fun incomeRankSummary(start: Long, end: Long, categoryId: Long): LiveData<ExpenseRankSummary>

    @Query("SELECT paymentMethod, SUM(amountCents) AS amountCents FROM transactions WHERE timestamp >= :start AND timestamp < :end AND type = 'EXPENSE' GROUP BY paymentMethod ORDER BY amountCents DESC")
    fun paymentTotals(start: Long, end: Long): LiveData<List<PaymentTotal>>

    @Query("SELECT strftime('%d', timestamp / 1000, 'unixepoch', 'localtime') AS period, COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amountCents ELSE 0 END),0) AS expense, COALESCE(SUM(CASE WHEN type = 'REFUND' THEN amountCents ELSE 0 END),0) AS refund FROM transactions WHERE timestamp >= :start AND timestamp < :end AND type IN ('EXPENSE','REFUND') GROUP BY period ORDER BY period")
    fun dailyTotals(start: Long, end: Long): LiveData<List<TimeTotal>>

    @Query("SELECT strftime('%m', timestamp / 1000, 'unixepoch', 'localtime') AS period, COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amountCents ELSE 0 END),0) AS expense, COALESCE(SUM(CASE WHEN type = 'REFUND' THEN amountCents ELSE 0 END),0) AS refund FROM transactions WHERE timestamp >= :start AND timestamp < :end AND type IN ('EXPENSE','REFUND') GROUP BY period ORDER BY period")
    fun monthByMonthTotals(start: Long, end: Long): LiveData<List<TimeTotal>>
}
