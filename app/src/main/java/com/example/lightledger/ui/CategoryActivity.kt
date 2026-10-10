package com.example.lightledger.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.widget.doAfterTextChanged
import com.example.lightledger.LedgerApp
import com.example.lightledger.data.CategoryCatalog
import com.example.lightledger.data.Category
import com.example.lightledger.databinding.ActivityCategoryBinding
import com.example.lightledger.databinding.DialogCategoryNameBinding
import com.example.lightledger.databinding.RowCategoryManageBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class CategoryActivity : LedgerActivity() {
    private lateinit var binding: ActivityCategoryBinding
    private val repository by lazy { (application as LedgerApp).repository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.apply(binding.root)
        binding.addCategory.setOnClickListener { editCategory(null) }
        repository.categories.observe(this) { categories -> showCategories(categories) }
    }

    private fun showCategories(categories: List<Category>) {
        binding.categoryContainer.removeAllViews()
        categories.forEach { category ->
            val item = RowCategoryManageBinding.inflate(layoutInflater, binding.categoryContainer, false)
            item.categoryName.text = category.name
            CategoryIcons.apply(item.categoryIcon, category.name)
            item.categoryHint.text = when {
                category.name == CategoryCatalog.unclassified -> "自动归入暂未选择分类的账单"
                category.name in CategoryCatalog.legacy -> "旧分类 · 点击改名，长按删除"
                category.isSystem -> "常用分类 · 点击改名"
                else -> "自定义分类 · 点击改名，长按删除"
            }
            item.editIcon.visibility = if (category.name == CategoryCatalog.unclassified) View.GONE else View.VISIBLE
            val row = item.root.apply {
                setOnClickListener { if (category.name != "未分类") editCategory(category) }
                setOnLongClickListener {
                    if (!category.isSystem) confirmDelete(category)
                    true
                }
            }
            binding.categoryContainer.addView(row)
        }
    }

    private fun editCategory(category: Category?) {
        val d = DialogCategoryNameBinding.inflate(layoutInflater)
        d.categoryName.setText(category?.name.orEmpty())
        d.categoryName.selectAll()
        d.nameExplanation.text = if (category == null) "给日常收支一个更清晰的归属" else "历史账单和商户规则会同步使用新名称"
        d.categoryName.doAfterTextChanged { d.nameInput.error = null }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(if (category == null) "新增分类" else "更改分类名称").setView(d.root)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val name = d.categoryName.text.toString().trim()
                if (name.isEmpty()) { d.nameInput.error = "请输入分类名称"; return@setOnClickListener }
                save.isEnabled = false
                lifecycleScope.launch {
                    try {
                        if (repository.categoryList().any { it.name == name && it.id != category?.id }) {
                            d.nameInput.error = "这个名称已存在，请换一个"
                            return@launch
                        }
                        if (category == null) repository.addCategory(name) else repository.renameCategory(category, name)
                        dialog.dismiss()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { d.nameInput.error = "保存失败，请重试" }
                    finally { save.isEnabled = true }
                }
            }
            d.categoryName.setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { save.performClick(); true } else false
            }
        }
        dialog.show()
    }

    private fun confirmDelete(category: Category) {
        AlertDialog.Builder(this).setTitle("删除 ${category.name}？")
            .setMessage("相关账单将变为未分类，账单本身不会删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> lifecycleScope.launch {
                try { repository.deleteCategory(category) }
                catch (e: Exception) { toast("删除失败：${e.localizedMessage}") }
            } }.show()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
