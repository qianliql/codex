package com.example.lightledger.ui

import android.content.ClipData
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.lightledger.R
import com.example.lightledger.data.Category
import com.example.lightledger.data.CategoryCatalog
import com.example.lightledger.data.CategoryPriority
import com.google.android.material.card.MaterialCardView

/** Same-window drop destinations allow one uninterrupted long-press gesture. */
internal class CategoryDragTargets(private val host: FrameLayout,
    private val categories: () -> List<Category>, private val startDrag: ((View, Category) -> Boolean)? = null,
    private val dropped: (Category, Int) -> Unit) {
    private val cards = mutableListOf<MaterialCardView>()
    private val counts = mutableListOf<TextView>()
    private var active: Category? = null
    private val context = host.context
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    init {
        val panel = MaterialCardView(context).apply {
            radius = dp(24).toFloat(); cardElevation = dp(8).toFloat()
            setCardBackgroundColor(context.getColor(R.color.white)); strokeWidth = dp(1)
            strokeColor = context.ledgerColor(R.color.ledger_border)
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)) }
        column.addView(TextView(context).apply { text = "拖到分组后松手"; textSize = 14f; setTextColor(context.ledgerColor(R.color.ledger_ink)); setPadding(0, 0, 0, dp(12)) })
        val row = LinearLayout(context)
        val ids = listOf(R.id.dropNotificationPriority, R.id.dropCommonPriority, R.id.dropOccasionalPriority)
        val icons = listOf(R.drawable.ic_wallet, R.drawable.ic_grid, R.drawable.ic_other)
        (0..2).forEach { priority ->
            val card = MaterialCardView(context).apply {
                id = ids[priority]; radius = dp(14).toFloat(); strokeWidth = dp(1); minimumHeight = dp(110)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (priority > 0) leftMargin = dp(6) }
            }
            val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(12), dp(4), dp(12)); minimumHeight = dp(110) }
            content.addView(ImageView(context).apply { setImageResource(icons[priority]); imageTintList = android.content.res.ColorStateList.valueOf(context.ledgerColor(R.color.ledger_teal)) }, LinearLayout.LayoutParams(dp(22), dp(22)))
            content.addView(TextView(context).apply { text = CategoryPriority.label(priority); textSize = 13f; gravity = Gravity.CENTER; setTextColor(context.ledgerColor(R.color.ledger_ink)); setPadding(0, dp(7), 0, dp(4)) })
            val count = TextView(context).apply { textSize = 11f; gravity = Gravity.CENTER; setTextColor(context.ledgerColor(R.color.ledger_muted)) }
            content.addView(count); counts += count; card.addView(content); cards += card; row.addView(card)
            card.setOnDragListener { _, event ->
                val source = event.localState as? Category
                if (source == null || source.id != active?.id) false else when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> card.isEnabled
                    DragEvent.ACTION_DRAG_ENTERED -> { if (card.isEnabled) highlight(card, true); true }
                    DragEvent.ACTION_DRAG_EXITED -> { highlight(card, false); true }
                    DragEvent.ACTION_DROP -> { if (card.isEnabled) { dropped(source, priority); true } else false }
                    else -> true
                }
            }
        }
        column.addView(row); panel.addView(column)
        host.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
        host.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> event.localState is Category
                DragEvent.ACTION_DRAG_ENDED -> { hide(); true }
                else -> false
            }
        }
    }
    fun start(category: Category, source: View): Boolean {
        active = category
        val all = categories()
        cards.forEachIndexed { priority, card ->
            val count = all.count { it.priority == priority && it.name != CategoryCatalog.unclassified }
            card.isEnabled = priority == category.priority || count < CategoryPriority.limit(priority)
            card.alpha = if (card.isEnabled) 1f else .4f
            counts[priority].text = if (priority == 2) "默认折叠" else "$count / ${CategoryPriority.limit(priority)}${if (card.isEnabled) "" else " · 已满"}"
            card.contentDescription = "${CategoryPriority.label(priority)}，${counts[priority].text}"
            highlight(card, false)
        }
        host.visibility = View.VISIBLE
        source.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val started = startDrag?.invoke(source, category) ?: source.startDragAndDrop(ClipData.newPlainText("分类", category.id.toString()), View.DragShadowBuilder(source), category, 0)
        if (!started) hide()
        return started
    }
    private fun highlight(card: MaterialCardView, on: Boolean) {
        card.strokeWidth = dp(if (on) 2 else 1)
        card.strokeColor = context.ledgerColor(if (on) R.color.ledger_teal else R.color.ledger_border)
        card.setCardBackgroundColor(context.ledgerColor(if (on) R.color.ledger_teal_soft else R.color.white))
    }
    fun hide() { host.visibility = View.GONE; active = null }
}
