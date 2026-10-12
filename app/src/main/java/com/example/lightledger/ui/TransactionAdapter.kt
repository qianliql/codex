package com.example.lightledger.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.lightledger.data.TransactionRow
import com.example.lightledger.data.TransactionType
import com.example.lightledger.data.ClassificationStatus
import com.example.lightledger.data.requiresAmountConfirmation
import com.example.lightledger.R
import com.example.lightledger.databinding.RowTransactionBinding

class TransactionAdapter(private val onLongClick: ((Long) -> Unit)? = null, private val onClick: (Long) -> Unit) :
    ListAdapter<TransactionRow, TransactionAdapter.Holder>(object : DiffUtil.ItemCallback<TransactionRow>() {
        override fun areItemsTheSame(old: TransactionRow, new: TransactionRow) = old.transaction.id == new.transaction.id
        override fun areContentsTheSame(old: TransactionRow, new: TransactionRow) = old == new
    }) {
    var hideAmounts: Boolean = false
        set(value) { field = value; notifyDataSetChanged() }
    private var selectedIds: Set<Long> = emptySet()
    private var selecting = false
    fun setSelection(ids: Set<Long>, active: Boolean) {
        if (selectedIds == ids && selecting == active) return
        selectedIds = ids.toSet()
        selecting = active
        notifyDataSetChanged()
    }
    inner class Holder(val binding: RowTransactionBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        RowTransactionBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val transaction = row.transaction
        holder.binding.title.text = transaction.merchant?.takeIf { it.isNotBlank() }
            ?: row.categoryName?.takeIf { it.isNotBlank() } ?: UiFormat.method(transaction.paymentMethod)
        val note = transaction.note?.takeIf { it.isNotBlank() }
        holder.binding.notePreview.text = note?.let { "备注：$it" }.orEmpty()
        holder.binding.notePreview.visibility = if (note == null) android.view.View.GONE else android.view.View.VISIBLE
        val category = if (transaction.classificationStatus == ClassificationStatus.NEED_CONFIRM) "待确认" else row.categoryName ?: "未分类"
        holder.binding.subtitle.text = "$category · ${UiFormat.method(transaction.paymentMethod)} · ${UiFormat.type(transaction.type)}" +
            if (transaction.requiresAmountConfirmation() && transaction.amountCents > 0) " · 金额待确认" else ""
        holder.binding.timestamp.text = java.text.SimpleDateFormat("yyyy.MM.dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(transaction.timestamp))
        CategoryIcons.apply(holder.binding.categoryIcon, row.categoryName ?: "未分类", row.categoryIconKey)
        holder.binding.selectionCheck.visibility = if (selecting) android.view.View.VISIBLE else android.view.View.GONE
        holder.binding.selectionCheck.isChecked = transaction.id in selectedIds
        holder.binding.root.isCheckable = selecting
        holder.binding.root.checkedIcon = null
        holder.binding.root.isChecked = transaction.id in selectedIds
        holder.binding.root.strokeColor = holder.itemView.context.ledgerColor(if (transaction.id in selectedIds) R.color.ledger_teal else R.color.ledger_border)
        val sign = if (transaction.type == TransactionType.EXPENSE || transaction.type == TransactionType.TRANSFER) "−" else "+"
        holder.binding.amount.text = if (hideAmounts) "¥****" else if (transaction.classificationStatus == ClassificationStatus.NEED_CONFIRM && transaction.amountCents == 0L) "金额待确认" else sign + UiFormat.money(transaction.amountCents)
        holder.binding.amount.setTextColor(holder.itemView.context.ledgerColor(
            if (transaction.type == TransactionType.REFUND || transaction.type == TransactionType.INCOME) R.color.ledger_teal else R.color.ledger_ink))
        holder.binding.root.setOnClickListener { onClick(transaction.id) }
        holder.binding.root.setOnLongClickListener {
            if (onLongClick == null) false else { onLongClick.invoke(transaction.id); true }
        }
    }
}
