package com.example.lightledger.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.databinding.ActivityTagsBinding
import kotlinx.coroutines.launch

class TagsActivity : LedgerActivity() {
    private lateinit var binding: ActivityTagsBinding
    private val repository by lazy { (application as LedgerApp).repository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTagsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.apply(binding.root)
        binding.add.setOnClickListener {
            val name = binding.name.text.toString().trim()
            if (name.isEmpty()) { binding.name.error = "请输入名称"; return@setOnClickListener }
            lifecycleScope.launch {
                try {
                    repository.addTag(name)
                    binding.name.setText("")
                    refresh()
                } catch (_: Exception) { Toast.makeText(this@TagsActivity, "标签已存在或保存失败", Toast.LENGTH_SHORT).show() }
            }
        }
        lifecycleScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val tags = repository.tags()
        val totals = repository.tagTotals().associateBy { it.id }
        binding.list.removeAllViews()
        tags.forEach { tag ->
            binding.list.addView(TextView(this).apply {
                val spend = totals[tag.id]?.expense ?: 0L
                text = "${tag.name}　实际支出 ${UiFormat.money(spend)}"
                textSize = 17f
                setTextColor(context.ledgerColor(R.color.ledger_ink))
                setBackgroundResource(R.drawable.bg_list_row)
                val d = resources.displayMetrics.density
                setPadding((18 * d).toInt(), (16 * d).toInt(), (18 * d).toInt(), (16 * d).toInt())
                minHeight = (56 * d).toInt()
                setOnLongClickListener {
                    AlertDialog.Builder(this@TagsActivity).setTitle("删除标签“${tag.name}”？")
                        .setMessage("账单会保留，标签关联会移除。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ -> lifecycleScope.launch {
                            repository.deleteTag(tag)
                            refresh()
                        } }.show()
                    true
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() })
        }
    }
}
