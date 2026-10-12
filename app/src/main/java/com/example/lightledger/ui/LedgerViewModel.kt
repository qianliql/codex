package com.example.lightledger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import com.example.lightledger.LedgerApp
import java.util.Calendar

class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as LedgerApp).repository
    val categories = repository.categories
    val transactions = repository.transactions
    val transactionChangeSignal = repository.transactionChangeSignal
    val recentTransactions = repository.recentTransactions
    val unconfirmedAmountCount = repository.unconfirmedAmountCount

    private val currentDay = MutableLiveData(dayStart())
    val monthlyTotals = currentDay.switchMap { day -> monthBounds(day).let { repository.totals(it.first, it.second) } }
    val monthlyCategories = currentDay.switchMap { day -> monthBounds(day).let { repository.categoryTotals(it.first, it.second) } }
    val todayTotals = currentDay.switchMap { day ->
        val next = Calendar.getInstance().apply { timeInMillis = day; add(Calendar.DAY_OF_MONTH, 1) }
        repository.totals(day, next.timeInMillis)
    }
    fun refreshDate() {
        val today = dayStart()
        if (currentDay.value != today) currentDay.value = today
    }
    private fun dayStart() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private fun monthBounds(day: Long): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            timeInMillis = day
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        return start.timeInMillis to end.timeInMillis
    }
}
