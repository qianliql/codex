package com.example.lightledger.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.lightledger.R
import com.example.lightledger.data.Category
import com.example.lightledger.data.CategoryCatalog
import com.example.lightledger.data.CategoryPriority
import com.example.lightledger.data.CategoryScope
import com.example.lightledger.data.TransactionType
import com.example.lightledger.databinding.RowCategoryManageBinding
import kotlin.math.abs

/** Dragging changes only the order inside one priority group. The database saves on drop. */
internal class CategoryManageAdapter(
    private val onEdit: (Category?) -> Unit,
    private val onDelete: (View, Category) -> Unit,
    private val onReordered: (List<Long>) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private sealed interface Row {
        data class Header(val title: String) : Row
        data class Empty(val title: String = "暂无分类") : Row
        data class Entry(val category: Category, val group: Int, val movable: Boolean = true) : Row
    }

    private var rows = mutableListOf<Row>()
    private var draggedGroup: Int? = null
    private var orderBeforeDrag = emptyList<Long>()
    private lateinit var touchHelper: ItemTouchHelper

    fun attachTo(list: RecyclerView) {
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder): Int {
                val row = rows.getOrNull(holder.bindingAdapterPosition) as? Row.Entry
                return if (row?.movable == true) makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                    else makeMovementFlags(0, 0)
            }

            override fun isLongPressDragEnabled() = false
            override fun isItemViewSwipeEnabled() = false

            override fun canDropOver(recyclerView: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val source = rows.getOrNull(current.bindingAdapterPosition) as? Row.Entry
                val destination = rows.getOrNull(target.bindingAdapterPosition) as? Row.Entry
                return source?.movable == true && destination?.movable == true && source.group == destination.group
            }

            override fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean =
                previewMove(source.bindingAdapterPosition, target.bindingAdapterPosition)

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(holder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) holder?.itemView?.translationZ = dp(holder.itemView, 8).toFloat()
            }

            override fun clearView(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, holder)
                holder.itemView.translationZ = 0f
                val group = draggedGroup
                draggedGroup = null
                if (group != null) {
                    val result = groupIds(group)
                    if (result != orderBeforeDrag) onReordered(result)
                }
                orderBeforeDrag = emptyList()
            }

            override fun interpolateOutOfBoundsScroll(recyclerView: RecyclerView, viewSize: Int,
                viewSizeOutOfBounds: Int, totalSize: Int, msSinceStartScroll: Long): Int {
                if (viewSizeOutOfBounds == 0) return 0
                // ItemTouchHelper keeps calling this near an edge. Cap each step so long lists
                // follow the finger gently instead of accelerating as the drag continues.
                val maxStep = dp(recyclerView, 3).coerceAtLeast(1)
                val step = (1 + abs(viewSizeOutOfBounds) / dp(recyclerView, 36).coerceAtLeast(1)).coerceAtMost(maxStep)
                return if (viewSizeOutOfBounds < 0) -step else step
            }
        })
        touchHelper.attachToRecyclerView(list)
    }

    fun show(categories: List<Category>) {
        if (draggedGroup != null) return
        val ordered = CategoryPriority.ordered(categories)
        val next = mutableListOf<Row>()
        (0..2).forEach { priority ->
            val group = ordered.filter { it.scope != CategoryScope.INCOME && it.priority == priority && it.name != CategoryCatalog.unclassified }
            val limit = CategoryPriority.limit(priority)
            next += Row.Header(CategoryPriority.label(priority) + if (priority == 2) " · ${group.size}" else " · ${group.size}/$limit")
            if (group.isEmpty()) next += Row.Empty()
            group.forEach { next += Row.Entry(it, priority) }
        }
        val income = CategoryCatalog.choices(categories, TransactionType.INCOME).filter { it.scope == CategoryScope.INCOME }
        if (income.isNotEmpty()) {
            next += Row.Header("收入分类")
            income.forEach { next += Row.Entry(it, 3) }
        }
        categories.firstOrNull { it.name == CategoryCatalog.unclassified }?.let { next += Row.Entry(it, 4, false) }
        rows = next
        notifyDataSetChanged()
    }

    internal fun previewMove(from: Int, to: Int): Boolean {
        val source = rows.getOrNull(from) as? Row.Entry ?: return false
        val destination = rows.getOrNull(to) as? Row.Entry ?: return false
        if (!source.movable || !destination.movable || source.group != destination.group || from == to) return false
        rows.add(to, rows.removeAt(from))
        notifyItemMoved(from, to)
        return true
    }

    internal fun groupIds(group: Int): List<Long> = rows.filterIsInstance<Row.Entry>()
        .filter { it.group == group && it.movable }.map { it.category.id }

    internal fun positionOfCategory(name: String): Int = rows.indexOfFirst { it is Row.Entry && it.category.name == name }
    internal fun heading(title: String): Boolean = rows.any { it is Row.Header && it.title == title }

    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> 0
        is Row.Empty -> 1
        is Row.Entry -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 2) return CategoryHolder(RowCategoryManageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        val label = TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(this, if (viewType == 0) 16 else 0), 0, dp(this, 12))
            textSize = if (viewType == 0) 15f else 13f
            if (viewType == 0) {
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.ledgerColor(R.color.ledger_ink))
            } else setTextColor(context.ledgerColor(R.color.ledger_muted))
        }
        return LabelHolder(label)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder.itemView as TextView).text = row.title
            is Row.Empty -> (holder.itemView as TextView).text = row.title
            is Row.Entry -> {
                val b = (holder as CategoryHolder).binding
                b.categoryName.text = row.category.name
                CategoryIcons.apply(b.categoryIcon, row.category.name, row.category.iconKey)
                b.root.contentDescription = if (row.movable) "${row.category.name}，点击编辑，长按调整顺序" else row.category.name
                b.root.setOnClickListener(if (row.movable) View.OnClickListener { onEdit(row.category) } else null)
                b.editIcon.setOnClickListener { onDelete(it, row.category) }
                val start = View.OnLongClickListener {
                    val index = holder.bindingAdapterPosition
                    val current = rows.getOrNull(index) as? Row.Entry
                    if (current?.movable != true || draggedGroup != null) false else {
                        draggedGroup = current.group
                        orderBeforeDrag = groupIds(current.group)
                        touchHelper.startDrag(holder)
                        true
                    }
                }
                b.root.setOnLongClickListener(if (row.movable) start else null)
                b.categoryName.setOnLongClickListener(if (row.movable) start else null)
                b.editIcon.setOnLongClickListener(if (row.movable) start else null)
            }
        }
    }

    private class CategoryHolder(val binding: RowCategoryManageBinding) : RecyclerView.ViewHolder(binding.root)
    private class LabelHolder(view: TextView) : RecyclerView.ViewHolder(view)
    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()
}
