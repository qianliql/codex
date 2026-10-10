package com.example.lightledger.data

class LedgerRepository(private val dao: LedgerDao) {
    val statistics = com.example.lightledger.statistics.StatisticsRepository(dao)
    private val duplicateDetector = com.example.lightledger.duplicate.DuplicateDetector(dao)
    val categories = dao.categories()
    val transactions = dao.allTransactions()
    val transactionChangeSignal = dao.transactionChangeSignal()
    val recentTransactions = dao.recentTransactions(5)
    fun totals(start: Long, end: Long) = dao.totals(start, end)
    fun categoryTotals(start: Long, end: Long) = dao.categoryTotals(start, end)
    suspend fun categoryList() = dao.categoryList()
    suspend fun transaction(id: Long) = dao.transaction(id)
    suspend fun searchTransactions(search: String, start: Long, end: Long, categoryId: Long,
        tagId: Long, method: String, source: String, type: String, pendingOnly: Boolean,
        limit: Int, offset: Int) = dao.searchTransactions(search, start, end, categoryId, tagId,
            method, source, type, pendingOnly, limit, offset)
    suspend fun save(transaction: LedgerTransaction): Long {
        if (transaction.id != 0L) {
            val linkedRefunds = dao.linkedRefundTotal(transaction.id)
            require(linkedRefunds == 0L || (transaction.type == TransactionType.EXPENSE && transaction.amountCents >= linkedRefunds)) {
                "原支出已关联退款，金额不能低于退款总额，也不能改为其他类型"
            }
        }
        if (transaction.type == TransactionType.REFUND && transaction.refundOfTransactionId != null) {
            require(refundCandidates(transaction).any { it.id == transaction.refundOfTransactionId }) {
                "退款金额超过原支出剩余金额，或原支出已不存在"
            }
        }
        if (transaction.id == 0L) return dao.insertTransaction(transaction)
        dao.updateTransaction(transaction.copy(updatedAt = System.currentTimeMillis()))
        return transaction.id
    }
    suspend fun saveAutoClassified(transaction: LedgerTransaction): Long = save(classifyByMerchant(transaction))
    suspend fun possibleDuplicate(transaction: LedgerTransaction) = duplicateDetector.find(transaction)
    suspend fun mergeScreenshot(existing: LedgerTransaction, screenshot: LedgerTransaction) {
        val current = dao.transaction(existing.id) ?: return
        dao.updateTransaction(current.copy(
            merchant = current.merchant ?: screenshot.merchant,
            categoryId = current.categoryId ?: screenshot.categoryId,
            paymentMethod = if (current.paymentMethod == PaymentMethod.OTHER) screenshot.paymentMethod else current.paymentMethod,
            platform = current.platform ?: screenshot.platform,
            ocrText = screenshot.ocrText ?: current.ocrText,
            note = current.note ?: screenshot.note,
            classificationStatus = if (current.categoryId == null && screenshot.categoryId != null)
                ClassificationStatus.CLASSIFIED else current.classificationStatus,
            updatedAt = System.currentTimeMillis(),
        ))
    }
    suspend fun saveNotification(transaction: LedgerTransaction): Long? {
        val classified = classifyByMerchant(transaction)
        val linked = if (classified.type == TransactionType.REFUND) {
            val candidate = com.example.lightledger.refund.RefundMatcher.confidentMatch(classified, refundCandidates(classified))
            if (candidate != null) classified.copy(refundOfTransactionId = candidate.id, classificationStatus = ClassificationStatus.UNCLASSIFIED)
            else classified.copy(classificationStatus = ClassificationStatus.NEED_CONFIRM)
        } else classified
        return dao.insertNotification(linked).takeIf { it > 0 }
    }
    suspend fun classifyExpense(transactionId: Long, categoryId: Long): Boolean =
        dao.classifyExpense(transactionId, categoryId, System.currentTimeMillis()) == 1
    suspend fun delete(transaction: LedgerTransaction) = dao.deleteTransactionSafely(transaction)
    suspend fun deleteTransactions(ids: List<Long>): Int = dao.deleteTransactionsSafely(ids)
    suspend fun addCategory(name: String) = dao.insertCategory(Category(name = name.trim()))
    suspend fun renameCategory(category: Category, name: String) = dao.updateCategory(category.copy(name = name.trim()))
    suspend fun deleteCategory(category: Category) {
        require(!category.isSystem) { "系统分类不能删除" }
        dao.removeCategory(category)
    }
    suspend fun tags() = dao.tags()
    suspend fun tagTotals() = dao.tagTotals()
    suspend fun addTag(name: String) = dao.insertTag(Tag(name = name.trim()))
    suspend fun deleteTag(tag: Tag) = dao.deleteTag(tag)
    suspend fun tagIdsForTransaction(id: Long) = dao.tagIdsForTransaction(id)
    suspend fun replaceTransactionTags(id: Long, tagIds: List<Long>) = dao.replaceTransactionTags(id, tagIds)
    suspend fun merchantRules() = dao.merchantRules()
    suspend fun addMerchantRule(keyword: String, categoryId: Long, exactMatch: Boolean) =
        dao.insertMerchantRule(MerchantRule(keyword = keyword.trim(), categoryId = categoryId, exactMatch = exactMatch))
    suspend fun deleteMerchantRule(rule: MerchantRule) = dao.deleteMerchantRule(rule)
    suspend fun suggestedCategory(merchant: String?) =
        com.example.lightledger.classification.MerchantRuleEngine.categoryFor(merchant, dao.merchantRules())
    suspend fun refundCandidates(refund: LedgerTransaction): List<LedgerTransaction> {
        val start = refund.timestamp - 90L * 24 * 60 * 60 * 1000
        return com.example.lightledger.refund.RefundMatcher.candidates(refund,
            dao.refundCandidates(refund.amountCents, start, refund.timestamp, refund.id))
    }
    suspend fun snapshot() = dao.snapshot()
    suspend fun restore(snapshot: LedgerSnapshot) = dao.replaceAll(snapshot)
    suspend fun appendHistory(data: com.example.lightledger.history.HistoryImport) = dao.appendHistory(data)
    suspend fun setting(key: String) = dao.setting(key)
    suspend fun putSetting(key: String, value: String) = dao.putSetting(AppSetting(key, value))
    private suspend fun classifyByMerchant(transaction: LedgerTransaction): LedgerTransaction {
        if (transaction.categoryId != null || transaction.type != TransactionType.EXPENSE) return transaction
        val id = suggestedCategory(transaction.merchant) ?: return transaction
        return transaction.copy(categoryId = id, classificationStatus = ClassificationStatus.CLASSIFIED)
    }
}
