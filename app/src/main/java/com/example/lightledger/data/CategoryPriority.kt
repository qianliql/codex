package com.example.lightledger.data

object CategoryPriority {
    const val NOTIFICATION = 0
    const val COMMON = 1
    const val OCCASIONAL = 2
    const val NOTIFICATION_LIMIT = 3
    const val COMMON_LIMIT = 8
    val notificationDefaults = listOf("正餐", "零食", "日用")
    fun defaultFor(name: String) = when (name) {
        in notificationDefaults -> NOTIFICATION
        in CategoryCatalog.primary -> COMMON
        else -> OCCASIONAL
    }
    fun limit(priority: Int) = when (priority) { NOTIFICATION -> NOTIFICATION_LIMIT; COMMON -> COMMON_LIMIT; else -> Int.MAX_VALUE }
    fun label(priority: Int) = when (priority) { NOTIFICATION -> "通知栏"; COMMON -> "常用"; else -> "不常用" }
    fun ordered(categories: List<Category>) = categories.sortedWith(compareBy<Category> { it.priority }.thenBy { it.sortOrder }.thenBy { it.id })
    fun notification(categories: List<Category>) = ordered(categories).filter { it.scope != CategoryScope.INCOME && it.priority == NOTIFICATION && it.name != CategoryCatalog.unclassified }.take(NOTIFICATION_LIMIT)
}
