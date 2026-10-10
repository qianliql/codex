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
    private var latestTotals: MonthTotals? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentStatisticsBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        b.monthMode.setOnClickListener { model.switchMode(false) }
        b.yearMode.setOnClickListener { model.switchMode(true) }
        b.previousPeriod.setOnClickListener { model.move(-1) }
        b.nextPeriod.setOnClickListener { model.move(1) }
        model.period.observe(viewLifecycleOwner) { period ->
            b.periodLabel.text = period.label()
            stylePeriodButton(b.monthMode, period.month != null)
            stylePeriodButton(b.yearMode, period.month == null)
            b.averageLabel.visibility = if (period.month == null) View.VISIBLE else View.GONE
            b.average.visibility = if (period.month == null) View.VISIBLE else View.GONE
            b.trendLabel.text = if (period.month == null) "每月实际支出" else "每日实际支出"
            latestTotals?.let { updateTotals(it) }
        }
        model.totals.observe(viewLifecycleOwner) {
            latestTotals = it
            updateTotals(it)
        }
        model.categories.observe(viewLifecycleOwner) { totals ->
            CategoryRows.show(b.categoryContainer, totals.map { it.label to it.amountCents }, "本期暂无支出", progress = true)
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
        val period = model.period.value ?: return
        val current = Calendar.getInstance()
        val months = if (period.year == current.get(Calendar.YEAR)) current.get(Calendar.MONTH) + 1 else 12
        b.average.text = UiFormat.money((totals.expense - totals.refund) / months)
    }

    private fun stylePeriodButton(button: MaterialButton, selected: Boolean) {
        val background = requireContext().ledgerColor(if (selected) R.color.ledger_teal else R.color.white)
        button.backgroundTintList = ColorStateList.valueOf(background)
        button.setTextColor(requireContext().ledgerColor(if (selected) R.color.white else R.color.ledger_teal))
    }

    override fun onDestroyView() { binding = null; super.onDestroyView() }
}
