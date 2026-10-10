package com.example.lightledger.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.Category
import com.example.lightledger.databinding.ActivityMerchantRulesBinding
import kotlinx.coroutines.launch

class MerchantRulesActivity : LedgerActivity() {
    private lateinit var binding: ActivityMerchantRulesBinding
    private val repository by lazy { (application as LedgerApp).repository }
    private var categories: List<Category> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMerchantRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.apply(binding.root)
        binding.add.setOnClickListener {
            val keyword = binding.keyword.text.toString().trim()
            if (keyword.isEmpty()) { binding.keyword.error = "请输入商户或关键词"; return@setOnClickListener }
            val category = categories.getOrNull(binding.category.selectedItemPosition) ?: return@setOnClickListener
            lifecycleScope.launch {
                try {
                    repository.addMerchantRule(keyword, category.id, binding.exact.isChecked)
                    binding.keyword.setText("")
                    refresh()
                } catch (_: Exception) { Toast.makeText(this@MerchantRulesActivity, "规则保存失败", Toast.LENGTH_SHORT).show() }
            }
        }
        lifecycleScope.launch {
            categories = repository.categoryList().filter { it.name != "未分类" }
            binding.category.adapter = ArrayAdapter(this@MerchantRulesActivity,
                android.R.layout.simple_spinner_dropdown_item, categories.map { it.name })
            refresh()
        }
    }

    private suspend fun refresh() {
        val rules = repository.merchantRules()
        binding.list.removeAllViews()
        rules.forEach { rule ->
            binding.list.addView(TextView(this).apply {
                text = "${rule.keyword} ${if (rule.exactMatch) "完全匹配" else "包含关键词"} → ${categories.firstOrNull { it.id == rule.categoryId }?.name ?: "已删除分类"}"
                textSize = 17f
                setTextColor(context.ledgerColor(R.color.ledger_ink))
                setBackgroundResource(R.drawable.bg_list_row)
                val d = resources.displayMetrics.density
                setPadding((18 * d).toInt(), (16 * d).toInt(), (18 * d).toInt(), (16 * d).toInt())
                minHeight = (56 * d).toInt()
                setOnLongClickListener {
                    AlertDialog.Builder(this@MerchantRulesActivity).setTitle("删除这条规则？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ -> lifecycleScope.launch {
                            repository.deleteMerchantRule(rule)
                            refresh()
                        } }.show()
                    true
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() })
        }
    }
}
