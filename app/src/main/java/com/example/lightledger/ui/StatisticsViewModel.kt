package com.example.lightledger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import com.example.lightledger.LedgerApp
import java.util.Calendar

data class StatisticsPeriod(val year: Int, val month: Int?) {
    fun bounds(): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, (month ?: 1) - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val end = (start.clone() as Calendar).apply { add(if (month == null) Calendar.YEAR else Calendar.MONTH, 1) }
        return start.timeInMillis to end.timeInMillis
    }
    fun label(): String = if (month == null) "${year}年" else "${year}年${month}月"
}

class StatisticsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as LedgerApp).repository.statistics
    private val now = Calendar.getInstance()
    val period = MutableLiveData(StatisticsPeriod(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1))
    val totals = period.switchMap { it.bounds().let { (start, end) -> repository.totals(start, end) } }
    val categories = period.switchMap { it.bounds().let { (start, end) -> repository.categories(start, end) } }
    val payments = period.switchMap { it.bounds().let { (start, end) -> repository.payments(start, end) } }
    val trend = period.switchMap { selected ->
        selected.bounds().let { (start, end) ->
            if (selected.month == null) repository.monthlyTrend(start, end) else repository.dailyTrend(start, end)
        }
    }

    fun switchMode(yearly: Boolean) {
        val current = period.value ?: return
        period.value = StatisticsPeriod(current.year, if (yearly) null else current.month ?: 1)
    }

    fun move(delta: Int) {
        val current = period.value ?: return
        val date = Calendar.getInstance().apply {
            clear(); set(Calendar.YEAR, current.year); set(Calendar.MONTH, (current.month ?: 1) - 1)
            add(if (current.month == null) Calendar.YEAR else Calendar.MONTH, delta)
        }
        period.value = StatisticsPeriod(date.get(Calendar.YEAR), if (current.month == null) null else date.get(Calendar.MONTH) + 1)
    }
}
