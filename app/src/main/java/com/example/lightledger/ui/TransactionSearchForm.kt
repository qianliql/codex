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
    initial: TransactionFilter, private val categories: List<Category>, tags: List<Tag>,
    private val chooseDate: (Long, (Long) -> Unit) -> Unit,
    private val apply: (TransactionFilter) -> Unit,
) {
    private var draft = initial.copy(
        type = initial.type.takeIf { it == "EXPENSE" || it == "INCOME" }.orEmpty(),
        method = initial.method.takeUnless { it == "OTHER" }.orEmpty(),
    )
    private var rendering = false
    private var showOccasional = categories.any { it.id == initial.categoryId && it.priority == CategoryPriority.OCCASIONAL }
    private val tagOptions = listOf(0L to "不限标签") + tags.map { it.id to it.name }
    init {
        EditorChoiceRows.bindCategories(b.categoryChoices, b.searchCategoryScroll)
        EditorChoiceRows.bindPayments(b.methodChoices)
        chips(b.datePresets, SearchDatePreset.entries.map { it to it.label }, preset()) { selected ->
            selected.range()?.let { (start, end) -> draft = draft.copy(start = start, end = end) }
            renderDates(); updateCount()
        }
        b.expandSearchCategories.setOnClickListener { showOccasional = !showOccasional; renderCategoryVisibility() }
        b.typeChoices.addOnButtonCheckedListener { group, id, checked ->
            if (!rendering && (checked || group.checkedButtonId == View.NO_ID)) {
                draft = draft.copy(type = if (!checked) "" else if (id == R.id.searchIncomeOption) "INCOME" else "EXPENSE")
                showOccasional = false
                renderCategories(); updateCount()
            }
        }
        chips(b.methodChoices, PaymentMethod.entries.filter { it != PaymentMethod.OTHER }.map { it.name to UiFormat.method(it) }, draft.method, empty = "") { draft = draft.copy(method = it); updateCount() }
        EditorChoiceRows.fitPayments(b.methodChoices)
        b.clearCategory.setOnClickListener { draft = draft.copy(categoryId = -1); select(b.categoryChoices, -1L); updateCount() }
        b.clearMethod.setOnClickListener { draft = draft.copy(method = ""); select(b.methodChoices, ""); updateCount() }
        chips(b.sourceChoices, listOf("" to "不限") + TransactionSource.entries.map { it.name to TransactionFilter.sourceLabel(it.name) }, draft.source) { draft = draft.copy(source = it); updateCount() }
        b.tagChoice.setAdapter(ArrayAdapter(b.root.context, R.layout.row_filter_dropdown, tagOptions.map { it.second }))
        b.tagChoice.setOnItemClickListener { _, _, position, _ -> draft = draft.copy(tagId = tagOptions[position].first); updateCount() }
        b.pendingOnly.setOnCheckedChangeListener { _, checked -> if (!rendering) { draft = draft.copy(pendingOnly = checked); updateCount() } }
        b.amountPendingOnly.setOnCheckedChangeListener { _, checked -> if (!rendering) { draft = draft.copy(amountPendingOnly = checked); updateCount() } }
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
    private fun updateCount() {
        val n = snapshot().activeCount; b.conditionCount.text = if (n == 0) "不限制条件，查看全部账单" else "已选 $n 项条件"
        b.clearCategory.text = if (draft.categoryId == -1L) "全部" else "清除"
        b.clearMethod.text = if (draft.method.isBlank()) "全部" else "清除"
    }
    private fun render() {
        rendering = true
        b.keyword.setText(draft.search)
        renderCategories(); select(b.methodChoices, draft.method)
        b.typeChoices.clearChecked()
        if (draft.type.isNotBlank()) b.typeChoices.check(if (draft.type == "INCOME") R.id.searchIncomeOption else R.id.searchExpenseOption)
        select(b.sourceChoices, draft.source); select(b.datePresets, preset())
        b.tagChoice.setText(tagOptions.firstOrNull { it.first == draft.tagId }?.second ?: "指定标签", false)
        b.pendingOnly.isChecked = draft.pendingOnly
        b.amountPendingOnly.isChecked = draft.amountPendingOnly
        more(draft.tagId != 0L || draft.source.isNotBlank() || draft.pendingOnly || draft.amountPendingOnly)
        renderDates(); renderCategoryVisibility(); rendering = false; updateCount()
    }
    private fun categoryOptions(): List<Category> = when (draft.type) {
        "INCOME" -> CategoryCatalog.choices(categories, TransactionType.INCOME)
        "EXPENSE" -> CategoryCatalog.choices(categories, TransactionType.EXPENSE)
        else -> CategoryPriority.ordered(categories).filter { it.name != CategoryCatalog.unclassified }
    }
    private fun renderCategories() {
        val options = categoryOptions()
        if (draft.categoryId > 0 && options.none { it.id == draft.categoryId }) draft = draft.copy(categoryId = -1)
        val old = rendering; rendering = true
        b.categoryChoices.setOnCheckedStateChangeListener(null)
        b.categoryChoices.removeAllViews()
        chips(b.categoryChoices, options.map { it.id to it.name } + (0L to "未分类"), draft.categoryId, true, -1L) {
            draft = draft.copy(categoryId = it); updateCount()
        }
        renderCategoryVisibility(); rendering = old
    }
    private fun renderCategoryVisibility() {
        val ids = if (draft.type == "INCOME") emptySet() else categoryOptions()
            .filter { it.scope != CategoryScope.INCOME && it.priority == CategoryPriority.OCCASIONAL }.map { it.id }.toSet()
        b.categoryChoices.children.filterIsInstance<Chip>().filter { it.tag in ids }.forEach { it.visibility = if (showOccasional) View.VISIBLE else View.GONE }
        b.expandSearchCategories.visibility = if (ids.isEmpty()) View.GONE else View.VISIBLE
        b.expandSearchCategories.text = if (showOccasional) "收起不常用分类 ▴" else "展开不常用分类（${ids.size}）▾"
        EditorChoiceRows.fitCategories(b.categoryChoices, b.searchCategoryScroll)
        b.searchCategoryScroll.scrollTo(0, 0)
    }
    private fun select(group: ChipGroup, value: Any) {
        val wasRendering = rendering; rendering = true
        group.clearCheck()
        group.children.filterIsInstance<Chip>().firstOrNull { it.tag == value }?.isChecked = true
        rendering = wasRendering
    }
    private fun <T : Any> chips(group: ChipGroup, values: List<Pair<T, String>>, selected: T, icons: Boolean = false, empty: T? = null, changed: (T) -> Unit) {
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
                    chipIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, if (label == "不限") R.drawable.ic_grid else CategoryIcons.icon(label, categories.firstOrNull { it.name == label }?.iconKey))
                    chipIconTint = ColorStateList.valueOf(context.ledgerColor(R.color.ledger_teal)); chipIconSize = 18 * density
                }
                isChecked = value == selected
            }
            byId[chip.id] = value; group.addView(chip)
        }
        group.setOnCheckedStateChangeListener { _, checked -> if (!rendering) (checked.firstOrNull()?.let(byId::get) ?: empty)?.let(changed) }
    }
}
