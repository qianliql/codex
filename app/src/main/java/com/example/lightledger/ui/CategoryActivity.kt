package com.example.lightledger.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.*
import com.example.lightledger.databinding.ActivityCategoryBinding
import com.example.lightledger.databinding.DialogCategoryPreferencesBinding
import com.example.lightledger.databinding.RowCategoryManageBinding
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class CategoryActivity : LedgerActivity() {
    private lateinit var binding: ActivityCategoryBinding
    private val repository by lazy { (application as LedgerApp).repository }
    private var categories = emptyList<Category>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.apply(binding.root)
        binding.addCategory.setOnClickListener { editCategory(null) }
        lifecycleScope.launch {
            try { repository.enforceCommonCategoryLimit() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast("分类整理失败，请重试") }
            repository.categories.observe(this@CategoryActivity) { categories = it; showCategories() }
        }
    }

    private fun showCategories() {
        val container = binding.categoryContainer
        container.removeAllViews()
        (0..2).forEach { priority ->
            val group = CategoryPriority.ordered(categories).filter { it.scope != CategoryScope.INCOME && it.priority == priority && it.name != CategoryCatalog.unclassified }
            val limit = CategoryPriority.limit(priority)
            val heading = CategoryPriority.label(priority) + if (priority == 2) " · ${group.size}" else " · ${group.size}/$limit"
            container.addView(TextView(this).apply {
                text = heading; textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(context.ledgerColor(R.color.ledger_ink)); setPadding(0, dp(10), 0, dp(12))
            })
            group.forEachIndexed { index, category ->
                val item = RowCategoryManageBinding.inflate(layoutInflater, container, false)
                item.categoryName.text = category.name
                CategoryIcons.apply(item.categoryIcon, category.name, category.iconKey)
                item.root.setOnClickListener { editCategory(category) }
                item.editIcon.setOnClickListener { showActions(it, category, index, group.size) }
                container.addView(item.root)
            }
            if (group.isEmpty()) container.addView(TextView(this).apply {
                text = "暂无分类"; textSize = 13f; setPadding(0, 0, 0, dp(12)); setTextColor(context.ledgerColor(R.color.ledger_muted))
            })
        }
        val income = CategoryCatalog.choices(categories, TransactionType.INCOME).filter { it.scope == CategoryScope.INCOME }
        if (income.isNotEmpty()) container.addView(TextView(this).apply {
            text = "收入分类"; textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(context.ledgerColor(R.color.ledger_ink)); setPadding(0, dp(16), 0, dp(12))
        })
        income.forEachIndexed { index, category ->
            val item = RowCategoryManageBinding.inflate(layoutInflater, container, false)
            item.categoryName.text = category.name
            CategoryIcons.apply(item.categoryIcon, category.name, category.iconKey)
            item.root.setOnClickListener { editCategory(category) }
            item.editIcon.setOnClickListener { showActions(it, category, index, income.size) }
            container.addView(item.root)
        }
        categories.firstOrNull { it.name == CategoryCatalog.unclassified }?.let { category ->
            val item = RowCategoryManageBinding.inflate(layoutInflater, container, false)
            item.categoryName.text = category.name
            CategoryIcons.apply(item.categoryIcon, category.name, category.iconKey)
            item.editIcon.setOnClickListener { showActions(it, category, 0, 1) }
            container.addView(item.root)
        }
    }

    private fun showActions(anchor: View, category: Category, index: Int, size: Int) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "上移").isEnabled = index > 0
            menu.add(0, 2, 1, "下移").isEnabled = index < size - 1
            menu.add(0, 3, 2, "删除分类")
            setOnMenuItemClickListener { item ->
                if (item.itemId == 3) confirmDelete(category)
                else lifecycleScope.launch {
                    try { repository.moveCategory(category.id, if (item.itemId == 1) -1 else 1) }
                    catch (e: Exception) { toast("排序失败，请重试") }
                }
                true
            }
            show()
        }
    }

    private fun editCategory(category: Category?) {
        val b = DialogCategoryPreferencesBinding.inflate(layoutInflater)
        b.categoryName.setText(category?.name.orEmpty())
        var iconKey = category?.iconKey ?: "auto"
        fun updateIcon() {
            b.chooseIcon.text = "图标 · ${CategoryIcons.choices[iconKey] ?: "自动"}"
            b.chooseIcon.setIconResource(CategoryIcons.icon(b.categoryName.text.toString(), iconKey))
        }
        updateIcon()
        b.chooseIcon.setOnClickListener { chooseIcon(iconKey, b.categoryName.text.toString()) { iconKey = it; updateIcon() } }
        b.priorityChoices.check(when (category?.priority ?: CategoryPriority.OCCASIONAL) {
            0 -> R.id.notificationPriority; 1 -> R.id.commonPriority; else -> R.id.occasionalPriority
        })
        listOf(b.notificationPriority, b.commonPriority, b.occasionalPriority).forEachIndexed { priority, button ->
            // ToggleGroup applies a one-line default when children are added; restore the two-line cards.
            button.setSingleLine(false); button.maxLines = 2; button.ellipsize = null
            val count = categories.count { it.scope != CategoryScope.INCOME && it.priority == priority && it.name != CategoryCatalog.unclassified }
            val limit = CategoryPriority.limit(priority)
            button.isEnabled = category?.priority == priority || count < limit
            button.text = CategoryPriority.label(priority) + "\n" + if (priority == 2) "不限数量" else if (!button.isEnabled) "已满" else "$count / $limit"
            button.contentDescription = "${CategoryPriority.label(priority)}，$count 个${if (button.isEnabled) "" else "，已满不可选"}"
        }
        if (category?.scope == CategoryScope.INCOME) {
            b.priorityHeading.visibility = View.GONE
            b.priorityChoices.visibility = View.GONE
        }
        b.categoryName.doAfterTextChanged { b.nameInput.error = null; b.categoryError.visibility = View.GONE; updateIcon() }
        b.priorityChoices.addOnButtonCheckedListener { _, _, _ -> b.categoryError.visibility = View.GONE }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(if (category == null) "新增分类" else "编辑分类")
            .setView(b.root).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.show()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.setOnClickListener {
            val name = b.categoryName.text.toString().trim()
            if (name.isBlank()) { b.nameInput.error = "请输入分类名称"; return@setOnClickListener }
            if (name == "家居" || name == CategoryCatalog.unclassified) { b.nameInput.error = "请换一个名称，家居已归入其他"; return@setOnClickListener }
            val priority = if (category?.scope == CategoryScope.INCOME) category.priority else
                when (b.priorityChoices.checkedButtonId) { R.id.notificationPriority -> 0; R.id.commonPriority -> 1; else -> 2 }
            save.isEnabled = false
            lifecycleScope.launch {
                try {
                    repository.saveCategoryPreferences((category ?: Category(name = name)).copy(name = name, priority = priority, iconKey = iconKey))
                    dialog.dismiss()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { b.categoryError.text = e.localizedMessage ?: "保存失败，请重试"; b.categoryError.visibility = View.VISIBLE }
                finally { save.isEnabled = true }
            }
        }
    }

    private fun chooseIcon(initial: String, label: String, chosen: (String) -> Unit) {
        var selected = initial
        val group = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        CategoryIcons.choices.forEach { (key, name) ->
            group.addView(Chip(this).apply {
                id = View.generateViewId(); text = name; isCheckable = true; isCheckedIconVisible = false
                chipIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, CategoryIcons.icon(label, key))
                chipIconTint = android.content.res.ColorStateList.valueOf(context.ledgerColor(R.color.ledger_teal))
                chipIconSize = dp(18).toFloat(); setEnsureMinTouchTargetSize(true); minHeight = dp(48)
                isChecked = key == initial
                setOnCheckedChangeListener { _, checked -> if (checked) selected = key }
            })
        }
        val scroll = android.widget.ScrollView(this).apply { addView(group) }
        MaterialAlertDialogBuilder(this).setTitle("选择分类图标").setView(scroll).setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> chosen(selected) }.show()
    }

    private fun confirmDelete(category: Category) {
        lifecycleScope.launch {
            try {
                val usage = repository.categoryUsage(category.id)
                MaterialAlertDialogBuilder(this@CategoryActivity).setTitle("删除 ${category.name}？")
                    .setMessage("当前 ${usage.billCount} 笔相关账单将变为未分类，金额、备注和账单本身保留。\n${usage.ruleCount} 条使用此分类的商户自动分类规则也会删除。")
                    .setNegativeButton("取消", null).setPositiveButton("确认删除") { _, _ -> lifecycleScope.launch {
                        try { repository.deleteCategory(category) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { toast("删除失败，请重试") }
                    } }.show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast("读取分类信息失败，请重试") }
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
