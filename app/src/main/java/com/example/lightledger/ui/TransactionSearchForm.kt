package com.example.lightledger.ui

import android.content.res.ColorStateList
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.core.view.children
import androidx.core.widget.doAfterTextChanged
import com.example.lightledger.R
import com.example.lightledger.data.*
import com.example.lightledger.databinding.ActivityTransactionSearchBinding
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One draft: cancelling the page never alters the result list's applied filters. */
internal class TransactionSearchForm(
    private val b: ActivityTransactionSearchBinding,
    initial: TransactionFilter, categories: List<Category>, tags: List<Tag>,
    private val chooseDate: (Long, (Long) -> Unit) -> Unit,
    private val apply: (TransactionFilter) -> Unit,
) {
    private var draft = initial
    private var rendering = false
    private val tagOptions = listOf(0L to "不限标签") + tags.map { it.id to it.name }
    init {
        chips(b.datePresets, SearchDatePreset.entries.map { it to it.label }, preset()) { selected ->
            selected.range()?.let { (start, end) -> draft = draft.copy(start = start, end = end) }
            renderDates(); updateCount()
        }
        chips(b.categoryChoices, listOf(-1L to "不限", 0L to "未分类") + categories.filter { it.name != "未分类" }.map { it.id to it.name }, draft.categoryId, true) {
            draft = draft.copy(categoryId = it); updateCount()
        }
        chips(b.typeChoices, listOf("" to "不限") + TransactionType.entries.map { it.name to UiFormat.type(it) }, draft.type) { draft = draft.copy(type = it); updateCount() }
        chips(b.methodChoices, listOf("" to "不限") + PaymentMethod.entries.map { it.name to UiFormat.method(it) }, draft.method) { draft = draft.copy(method = it); updateCount() }
        chips(b.sourceChoices, listOf("" to "不限") + TransactionSource.entries.map { it.name to TransactionFilter.sourceLabel(it.name) }, draft.source) { draft = draft.copy(source = it); updateCount() }
        b.tagChoice.setAdapter(ArrayAdapter(b.root.context, R.layout.row_filter_dropdown, tagOptions.map { it.second }))
        b.tagChoice.setOnItemClickListener { _, _, position, _ -> draft = draft.copy(tagId = tagOptions[position].first); updateCount() }
        b.pendingOnly.setOnCheckedChangeListener { _, checked -> if (!rendering) { draft = draft.copy(pendingOnly = checked); updateCount() } }
        b.keyword.doAfterTextChanged { if (!rendering) { draft = draft.copy(search = it.toString()); updateCount() } }
        b.keyword.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false }
        b.startDate.setOnClickListener { chooseDate(draft.start.takeIf { it > 0 } ?: System.currentTimeMillis()) { draft = draft.copy(start = it); select(b.datePresets, SearchDatePreset.CUSTOM); renderDates(); updateCount() } }
        b.endDate.setOnClickListener { chooseDate(if (draft.end == Long.MAX_VALUE) System.currentTimeMillis() else draft.end - 1) {
            val end = Calendar.getInstance().apply { timeInMillis = it; add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            draft = draft.copy(end = end); select(b.datePresets, SearchDatePreset.CUSTOM); renderDates(); updateCount()
        } }
        b.moreFilters.setOnClickListener { more(b.additionalFilters.visibility != View.VISIBLE) }
        b.resetFilters.setOnClickListener { draft = TransactionFilter(); render() }
        b.applyFilters.setOnClickListener { submit() }
        render()
        b.applyFilters.isEnabled = true
    }
    fun snapshot() = draft.copy(search = b.keyword.text.toString().trim())
    private fun submit() {
        if (!draft.validDates) { b.dateError.visibility = View.VISIBLE; b.searchScroll.smoothScrollTo(0, b.startDate.top); return }
        apply(snapshot())
    }
    private fun preset() = SearchDatePreset.entries.firstOrNull { it.range() == (draft.start to draft.end) } ?: SearchDatePreset.CUSTOM
    private fun renderDates() {
        val format = SimpleDateFormat("yyyy.MM.dd", Locale.CHINA)
        b.startDate.text = if (draft.start == 0L) "不限" else format.format(Date(draft.start))
        b.endDate.text = if (draft.end == Long.MAX_VALUE) "不限" else format.format(Date(draft.end - 1))
        b.dateError.visibility = if (draft.validDates) View.GONE else View.VISIBLE
    }
    private fun more(show: Boolean) {
        b.additionalFilters.visibility = if (show) View.VISIBLE else View.GONE
        b.moreFilters.text = if (show) "收起更多条件" else "更多条件 · 标签、来源、待处理"
    }
    private fun updateCount() { val n = snapshot().activeCount; b.conditionCount.text = if (n == 0) "不限制条件，查看全部账单" else "已选 $n 项条件"; }
    private fun render() {
        rendering = true
        b.keyword.setText(draft.search)
        select(b.categoryChoices, draft.categoryId); select(b.methodChoices, draft.method)
        select(b.typeChoices, draft.type); select(b.sourceChoices, draft.source); select(b.datePresets, preset())
        b.tagChoice.setText(tagOptions.firstOrNull { it.first == draft.tagId }?.second ?: "指定标签", false)
        b.pendingOnly.isChecked = draft.pendingOnly
        more(draft.tagId != 0L || draft.source.isNotBlank() || draft.pendingOnly)
        renderDates(); rendering = false; updateCount()
    }
    private fun select(group: ChipGroup, value: Any) {
        val wasRendering = rendering; rendering = true
        group.children.filterIsInstance<Chip>().firstOrNull { it.tag == value }?.isChecked = true
        rendering = wasRendering
    }
    private fun <T : Any> chips(group: ChipGroup, values: List<Pair<T, String>>, selected: T, icons: Boolean = false, changed: (T) -> Unit) {
        val byId = mutableMapOf<Int, T>()
        val context = b.root.context
        val density = context.resources.displayMetrics.density
        values.forEach { (value, label) ->
            val chip = Chip(context).apply {
                id = View.generateViewId(); tag = value; text = label; textSize = 14f
                isCheckable = true; isCheckedIconVisible = false
                chipBackgroundColor = context.getColorStateList(R.color.ledger_chip_background)
                setTextColor(context.getColorStateList(R.color.ledger_chip_text))
                chipStrokeColor = ColorStateList.valueOf(context.ledgerColor(R.color.ledger_border)); chipStrokeWidth = density
                setEnsureMinTouchTargetSize(true); minHeight = (48 * density).toInt()
                if (icons) {
                    chipIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, if (label == "不限") R.drawable.ic_grid else CategoryIcons.icon(label))
                    chipIconTint = ColorStateList.valueOf(context.ledgerColor(R.color.ledger_teal)); chipIconSize = 18 * density
                }
                isChecked = value == selected
            }
            byId[chip.id] = value; group.addView(chip)
        }
        group.setOnCheckedStateChangeListener { _, checked -> if (!rendering) checked.firstOrNull()?.let(byId::get)?.let(changed) }
    }
}
