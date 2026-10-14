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
import android.widget.LinearLayout
import android.widget.ScrollView
import com.example.lightledger.data.ExpenseCategoryTotal
import com.example.lightledger.statistics.CategoryBreakdown
import com.example.lightledger.statistics.ExpenseSlice
import com.example.lightledger.databinding.RowExpenseSliceBinding
import com.example.lightledger.databinding.RowCategoryTotalBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class StatisticsFragment : Fragment() {
    private val model: StatisticsViewModel by viewModels()
    private var binding: FragmentStatisticsBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentStatisticsBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        b.statisticsBack.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        if (savedInstanceState?.containsKey("stats_year") == true) model.choose(StatisticsPeriod(savedInstanceState.getInt("stats_year"), savedInstanceState.getInt("stats_month").takeIf { it != 0 }))
        b.periodLabel.setOnClickListener { model.period.value?.let { period -> MonthSelection.show(requireContext(), period, model::choose) } }
        b.monthMode.setOnClickListener { model.switchMode(false) }
        b.yearMode.setOnClickListener { model.switchMode(true) }
        b.previousPeriod.setOnClickListener { model.move(-1) }
        b.nextPeriod.setOnClickListener { model.move(1) }
        model.period.observe(viewLifecycleOwner) { period ->
            if (model.expenseCategories.value?.first != period) {
                b.categoryDonut.setData(CategoryBreakdown(0, emptyList()))
                b.categoryLegend.removeAllViews()
                b.categoryDonut.onSliceClick = null
            }
            if (model.incomeCategories.value?.first != period) {
                b.incomeDonut.setData(CategoryBreakdown(0, emptyList()), income = true)
                b.incomeLegend.removeAllViews()
                b.incomeDonut.onSliceClick = null
            }
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
                b.income.text = "…"; b.expense.text = "…"; b.refund.text = "…"; b.average.text = "…"
                b.averageLabel.text = "月均实际支出 · 按有收支记录的月份"
            }
            b.periodLabel.contentDescription = "${period.label()}，点击选择${if (period.month == null) "年份" else "年份和月份"}"
        }
        model.totals.observe(viewLifecycleOwner) { (period, totals) ->
            if (model.period.value == period) updateTotals(totals)
        }
        model.expenseCategories.observe(viewLifecycleOwner) { (period, totals) ->
            if (model.period.value == period) showCategories(period, totals, income = false)
        }
        model.incomeCategories.observe(viewLifecycleOwner) { (period, totals) ->
            if (model.period.value == period) showCategories(period, totals, income = true)
        }
        model.payments.observe(viewLifecycleOwner) { totals ->
            CategoryRows.show(b.paymentContainer, totals.map { UiFormat.method(it.paymentMethod) to it.amountCents }, "本期暂无支出", progress = true)
        }
        model.trend.observe(viewLifecycleOwner) { b.spendingChart.setData(model.period.value ?: return@observe, it) }
    }

    private fun showCategories(period: StatisticsPeriod, totals: List<ExpenseCategoryTotal>, income: Boolean) {
        val b = binding ?: return
        val data = CategoryBreakdown.from(totals)
        val donut = if (income) b.incomeDonut else b.categoryDonut
        val legend = if (income) b.incomeLegend else b.categoryLegend
        donut.setData(data, income)
        donut.onSliceClick = { openSlice(period, it, income) }
        legend.removeAllViews()
        data.slices.forEachIndexed { index, slice ->
            val row = RowExpenseSliceBinding.inflate(layoutInflater, legend, false)
            row.sliceColor.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(donut.colorFor(index)) }
            row.sliceName.text = slice.label
            row.sliceShare.text = String.format(java.util.Locale.CHINA, "%.2f%%", slice.amountCents.toDouble() / data.totalCents * 100) + if (slice.merged) " · ${slice.categories.size}个分类" else ""
            row.sliceAmount.text = UiFormat.money(slice.amountCents)
            row.root.contentDescription = "${slice.label}，${row.sliceShare.text}，${row.sliceAmount.text}，点击查看${if (income) "收入" else "消费"}明细"
            row.root.setOnClickListener { openSlice(period, slice, income) }
            legend.addView(row.root)
        }
    }

    private fun openSlice(period: StatisticsPeriod, slice: ExpenseSlice, income: Boolean) {
        if (!slice.merged) { ExpenseRankActivity.open(requireContext(), period, slice.categories.single(), income); return }
        val list = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; val inset = (20 * resources.displayMetrics.density).toInt(); setPadding(inset, 0, inset, 0) }
        val scroll = ScrollView(requireContext()).apply { addView(list) }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("其他项目 · ${slice.categories.size}类")
            .setMessage("${period.label()} · 合计${UiFormat.money(slice.amountCents)}")
            .setView(scroll).setPositiveButton("关闭", null).create()
        slice.categories.forEach { item ->
            val row = RowCategoryTotalBinding.inflate(layoutInflater, list, false)
            row.categoryName.text = item.label; row.categoryAmount.text = UiFormat.money(item.amountCents)
            CategoryIcons.apply(row.categoryIcon, item.label, item.iconKey)
            row.root.minimumHeight = (64 * resources.displayMetrics.density).toInt()
            row.root.isClickable = true; row.root.isFocusable = true
            row.root.setBackgroundResource(android.util.TypedValue().also { requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId)
            row.root.contentDescription = "${item.label}，${UiFormat.money(item.amountCents)}，查看${if (income) "收入" else "消费"}排行"
            row.root.setOnClickListener { dialog.dismiss(); ExpenseRankActivity.open(requireContext(), period, item, income) }
            list.addView(row.root)
        }
        dialog.show()
    }

    private fun updateTotals(totals: MonthTotals) {
        val b = binding ?: return
        b.income.text = UiFormat.money(totals.income)
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
