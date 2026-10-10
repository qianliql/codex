package com.example.lightledger.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.example.lightledger.databinding.DialogMonthYearBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar

object MonthSelection {
    fun at(time: Long = System.currentTimeMillis()): StatisticsPeriod = Calendar.getInstance().run {
        timeInMillis = time
        StatisticsPeriod(get(Calendar.YEAR), get(Calendar.MONTH) + 1)
    }
    fun move(period: StatisticsPeriod, delta: Int): StatisticsPeriod = Calendar.getInstance().run {
        clear(); set(period.year, (period.month ?: 1) - 1, 1); add(Calendar.MONTH, delta)
        StatisticsPeriod(get(Calendar.YEAR), get(Calendar.MONTH) + 1)
    }
    fun apply(filter: TransactionFilter, month: StatisticsPeriod): TransactionFilter = month.bounds().let { (start, end) ->
        filter.copy(start = start.coerceAtLeast(0), end = end)
    }
    fun from(filter: TransactionFilter): StatisticsPeriod? {
        if (filter.end == Long.MAX_VALUE) return null
        val month = at(filter.start)
        val expected = apply(TransactionFilter(), month)
        return month.takeIf { expected.start == filter.start && expected.end == filter.end }
    }
    fun show(context: Context, initial: StatisticsPeriod, chosen: (StatisticsPeriod) -> Unit): AlertDialog {
        val b = DialogMonthYearBinding.inflate(LayoutInflater.from(context))
        b.yearPicker.minValue = 1970
        b.yearPicker.maxValue = 9999
        b.yearPicker.value = initial.year.coerceIn(1970, 9999)
        b.yearPicker.wrapSelectorWheel = false
        b.monthPicker.minValue = 1
        b.monthPicker.maxValue = 12
        b.monthPicker.displayedValues = (1..12).map { "${it}月" }.toTypedArray()
        b.monthPicker.value = initial.month ?: 1
        b.monthColumn.visibility = if (initial.month == null) View.GONE else View.VISIBLE
        return MaterialAlertDialogBuilder(context).setTitle(if (initial.month == null) "选择年份" else "选择年份和月份")
            .setView(b.root).setNegativeButton("取消", null).setPositiveButton("确定") { _, _ ->
                b.yearPicker.clearFocus(); b.monthPicker.clearFocus()
                chosen(StatisticsPeriod(b.yearPicker.value, if (initial.month == null) null else b.monthPicker.value))
            }.show()
    }
}
