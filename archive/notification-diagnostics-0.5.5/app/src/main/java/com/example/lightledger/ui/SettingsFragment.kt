package com.example.lightledger.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.lightledger.LedgerApp
import com.example.lightledger.backup.BackupCodec
import com.example.lightledger.export.CsvExporter
import com.example.lightledger.databinding.FragmentSettingsBinding
import com.example.lightledger.notification.NotificationAccess
import com.example.lightledger.notification.NotificationDiagnostics
import com.example.lightledger.notification.NotificationTrace
import com.example.lightledger.notification.PaymentNotificationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.ByteArrayOutputStream

class SettingsFragment : Fragment() {
    private val postPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }
    private val createCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) writeExport(uri, false)
    }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeExport(uri, true)
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) inspectBackup(uri)
    }
    private val openHistory = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) inspectHistory(uri)
    }
    private var importingHistory = false
    private val createDetection = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewLifecycleOwner.lifecycleScope.launch {
            try {
                val context = requireContext()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(NotificationTrace.export(context)) } ?: error("无法创建报告")
                }
                Toast.makeText(context, "检测报告已保存", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(requireContext(), "报告导出失败，请重试", Toast.LENGTH_SHORT).show() }
        }
    }
    private val repository by lazy { (requireActivity().application as LedgerApp).repository }
    private var binding: FragmentSettingsBinding? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentSettingsBinding.inflate(inflater, container, false)
        binding = b
        b.themeSettings.setOnClickListener { startActivity(Intent(requireContext(), ThemeActivity::class.java)) }
        b.manageCategories.setOnClickListener { startActivity(Intent(requireContext(), CategoryActivity::class.java)) }
        b.manageTags.setOnClickListener { startActivity(Intent(requireContext(), TagsActivity::class.java)) }
        b.manageMerchantRules.setOnClickListener { startActivity(Intent(requireContext(), MerchantRulesActivity::class.java)) }
        b.exportCsv.setOnClickListener { createCsv.launch("lightledger_${fileDate()}.csv") }
        b.exportBackup.setOnClickListener { createBackup.launch("lightledger_backup_${fileDate()}.json") }
        b.importBackup.setOnClickListener { openBackup.launch(arrayOf("*/*")) }
        b.importHistory.setOnClickListener { if (!importingHistory) openHistory.launch(arrayOf("*/*")) }
        b.notificationSettings.setOnClickListener { NotificationAccess.openSettings(requireActivity()) }
        b.refreshNotificationDiagnostic.setOnClickListener { refreshStatus() }
        b.quickNotificationSettings.setOnClickListener { NotificationAccess.requestPostPermissionOrOpenSettings(requireActivity(), postPermission) }
        b.captureSamples.isChecked = NotificationTrace.enabled(requireContext())
        b.captureSamples.setOnCheckedChangeListener { _, checked -> NotificationTrace.setEnabled(requireContext(), checked); refreshStatus() }
        b.inspectNotifications.setOnClickListener {
            NotificationAccess.reconnectIfNeeded(requireContext())
            val inspected = PaymentNotificationService.inspectCurrent()
            Toast.makeText(requireContext(), if (inspected) "已检测通知栏保留的通知" else "监听未连接，请先允许自动记账权限", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
        b.exportDetection.setOnClickListener { createDetection.launch("lightledger_detection_${fileDate()}.json") }
        b.clearSamples.setOnClickListener { NotificationTrace.clear(requireContext()); refreshStatus() }
        return b.root
    }
    override fun onResume() {
        super.onResume()
        refreshStatus()
    }
    private fun refreshStatus() {
        NotificationAccess.reconnectIfNeeded(requireContext())
        binding?.themeSettings?.text = "主题配色 · ${LedgerThemes.selected(requireContext()).label}"
        binding?.notificationStatus?.text = if (NotificationAccess.isEnabled(requireContext())) "通知自动记账：已开启" else "通知自动记账：未开启"
        binding?.notificationDiagnostic?.text = NotificationDiagnostics.summary(requireContext())
        binding?.quickNotificationStatus?.text = if (NotificationAccess.canPost(requireContext())) "快捷分类通知：已开启" else "快捷分类通知：未开启"
        binding?.sampleStatus?.text = (if (NotificationTrace.enabled(requireContext())) "样本记录已开启" else "样本记录未开启") + " · 已保存 ${NotificationTrace.count(requireContext())} 条"
    }
    private fun fileDate() = SimpleDateFormat("yyyy_MM_dd", Locale.ROOT).format(Date())

    private fun inspectHistory(uri: Uri) {
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(out.size() + count <= 8 * 1024 * 1024) { "导入文件超过8MB" }
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    } ?: error("无法读取文件")
                    com.example.lightledger.history.HistoryImportCodec.decode(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
                }
                val date = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.CHINA)
                val detail = data.records.joinToString("\n\n") { row ->
                    "${date.format(Date(row.timestamp))} · ${row.category} · ${UiFormat.money(row.amountCents)}\n备注：${row.note?.takeIf { it.isNotBlank() } ?: "无"}"
                }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(context).setTitle("追加导入历史账单")
                    .setMessage("${data.title}\n共${data.expectedCount}笔，合计${UiFormat.money(data.expectedTotalCents)}。\n保留现有账单；重复导入同一记录会跳过。\n\n$detail")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("追加导入") { _, _ ->
                        if (!importingHistory) {
                            importingHistory = true
                            binding?.importHistory?.isEnabled = false
                            viewLifecycleOwner.lifecycleScope.launch {
                                try {
                                    val result = repository.appendHistory(data)
                                    Toast.makeText(context, "已导入${result.imported}笔，跳过${result.skipped}笔已导入记录", Toast.LENGTH_LONG).show()
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { Toast.makeText(context, "导入失败，账单未更改：${e.localizedMessage}", Toast.LENGTH_LONG).show() }
                                finally { importingHistory = false; binding?.importHistory?.isEnabled = true }
                            }
                        }
                    }.show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(context, "文件无法导入：${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun writeExport(uri: Uri, backup: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val snapshot = repository.snapshot()
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                        if (backup) writer.write(BackupCodec.encode(snapshot)) else CsvExporter.write(snapshot, writer)
                    } ?: error("无法创建文件")
                }
                Toast.makeText(requireContext(), if (backup) "备份已保存" else "CSV 已导出", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "导出失败：${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun inspectBackup(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val resolver = requireContext().contentResolver
                    val stream = resolver.openInputStream(uri) ?: error("无法读取备份")
                    val bytes = stream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 50 * 1024 * 1024) { "备份文件超过 50 MB" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    BackupCodec.decode(bytes.toString(Charsets.UTF_8))
                }
                AlertDialog.Builder(requireContext()).setTitle("确认恢复备份")
                    .setMessage("备份包含 ${snapshot.transactions.size} 笔账单、${snapshot.categories.size} 个分类、${snapshot.tags.size} 个标签和 ${snapshot.merchantRules.size} 条商户规则。恢复会替换当前所有本机数据，请先确认已保存需要保留的数据。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("替换并恢复") { _, _ -> viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            repository.restore(snapshot)
                            Toast.makeText(requireContext(), "恢复完成", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "恢复失败：${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    } }.show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "备份无法读取：${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }
    override fun onDestroyView() { binding = null; super.onDestroyView() }
}
