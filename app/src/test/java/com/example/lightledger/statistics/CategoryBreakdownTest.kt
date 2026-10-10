package com.example.lightledger.statistics

import com.example.lightledger.data.ExpenseCategoryTotal
import org.junit.Assert.*
import org.junit.Test

class CategoryBreakdownTest {
    @Test fun strictTenPercentBoundaryAndEveryMergedAmountArePreserved() {
        val values = listOf(30L, 25L, 20L, 10L, 8L, 7L).mapIndexed { i, value -> ExpenseCategoryTotal(i.toLong(), "虚构$i", value) }
        val result = CategoryBreakdown.from(values)
        assertEquals(100L, result.totalCents)
        assertEquals(listOf(30L, 25L, 20L, 25L), result.slices.map { it.amountCents })
        assertEquals(listOf(10L, 8L, 7L), result.slices.last().categories.map { it.amountCents })
        assertTrue(result.slices.last().merged)
        assertEquals(result.totalCents, result.slices.sumOf { it.amountCents })
    }
    @Test fun equalTenPercentCategoriesAreAllGroupedAndEmptyDataStaysEmpty() {
        assertTrue(CategoryBreakdown.from(emptyList()).slices.isEmpty())
        val result = CategoryBreakdown.from((1..10).map { ExpenseCategoryTotal(it.toLong(), "虚构$it", 1) })
        assertEquals(1, result.slices.size); assertTrue(result.slices.single().merged)
        assertEquals(10, result.slices.single().categories.size)
    }
    @Test fun ActualOtherCategoryIsDistinctFromMergedOtherItems() {
        val result = CategoryBreakdown.from(listOf(ExpenseCategoryTotal(1, "其他", 60), ExpenseCategoryTotal(2, "其他项目", 30), ExpenseCategoryTotal(3, "虚构小项", 10)))
        assertEquals(listOf(false, false, true), result.slices.map { it.merged })
        assertEquals(listOf(1L, 2L, 3L), result.slices.flatMap { it.categories }.map { it.categoryId })
    }
    @Test fun thresholdUsesExactIntegerCentsWithoutOverflow() {
        val small = Long.MAX_VALUE / 10
        val result = CategoryBreakdown.from(listOf(ExpenseCategoryTotal(1, "大项", Long.MAX_VALUE - small), ExpenseCategoryTotal(2, "小项", small)))
        assertEquals(Long.MAX_VALUE, result.totalCents); assertTrue(result.slices.last().merged)
        val uneven = CategoryBreakdown.from(listOf(ExpenseCategoryTotal(1, "大项", 899), ExpenseCategoryTotal(2, "小项", 101)))
        assertFalse(uneven.slices.last().merged)
    }
}
