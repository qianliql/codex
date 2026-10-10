package com.example.lightledger.statistics

import com.example.lightledger.data.ExpenseCategoryTotal

data class ExpenseSlice(val label: String, val categories: List<ExpenseCategoryTotal>, val merged: Boolean = false) {
    val amountCents: Long = categories.fold(0L) { sum, item -> Math.addExact(sum, item.amountCents) }
}
data class CategoryBreakdown(val totalCents: Long, val slices: List<ExpenseSlice>) {
    companion object {
        fun from(totals: List<ExpenseCategoryTotal>): CategoryBreakdown {
            val positive = totals.filter { it.amountCents > 0 }.sortedWith(compareByDescending<ExpenseCategoryTotal> { it.amountCents }.thenBy { it.categoryId })
            val total = positive.fold(0L) { sum, item -> Math.addExact(sum, item.amountCents) }
            if (total == 0L) return CategoryBreakdown(0, emptyList())
            // Integer division preserves the strict >10% boundary without multiplication overflow.
            val (large, small) = positive.partition { it.amountCents > total / 10 }
            val slices = large.map { ExpenseSlice(it.label, listOf(it)) }.toMutableList()
            if (small.isNotEmpty()) slices += ExpenseSlice("其他项目", small, merged = true)
            return CategoryBreakdown(total, slices)
        }
    }
}
