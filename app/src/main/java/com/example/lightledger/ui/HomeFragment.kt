package com.example.lightledger.ui

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.CategoryTotal
import com.example.lightledger.databinding.FragmentHomeBinding
import com.example.lightledger.notification.NotificationAccess
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {
    private val postPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissionNotices() }
    private val model: LedgerViewModel by activityViewModels()
    private val repository by lazy { (requireActivity().application as LedgerApp).repository }
    private var binding: FragmentHomeBinding? = null
    private var hidden = true
    private var monthExpense = 0L
    private var monthRefund = 0L
    private var todayExpense = 0L
    private var categories: List<CategoryTotal> = emptyList()
    private var recentAdapter: TransactionAdapter? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentHomeBinding.inflate(inflater, container, false).also { binding = it }.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = binding ?: return
        b.monthLabel.text = SimpleDateFormat("yyyy年M月", Locale.CHINA).format(Date())
        val adapter = TransactionAdapter(onLongClick = { (requireActivity() as MainActivity).selectBill(it) }) {
            startActivity(Intent(requireContext(), TransactionEditorActivity::class.java).putExtra("id", it))
        }
        recentAdapter = adapter
        adapter.hideAmounts = true
        b.recentList.layoutManager = LinearLayoutManager(requireContext())
        b.recentList.adapter = adapter
        b.addTransaction.setOnClickListener { startActivity(Intent(requireContext(), TransactionEditorActivity::class.java)) }
        b.viewTransactions.setOnClickListener { openTab(R.id.nav_transactions) }
        b.viewAllTransactions.setOnClickListener { openTab(R.id.nav_transactions) }
        b.viewStatistics.setOnClickListener { openTab(R.id.nav_statistics) }
        b.toggleAmounts.setOnClickListener {
            hidden = !hidden
            viewLifecycleOwner.lifecycleScope.launch { repository.putSetting("home_hide_amounts", hidden.toString()) }
            updateAmounts()
        }
        b.openNotificationSettings.setOnClickListener { NotificationAccess.openSettings(requireActivity()) }
        b.openQuickNotificationSettings.setOnClickListener { NotificationAccess.requestPostPermissionOrOpenSettings(requireActivity(), postPermission) }
        model.recentTransactions.observe(viewLifecycleOwner) {
            adapter.submitList(it)
            b.emptyRecent.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
        }
        model.monthlyTotals.observe(viewLifecycleOwner) {
            monthExpense = it.expense
            monthRefund = it.refund
            updateAmounts()
        }
        model.todayTotals.observe(viewLifecycleOwner) { todayExpense = it.expense; updateAmounts() }
        model.monthlyCategories.observe(viewLifecycleOwner) { categories = it; updateAmounts() }
        viewLifecycleOwner.lifecycleScope.launch {
            hidden = repository.setting("home_hide_amounts")?.toBooleanStrictOrNull() ?: true
            updateAmounts()
        }
    }
    override fun onResume() {
        super.onResume()
        model.refreshDate()
        binding?.monthLabel?.text = SimpleDateFormat("yyyy年M月", Locale.CHINA).format(Date())
        refreshPermissionNotices()
    }
    private fun refreshPermissionNotices() {
        val listenerEnabled = NotificationAccess.isEnabled(requireContext())
        binding?.permissionNotice?.visibility = if (listenerEnabled) View.GONE else View.VISIBLE
        binding?.quickNotificationNotice?.visibility = if (listenerEnabled && !NotificationAccess.canPost(requireContext())) View.VISIBLE else View.GONE
    }
    private fun updateAmounts() {
        val b = binding ?: return
        b.toggleAmounts.contentDescription = if (hidden) "显示金额" else "隐藏金额"
        b.toggleAmounts.setIconResource(if (hidden) R.drawable.ic_eye else R.drawable.ic_eye_off)
        b.monthExpense.text = if (hidden) "¥****" else UiFormat.money(monthExpense - monthRefund)
        b.monthDetail.text = if (hidden) "支出 ¥**** · 退款 ¥****" else "支出 ${UiFormat.money(monthExpense)} · 退款 ${UiFormat.money(monthRefund)}"
        b.todayExpense.text = if (hidden) "¥****" else UiFormat.money(todayExpense)
        CategoryRows.show(b.categorySummary, categories.take(5).map { it.label to it.amountCents }, "本月暂无支出", hidden)
        recentAdapter?.hideAmounts = hidden
    }
    private fun openTab(id: Int) {
        requireActivity().findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = id
    }
    override fun onDestroyView() { recentAdapter = null; binding = null; super.onDestroyView() }
}
