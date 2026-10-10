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
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.PaymentMethod
import com.example.lightledger.data.TransactionRow
import com.example.lightledger.data.TransactionSource
import com.example.lightledger.data.TransactionType
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
    private var filter = TransactionFilter()
    private val pendingOnly get() = filter.pendingOnly
    private var summaryJob: Job? = null
    private val rows = mutableListOf<TransactionRow>()
    private var queryJob: Job? = null
    private var queryGeneration = 0
    private var loading = false
    private var hasMore = false
    private val pageSize = 100
    private val selectedIds = linkedSetOf<Long>()
    private var selectionMode = false
    private var deleting = false
    private var selectionBack: OnBackPressedCallback? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentTransactionsBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        if (savedInstanceState != null) filter = TransactionFilter.readFrom(savedInstanceState)
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
        adapter?.submitList(rows.toList())
        b.pendingFilter.text = if (pendingOnly) "显示全部" else "待处理账单"
        b.addTransaction.setOnClickListener { startActivity(Intent(requireContext(), TransactionEditorActivity::class.java)) }
        b.importScreenshot.setOnClickListener {
            pickScreenshot.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        b.importScreenshots.setOnClickListener {
            pickScreenshots.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        b.pendingFilter.setOnClickListener {
            filter = filter.copy(pendingOnly = !pendingOnly)
            b.pendingFilter.text = if (pendingOnly) "显示全部" else "待处理账单"
            refresh()
        }
        b.searchFilters.setOnClickListener { showFilters() }
        b.loadMore.setOnClickListener { loadNext() }
        b.clearFilters.setOnClickListener { filter = TransactionFilter(); b.pendingFilter.text = "待处理账单"; refresh() }
        b.refreshBills.setColorSchemeColors(requireContext().ledgerColor(R.color.ledger_teal))
        b.refreshBills.setOnChildScrollUpCallback { _, _ -> b.transactionList.canScrollVertically(-1) }
        b.refreshBills.setOnRefreshListener {
            com.example.lightledger.notification.NotificationAccess.reconnectIfNeeded(requireContext())
            refresh()
        }
        b.refreshNow.setOnClickListener { refresh() }
        updateSelectionUi()
        model.transactionChangeSignal.observe(viewLifecycleOwner) { if (!selectionMode) refresh() }
    }

    override fun onResume() { super.onResume(); if (binding != null && (!selectionMode || rows.isEmpty())) refresh() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLongArray("selected_ids", selectedIds.toLongArray())
        outState.putBoolean("selection_mode", selectionMode)
        filter.writeTo(outState)
        super.onSaveInstanceState(outState)
    }

    private fun updateSelectionUi() {
        val b = binding ?: return
        b.refreshBills.isEnabled = !selectionMode
        b.normalActions.visibility = if (selectionMode) View.GONE else View.VISIBLE
        b.selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        b.selectionCount.text = "已选 ${selectedIds.size} 笔账单"
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
        hasMore = false
        loading = false
        loadNext()
    }

    private fun loadNext() {
        val b = binding ?: return
        if (loading || (rows.isNotEmpty() && !hasMore)) return
        loading = true
        val generation = queryGeneration
        b.loadMore.isEnabled = false
        queryJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = repository.searchTransactions(filter.search, filter.start, filter.end,
                    filter.categoryId, filter.tagId, filter.method, filter.source, filter.type,
                    pendingOnly, pageSize, rows.size)
                rows.addAll(page)
                hasMore = page.size == pageSize
                adapter?.submitList(rows.toList())
                b.emptyTransactions.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                b.emptyTransactions.text = when {
                    pendingOnly -> "没有符合条件的待处理账单"
                    filter.activeCount > 0 -> "没有找到匹配账单\n试试减少条件或更换关键词"
                    else -> "还没有账单，点击“记一笔”开始"
                }
                b.resultCount.text = (if (hasMore) "已显示 ${rows.size} 笔" else "${rows.size} 笔账单") + " · 长按可多选删除"
                b.loadMore.visibility = if (hasMore) View.VISIBLE else View.GONE
                b.loadMore.isEnabled = true
                updateSelectionUi()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { b.resultCount.text = "读取失败，请下拉刷新"; Toast.makeText(requireContext(), "账单读取失败，请重试", Toast.LENGTH_SHORT).show() }
            finally {
                if (generation == queryGeneration) {
                    loading = false; b.refreshBills.isRefreshing = false; b.loadMore.isEnabled = true
                }
            }
        }
    }

    private val searchPage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            filter = TransactionFilter.readFrom(result.data?.getBundleExtra(TransactionFilter.EXTRA))
            binding?.pendingFilter?.text = if (pendingOnly) "显示全部" else "待处理账单"
            refresh()
        }
    }

    private fun showFilters() {
        searchPage.launch(Intent(requireContext(), TransactionSearchActivity::class.java)
            .putExtra(TransactionFilter.EXTRA, filter.writeTo(Bundle())))
    }

    private fun updateSearchUi() {
        val b = binding ?: return
        val applied = filter
        b.filterSummary.visibility = if (applied.activeCount > 0) View.VISIBLE else View.GONE
        b.recordsTitle.text = if (applied.activeCount > 0) "搜索结果" else "全部记录"
        b.searchFilters.text = if (applied.activeCount > 0) "搜索 · ${applied.activeCount}" else "搜索"
        b.searchFilters.contentDescription = "搜索账单，已选 ${applied.activeCount} 项条件"
        summaryJob?.cancel()
        if (applied.activeCount > 0) summaryJob = viewLifecycleOwner.lifecycleScope.launch {
            b.filterDescription.text = applied.description(repository.categoryList(), repository.tags())
        }
    }

    override fun onDestroyView() {
        queryJob?.cancel()
        summaryJob?.cancel()
        adapter = null
        selectionBack = null
        binding = null
        super.onDestroyView()
    }
}
