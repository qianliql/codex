package com.example.lightledger.ui

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.TransactionRow
import com.example.lightledger.databinding.FragmentTransactionsBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class TransactionsFragment : Fragment() {
    private val pickScreenshot = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) startActivity(Intent(requireContext(), ScreenshotImportActivity::class.java).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
    private val pickScreenshots = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) startActivity(Intent(requireContext(), BatchImportActivity::class.java).apply {
            putParcelableArrayListExtra("uris", ArrayList(uris))
            clipData = ClipData.newUri(requireContext().contentResolver, "账单截图", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
    private val model: LedgerViewModel by activityViewModels()
    private val repository by lazy { (requireActivity().application as LedgerApp).repository }
    private var binding: FragmentTransactionsBinding? = null
    private var adapter: TransactionAdapter? = null
    private var monthCursor = MonthSelection.at()
    private var filter = MonthSelection.apply(TransactionFilter(), monthCursor)
    private val pendingOnly get() = filter.pendingOnly
    private val rows = mutableListOf<TransactionRow>()
    private var queryJob: Job? = null
    private var queryGeneration = 0
    private var loading = false
    private var hasMore = false
    private var pageFailed = false
    private val pageSize = 100
    private val selectedIds = linkedSetOf<Long>()
    private var selectionMode = false
    private var deleting = false
    private var selectionBack: OnBackPressedCallback? = null
    private val pagingScroll = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            // Layout/accessibility jumps can reach the end with a zero scroll delta.
            loadIfNearEnd()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentTransactionsBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        if (savedInstanceState != null) {
            filter = TransactionFilter.readFrom(savedInstanceState)
            monthCursor = StatisticsPeriod(savedInstanceState.getInt("month_year", monthCursor.year), savedInstanceState.getInt("month_number", monthCursor.month!!))
        }
        selectedIds.addAll(savedInstanceState?.getLongArray("selected_ids")?.toList().orEmpty())
        arguments?.getLong("select_id", 0)?.takeIf { it > 0 && savedInstanceState == null }?.let { selectedIds.add(it) }
        selectionMode = savedInstanceState?.getBoolean("selection_mode") ?: selectedIds.isNotEmpty()
        adapter = TransactionAdapter(onLongClick = { id ->
            if (!deleting) { selectionMode = true; selectedIds.add(id); updateSelectionUi() }
        }) { id ->
            if (deleting) return@TransactionAdapter
            if (selectionMode) {
                if (!selectedIds.add(id)) selectedIds.remove(id)
                updateSelectionUi()
            } else startActivity(Intent(requireContext(), TransactionEditorActivity::class.java).putExtra("id", id))
        }
        selectionBack = object : OnBackPressedCallback(selectionMode) {
            override fun handleOnBackPressed() { if (!deleting) endSelection() }
        }.also { requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }
        b.cancelSelection.setOnClickListener { endSelection() }
        b.selectLoaded.setOnClickListener {
            val loaded = rows.map { it.transaction.id }
            if (loaded.isNotEmpty() && loaded.all { it in selectedIds }) selectedIds.removeAll(loaded.toSet()) else selectedIds.addAll(loaded)
            updateSelectionUi()
        }
        b.deleteSelected.setOnClickListener { confirmBatchDelete() }
        b.transactionList.layoutManager = LinearLayoutManager(requireContext())
        b.transactionList.adapter = adapter
        b.transactionList.addOnScrollListener(pagingScroll)
        adapter?.submitList(rows.toList())
        b.addTransaction.setOnClickListener { startActivity(Intent(requireContext(), TransactionEditorActivity::class.java)) }
        b.moreActions.setOnClickListener { showMoreActions() }
        b.searchFilters.setOnClickListener { showFilters() }
        b.previousMonth.setOnClickListener { chooseMonth(MonthSelection.move(monthCursor, -1)) }
        b.nextMonth.setOnClickListener { chooseMonth(MonthSelection.move(monthCursor, 1)) }
        b.billMonthPicker.setOnClickListener { MonthSelection.show(requireContext(), monthCursor, ::chooseMonth) }
        b.refreshBills.setColorSchemeColors(requireContext().ledgerColor(R.color.ledger_teal))
        b.refreshBills.setOnChildScrollUpCallback { _, _ -> b.transactionList.canScrollVertically(-1) }
        b.refreshBills.setOnRefreshListener {
            com.example.lightledger.notification.NotificationAccess.reconnectIfNeeded(requireContext())
            refresh()
        }
        updateSelectionUi()
        model.transactionChangeSignal.observe(viewLifecycleOwner) { if (!selectionMode) refresh() }
        arguments?.getLong("select_id", 0)?.takeIf { it > 0 && savedInstanceState == null }?.let { id ->
            viewLifecycleOwner.lifecycleScope.launch {
                repository.transaction(id)?.let { chooseMonth(MonthSelection.at(it.timestamp)) }
            }
        }
    }

    override fun onResume() { super.onResume(); if (binding != null && (!selectionMode || rows.isEmpty())) refresh() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLongArray("selected_ids", selectedIds.toLongArray())
        outState.putBoolean("selection_mode", selectionMode)
        outState.putInt("month_year", monthCursor.year)
        outState.putInt("month_number", monthCursor.month!!)
        filter.writeTo(outState)
        super.onSaveInstanceState(outState)
    }

    private fun updateSelectionUi() {
        val b = binding ?: return
        b.refreshBills.isEnabled = !selectionMode
        b.normalActions.visibility = if (selectionMode) View.GONE else View.VISIBLE
        b.selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        b.selectionCount.text = "已选 ${selectedIds.size} 笔"
        b.deleteSelected.text = "删除（${selectedIds.size}）"
        b.deleteSelected.isEnabled = selectedIds.isNotEmpty() && !deleting
        b.cancelSelection.isEnabled = !deleting
        b.selectLoaded.isEnabled = !deleting && rows.isNotEmpty()
        b.selectLoaded.text = if (rows.isNotEmpty() && rows.all { it.transaction.id in selectedIds }) "取消全选" else "全选已加载"
        selectionBack?.isEnabled = selectionMode
        adapter?.setSelection(selectedIds, selectionMode)
    }

    private fun endSelection() {
        if (deleting) return
        selectedIds.clear()
        selectionMode = false
        updateSelectionUi()
        refresh()
    }

    private fun confirmBatchDelete() {
        val targets = selectedIds.toList()
        if (targets.isEmpty() || deleting) return
        AlertDialog.Builder(requireContext()).setTitle("删除 ${targets.size} 笔账单？")
            .setMessage("删除后无法撤销，请核对已选账单。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                deleting = true
                updateSelectionUi()
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val count = repository.deleteTransactions(targets)
                        Toast.makeText(requireContext(), "已删除 $count 笔账单", Toast.LENGTH_SHORT).show()
                        deleting = false
                        endSelection()
                    } catch (e: CancellationException) {
                        deleting = false
                        throw e
                    } catch (e: Exception) {
                        deleting = false
                        updateSelectionUi()
                        Toast.makeText(requireContext(), "删除失败，请重试", Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
    }

    private fun refresh() {
        queryGeneration++
        queryJob?.cancel()
        updateSearchUi()
        binding?.resultCount?.text = "正在查找账单…"
        rows.clear()
        adapter?.submitList(emptyList())
        binding?.transactionList?.scrollToPosition(0)
        hasMore = false
        pageFailed = false
        loading = false
        binding?.emptyTransactions?.visibility = View.GONE
        loadNext()
    }

    private fun loadIfNearEnd() {
        val list = binding?.transactionList ?: return
        val manager = list.layoutManager as? LinearLayoutManager ?: return
        if (!loading && hasMore && !pageFailed && manager.findLastVisibleItemPosition() >= rows.size - 10) loadNext()
    }

    private fun loadNext() {
        val b = binding ?: return
        if (loading || (rows.isNotEmpty() && !hasMore)) return
        loading = true
        val generation = queryGeneration
        b.resultCount.text = if (rows.isEmpty()) "正在查找账单…" else "已显示 ${rows.size} 笔 · 加载中…"
        queryJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = repository.searchTransactions(filter.search, filter.start, filter.end,
                    filter.categoryId, filter.tagId, filter.method, filter.source, filter.type,
                    pendingOnly, pageSize, rows.size)
                if (generation != queryGeneration || binding !== b) return@launch
                rows.addAll(page)
                hasMore = page.size == pageSize
                adapter?.submitList(rows.toList()) {
                    // Wait for DiffUtil/layout before deciding whether another page is needed.
                    b.transactionList.post { if (generation == queryGeneration && binding === b) loadIfNearEnd() }
                }
                b.emptyTransactions.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                b.emptyTransactions.text = when {
                    pendingOnly -> "没有符合条件的待处理账单"
                    nonDateCount > 0 -> "没有找到匹配账单\n试试减少条件或更换关键词"
                    else -> "这个月份还没有账单\n点击“记一笔”或切换月份"
                }
                b.resultCount.text = (if (hasMore) "已显示 ${rows.size} 笔" else "${rows.size} 笔") + if (nonDateCount > 0) " · 筛选 $nonDateCount 项" else " · 长按多选"
                updateSelectionUi()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == queryGeneration && binding === b) {
                    // Stop automatic retries until the user explicitly refreshes.
                    pageFailed = true
                    b.resultCount.text = "读取失败，请下拉刷新"
                    Toast.makeText(requireContext(), "账单读取失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
            finally {
                if (generation == queryGeneration) {
                    loading = false; b.refreshBills.isRefreshing = false
                }
            }
        }
    }

    private val searchPage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            filter = TransactionFilter.readFrom(result.data?.getBundleExtra(TransactionFilter.EXTRA))
            refresh()
        }
    }

    private fun showFilters() {
        searchPage.launch(Intent(requireContext(), TransactionSearchActivity::class.java)
            .putExtra(TransactionFilter.EXTRA, filter.writeTo(Bundle())))
    }

    private fun showMoreActions() {
        val b = binding ?: return
        PopupMenu(requireContext(), b.moreActions).apply {
            inflate(R.menu.transaction_actions)
            menu.findItem(R.id.action_pending).title = if (pendingOnly) "显示全部账单" else "待处理账单"
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_clear_filters -> { filter = TransactionFilter(start = filter.start, end = filter.end); refresh() }
                    R.id.action_pending -> { filter = filter.copy(pendingOnly = !pendingOnly); refresh() }
                    R.id.action_import_screenshot -> pickScreenshot.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    R.id.action_import_screenshots -> pickScreenshots.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    R.id.action_refresh_bills -> {
                        com.example.lightledger.notification.NotificationAccess.reconnectIfNeeded(requireContext())
                        refresh()
                    }
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    private val nonDateCount get() = filter.copy(start = 0, end = Long.MAX_VALUE).activeCount

    private fun chooseMonth(month: StatisticsPeriod) {
        if (month.year !in 1970..9999) return
        monthCursor = month
        filter = MonthSelection.apply(filter, month)
        refresh()
    }

    private fun updateSearchUi() {
        val b = binding ?: return
        val exact = MonthSelection.from(filter)
        if (exact != null) monthCursor = exact
        val label = exact?.label() ?: if (filter.start == 0L && filter.end == Long.MAX_VALUE) "全部月份" else "自选日期"
        b.billMonthLabel.text = "$label ▾"
        b.billMonthPicker.contentDescription = "$label，点击选择年份和月份"
        b.searchFilters.contentDescription = "搜索账单，已选 $nonDateCount 项筛选条件"
        b.previousMonth.isEnabled = monthCursor.year > 1970 || monthCursor.month != 1
        b.nextMonth.isEnabled = monthCursor.year < 9999 || monthCursor.month != 12
    }

    override fun onDestroyView() {
        queryGeneration++
        queryJob?.cancel()
        binding?.transactionList?.removeOnScrollListener(pagingScroll)
        binding?.transactionList?.adapter = null
        adapter = null
        selectionBack = null
        binding = null
        super.onDestroyView()
    }
}
