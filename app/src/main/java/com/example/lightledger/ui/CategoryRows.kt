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
    val choices = linkedMapOf("auto" to "自动", "food" to "正餐", "snack" to "零食", "cup" to "饮品", "car" to "交通", "bag" to "日用", "shirt" to "服饰", "device" to "数码", "phone" to "手机", "game" to "娱乐", "bath" to "洗澡", "book" to "教育", "health" to "医疗", "beauty" to "美容", "pet" to "宠物", "sport" to "运动", "travel" to "旅行", "gift" to "礼物", "work" to "工作", "repair" to "维修", "rent" to "住房", "wallet" to "钱包", "other" to "其他")
    fun icon(label: String, key: String? = "auto"): Int {
        val resolved = key?.takeIf { it != "auto" && it in choices } ?: when (label) {
            "正餐", "餐饮", "早餐", "午餐", "晚餐" -> "food"
            "零食", "水果" -> "snack"
            "饮品", "奶茶", "饮料", "咖啡" -> "cup"
            "交通", "出行", "汽车", "加油" -> "car"
            "服饰", "衣服", "衣物", "穿搭" -> "shirt"
            "手机", "通讯", "话费" -> "phone"
            "医疗", "药品", "健康" -> "health"
            "美容", "护肤", "化妆", "理发" -> "beauty"
            "宠物" -> "pet"
            "运动", "健身" -> "sport"
            "旅行", "旅游" -> "travel"
            "礼物", "人情", "社交" -> "gift"
            "工作", "办公", "工资" -> "work"
            "维修" -> "repair"
            "住房", "房租" -> "rent"
            "娱乐" -> "game"
            "日用", "生活", "购物" -> "bag"
            "数码" -> "device"
            "洗澡" -> "bath"
            "教育", "学习" -> "book"
            "微信", "支付宝", "银行卡", "现金", "收入" -> "wallet"
            else -> "other"
        }
        return when (resolved) {
            "food" -> R.drawable.ic_food
            "snack" -> R.drawable.ic_snack
            "game" -> R.drawable.ic_game
            "bag" -> R.drawable.ic_bag
            "device" -> R.drawable.ic_device
            "bath" -> R.drawable.ic_bath
            "book" -> R.drawable.ic_book
            "wallet" -> R.drawable.ic_wallet
            "car" -> R.drawable.ic_car
            "shirt" -> R.drawable.ic_shirt
            "phone" -> R.drawable.ic_phone
            "cup" -> R.drawable.ic_cup
            "health" -> R.drawable.ic_health
            "pet" -> R.drawable.ic_pet
            "beauty" -> R.drawable.ic_beauty
            "sport" -> R.drawable.ic_sport
            "travel" -> R.drawable.ic_travel
            "gift" -> R.drawable.ic_gift
            "work" -> R.drawable.ic_work
            "repair" -> R.drawable.ic_repair
            "rent" -> R.drawable.ic_rent
            else -> R.drawable.ic_other
        }
    }
    fun color(label: String): Int = when (label) {
        "正餐", "零食" -> R.color.ledger_orange
        "数码", "洗澡", "支付宝" -> R.color.ledger_blue
        "教育", "娱乐" -> R.color.ledger_purple
        else -> R.color.ledger_teal
    }
    fun apply(view: ImageView, label: String, iconKey: String? = "auto") {
        val soft = when (color(label)) {
            R.color.ledger_orange -> R.color.ledger_orange_soft
            R.color.ledger_blue -> R.color.ledger_blue_soft
            R.color.ledger_purple -> R.color.ledger_purple_soft
            else -> R.color.ledger_teal_soft
        }
        view.setImageResource(icon(label, iconKey))
        view.imageTintList = ColorStateList.valueOf(view.context.ledgerColor(color(label)))
        view.backgroundTintList = ColorStateList.valueOf(view.context.ledgerColor(soft))
    }
}

object CategoryRows {
    fun show(container: LinearLayout, rows: List<Pair<String, Long>>, empty: String, hidden: Boolean = false, progress: Boolean = false, iconKeys: Map<String, String?> = emptyMap()) {
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
            CategoryIcons.apply(row.categoryIcon, label, iconKeys[label])
            row.categoryProgress.visibility = if (progress && !hidden) View.VISIBLE else View.GONE
            row.categoryProgress.setIndicatorColor(container.context.ledgerColor(CategoryIcons.color(label)))
            row.categoryProgress.progress = (amount.coerceAtLeast(0).toDouble() / max * 10000).toInt()
            container.addView(row.root)
        }
    }
}
