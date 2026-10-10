package com.example.lightledger.ui

import android.os.Bundle
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.lightledger.data.MonthTotals
import com.example.lightledger.R
import com.google.android.material.button.MaterialButton
import com.example.lightledger.databinding.FragmentStatisticsBinding
import java.util.Calendar

class StatisticsFragment : Fragment() {
    private val model: StatisticsViewModel by viewModels()
    private var binding: FragmentStatisticsBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentStatisticsBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        if (savedInstanceState?.containsKey("stats_year") == true) model.choose(StatisticsPeriod(savedInstanceState.getInt("stats_year"), savedInstanceState.getInt("stats_month").takeIf { it != 0 }))
        b.periodLabel.setOnClickListener { model.period.value?.let { period -> MonthSelection.show(requireContext(), period, model::choose) } }
        b.monthMode.setOnClickListener { model.switchMode(false) }
        b.yearMode.setOnClickListener { model.switchMode(true) }
        b.previousPeriod.setOnClickListener { model.move(-1) }
        b.nextPeriod.setOnClickListener { model.move(1) }
        model.period.observe(viewLifecycleOwner) { period ->
            b.periodLabel.text = "${period.label()} ▾"
            stylePeriodButton(b.monthMode, period.month != null)
            stylePeriodButton(b.yearMode, period.month == null)
            b.averageLabel.visibility = if (period.month == null) View.VISIBLE else View.GONE
            b.average.visibility = if (period.month == null) View.VISIBLE else View.GONE
            b.trendLabel.text = if (period.month == null) "每月实际支出" else "每日实际支出"
            val cached = model.totals.value
            if (cached?.first == period) updateTotals(cached.second)
            else {
                b.netExpense.text = "…"
                b.expense.text = "…"; b.refund.text = "…"; b.average.text = "…"
                b.averageLabel.text = "月均实际支出 · 按有收支记录的月份"
            }
            b.periodLabel.contentDescription = "${period.label()}，点击选择${if (period.month == null) "年份" else "年份和月份"}"
        }
        model.totals.observe(viewLifecycleOwner) { (period, totals) ->
            if (model.period.value == period) updateTotals(totals)
        }
        model.categories.observe(viewLifecycleOwner) { totals ->
            CategoryRows.show(b.categoryContainer, totals.map { it.label to it.amountCents }, "本期暂无支出", progress = true, iconKeys = totals.associate { it.label to it.iconKey })
        }
        model.payments.observe(viewLifecycleOwner) { totals ->
            CategoryRows.show(b.paymentContainer, totals.map { UiFormat.method(it.paymentMethod) to it.amountCents }, "本期暂无支出", progress = true)
        }
        model.trend.observe(viewLifecycleOwner) { b.spendingChart.setData(model.period.value ?: return@observe, it) }
    }

    private fun updateTotals(totals: MonthTotals) {
        val b = binding ?: return
        b.expense.text = UiFormat.money(totals.expense)
        b.refund.text = UiFormat.money(totals.refund)
        b.netExpense.text = UiFormat.money(totals.expense - totals.refund)
        b.averageLabel.text = if (totals.recordedMonths == 0) "月均实际支出 · 暂无收支记录" else "月均实际支出 · 按 ${totals.recordedMonths} 个记账月份"
        b.average.text = UiFormat.money(totals.averageExpense())
    }

    private fun stylePeriodButton(button: MaterialButton, selected: Boolean) {
        val background = requireContext().ledgerColor(if (selected) R.color.ledger_teal else R.color.white)
        button.backgroundTintList = ColorStateList.valueOf(background)
        button.setTextColor(requireContext().ledgerColor(if (selected) R.color.white else R.color.ledger_teal))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        model.period.value?.let { outState.putInt("stats_year", it.year); outState.putInt("stats_month", it.month ?: 0) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroyView() { binding = null; super.onDestroyView() }
}
