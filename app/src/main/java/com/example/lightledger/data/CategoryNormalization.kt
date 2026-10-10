package com.example.lightledger.data

/** Used inside restore's transaction as well as when decoding older backups. */
object CategoryNormalization {
    fun withoutHousehold(snapshot: LedgerSnapshot): LedgerSnapshot {
        val household = snapshot.categories.filter { it.name == "家居" }.map { it.id }.toSet()
        if (household.isEmpty()) return snapshot
        val other = snapshot.categories.firstOrNull { it.name == "其他" }
            ?: Category(id = (snapshot.categories.maxOfOrNull { it.id } ?: 0) + 1,
                name = "其他", isSystem = true, priority = if (snapshot.categories.count { it.scope != CategoryScope.INCOME && it.priority == CategoryPriority.COMMON && it.name != CategoryCatalog.unclassified && it.id !in household } < CategoryPriority.COMMON_LIMIT) CategoryPriority.COMMON else CategoryPriority.OCCASIONAL, sortOrder = 7)
        val categories = snapshot.categories.filterNot { it.id in household }.let { list ->
            if (list.any { it.id == other.id }) list else list + other
        }
        return snapshot.copy(categories = categories,
            transactions = snapshot.transactions.map { if (it.categoryId in household) it.copy(categoryId = other.id) else it },
            merchantRules = snapshot.merchantRules.map { if (it.categoryId in household) it.copy(categoryId = other.id) else it })
    }
}
