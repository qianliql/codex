package com.example.lightledger.statistics

import com.example.lightledger.data.LedgerDao

class StatisticsRepository(private val dao: LedgerDao) {
    fun totals(start: Long, end: Long) = dao.totals(start, end)
    fun categories(start: Long, end: Long) = dao.categoryTotals(start, end)
    fun expenseCategories(start: Long, end: Long) = dao.expenseCategories(start, end)
    fun incomeCategories(start: Long, end: Long) = dao.incomeCategories(start, end)
    fun payments(start: Long, end: Long) = dao.paymentTotals(start, end)
    fun dailyTrend(start: Long, end: Long) = dao.dailyTotals(start, end)
    fun monthlyTrend(start: Long, end: Long) = dao.monthByMonthTotals(start, end)
}
