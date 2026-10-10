package com.example.lightledger.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.lightledger.R
import com.example.lightledger.databinding.RowCategoryTotalBinding

object CategoryIcons {
    fun icon(label: String): Int = when (label) {
        "正餐" -> R.drawable.ic_food
        "零食" -> R.drawable.ic_snack
        "娱乐" -> R.drawable.ic_game
        "日用" -> R.drawable.ic_bag
        "数码" -> R.drawable.ic_device
        "洗澡" -> R.drawable.ic_bath
        "教育" -> R.drawable.ic_book
        "微信", "支付宝", "银行卡", "现金" -> R.drawable.ic_wallet
        else -> R.drawable.ic_other
    }
    fun color(label: String): Int = when (label) {
        "正餐", "零食" -> R.color.ledger_orange
        "数码", "洗澡", "支付宝" -> R.color.ledger_blue
        "教育", "娱乐" -> R.color.ledger_purple
        else -> R.color.ledger_teal
    }
    fun apply(view: ImageView, label: String) {
        val soft = when (color(label)) {
            R.color.ledger_orange -> R.color.ledger_orange_soft
            R.color.ledger_blue -> R.color.ledger_blue_soft
            R.color.ledger_purple -> R.color.ledger_purple_soft
            else -> R.color.ledger_teal_soft
        }
        view.setImageResource(icon(label))
        view.imageTintList = ColorStateList.valueOf(view.context.ledgerColor(color(label)))
        view.backgroundTintList = ColorStateList.valueOf(view.context.ledgerColor(soft))
    }
}

object CategoryRows {
    fun show(container: LinearLayout, rows: List<Pair<String, Long>>, empty: String, hidden: Boolean = false, progress: Boolean = false) {
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.addView(TextView(container.context).apply {
                text = empty
                textSize = 14f
                setTextColor(context.ledgerColor(R.color.ledger_muted))
                val inset = (12 * resources.displayMetrics.density).toInt()
                setPadding(0, inset, 0, inset)
            })
            return
        }
        val max = rows.maxOf { it.second }.coerceAtLeast(1L)
        rows.forEach { (label, amount) ->
            val row = RowCategoryTotalBinding.inflate(LayoutInflater.from(container.context), container, false)
            row.categoryName.text = label
            row.categoryAmount.text = if (hidden) "¥****" else UiFormat.money(amount)
            CategoryIcons.apply(row.categoryIcon, label)
            row.categoryProgress.visibility = if (progress && !hidden) View.VISIBLE else View.GONE
            row.categoryProgress.setIndicatorColor(container.context.ledgerColor(CategoryIcons.color(label)))
            row.categoryProgress.progress = (amount.coerceAtLeast(0).toDouble() / max * 10000).toInt()
            container.addView(row.root)
        }
    }
}
