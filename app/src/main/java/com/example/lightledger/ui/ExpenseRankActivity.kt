package com.example.lightledger.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.lightledger.LedgerApp
import com.example.lightledger.data.ExpenseCategoryTotal
import com.example.lightledger.data.TransactionRow
import com.example.lightledger.databinding.ActivityExpenseRankBinding
import com.example.lightledger.databinding.RowTransactionBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ExpenseRankActivity : LedgerActivity() {
    companion object {
        fun open(context: Context, period: StatisticsPeriod, category: ExpenseCategoryTotal, income: Boolean = false) {
            context.startActivity(Intent(context, ExpenseRankActivity::class.java).putExtra("year", period.year)
                .putExtra("month", period.month ?: 0).putExtra("category_id", category.categoryId).putExtra("category_label", category.label)
                .putExtra("income", income))
        }
    }
    private lateinit var binding: ActivityExpenseRankBinding
    private val repository by lazy { (application as LedgerApp).repository }
    private val rows = mutableListOf<TransactionRow>()
    private lateinit var adapter: ExpenseRankAdapter
    private var job: Job? = null
    private var generation = 0
    private var loading = false
    private var exhausted = false
    private var start = 0L; private var end = 0L; private var categoryId = 0L
    private var income = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val year = intent.getIntExtra("year", 0); val month = intent.getIntExtra("month", 0).takeIf { it != 0 }
        categoryId = intent.getLongExtra("category_id", -1)
        income = intent.getBooleanExtra("income", false)
        if (year !in 1970..9999 || (month != null && month !in 1..12) || categoryId < 0) { finish(); return }
        val period = StatisticsPeriod(year, month)
        val bounds = period.bounds(); start = bounds.first; end = bounds.second
        binding = ActivityExpenseRankBinding.inflate(layoutInflater); setContentView(binding.root); SystemBarInsets.apply(binding.root)
        binding.goBack.setOnClickListener { finish() }
        binding.rankTitle.text = "${intent.getStringExtra("category_label").orEmpty()} · ${if (income) "收入" else "消费"}排行"
        binding.rankPeriod.text = "${period.label()} · 按单笔${if (income) "收入" else "支出"}从高到低" + if (income) "" else " · 未扣退款"
        adapter = ExpenseRankAdapter { id -> startActivity(Intent(this, TransactionEditorActivity::class.java).putExtra("id", id)) }
        val layout = LinearLayoutManager(this)
        binding.rankList.layoutManager = layout; binding.rankList.adapter = adapter
        binding.rankList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy >= 0 && layout.findLastVisibleItemPosition() >= adapter.itemCount - 6) load(false)
            }
        })
        binding.rankRetry.setOnClickListener { load(rows.isEmpty()) }
        (if (income) repository.incomeRankSummary(start, end, categoryId) else repository.expenseRankSummary(start, end, categoryId))
            .observe(this) { binding.rankSummary.text = "${UiFormat.money(it.amountCents)} · ${it.billCount}笔" }
        repository.transactionChangeSignal.observe(this) { load(true) }
    }
    private fun load(reset: Boolean) {
        if (!reset && (loading || exhausted)) return
        if (reset) { job?.cancel(); generation++; rows.clear(); adapter.submitList(emptyList()); exhausted = false }
        val current = generation; val offset = rows.size
        loading = true; binding.rankLoading.visibility = View.VISIBLE; binding.rankRetry.visibility = View.GONE; binding.rankEmpty.visibility = View.GONE
        job = lifecycleScope.launch {
            try {
                val page = if (income) repository.incomeRank(start, end, categoryId, 80, offset)
                    else repository.expenseRank(start, end, categoryId, 80, offset)
                if (current != generation) return@launch
                rows.addAll(page); exhausted = page.size < 80
                adapter.submitList(rows.toList())
                binding.rankEmpty.text = "本期暂无${if (income) "收入" else "支出"}"
                binding.rankEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (current == generation) { binding.rankEmpty.text = "加载失败，请重试"; binding.rankEmpty.visibility = View.VISIBLE; binding.rankRetry.visibility = View.VISIBLE }
            } finally {
                if (current == generation) { loading = false; binding.rankLoading.visibility = View.GONE }
            }
        }
    }
}

internal class ExpenseRankAdapter(private val chosen: (Long) -> Unit) : ListAdapter<TransactionRow, ExpenseRankAdapter.Holder>(object : DiffUtil.ItemCallback<TransactionRow>() {
    override fun areItemsTheSame(old: TransactionRow, new: TransactionRow) = old.transaction.id == new.transaction.id
    override fun areContentsTheSame(old: TransactionRow, new: TransactionRow) = old == new
}) {
    class Holder(val binding: RowTransactionBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(RowTransactionBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position); val t = row.transaction; val b = holder.binding
        val note = t.note?.takeIf { it.isNotBlank() }; val merchant = t.merchant?.takeIf { it.isNotBlank() }
        b.title.text = "${position + 1}. ${note ?: merchant ?: row.categoryName ?: "未分类"}"
        b.subtitle.text = listOfNotNull(merchant, UiFormat.method(t.paymentMethod)).joinToString(" · ")
        b.timestamp.text = UiFormat.date(t.timestamp); b.amount.text = UiFormat.money(t.amountCents)
        CategoryIcons.apply(b.categoryIcon, row.categoryName ?: "未分类", row.categoryIconKey)
        b.notePreview.visibility = View.GONE; b.selectionCheck.visibility = View.GONE
        b.root.setOnClickListener { chosen(t.id) }
    }
}
