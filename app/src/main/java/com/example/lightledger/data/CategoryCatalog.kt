package com.example.lightledger.data

object CategoryCatalog {
    val primary = listOf("正餐", "零食", "娱乐", "日用", "数码", "洗澡", "教育", "其他")
    val incomeDefaults = listOf("工资", "奖金", "生活费")
    fun choices(categories: List<Category>, type: TransactionType): List<Category> =
        CategoryPriority.ordered(categories).filter { it.name != unclassified &&
            if (type == TransactionType.INCOME) it.scope != CategoryScope.EXPENSE else it.scope != CategoryScope.INCOME
        }.let { choices ->
            if (type == TransactionType.INCOME) choices.sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.id }) else choices
        }
    val legacy = setOf("餐饮", "交通", "购物", "生活", "医疗", "通讯", "住房")
    const val unclassified = "未分类"
}
