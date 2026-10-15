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
import com.example.lightledger.databinding.FragmentSettingsBinding
import com.example.lightledger.notification.NotificationAccess
import com.example.lightledger.notification.ListenerRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.ByteArrayOutputStream

class SettingsFragment : Fragment() {
    private var pendingProtection = false
    private var refreshingProtection = false
    private var changingProtection = false
    private val postPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingProtection) {
            pendingProtection = false
            if (NotificationAccess.canPost(requireContext())) enableProtection()
            else Toast.makeText(requireContext(), "允许发送通知后，才能显示后台保护状态", Toast.LENGTH_LONG).show()
        }
        refreshStatus()
    }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeExport(uri)
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) inspectBackup(uri)
    }
    private val repository by lazy { (requireActivity().application as LedgerApp).repository }
    private var binding: FragmentSettingsBinding? = null
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusCheck = object : Runnable {
        override fun run() {
            if (isAdded && binding != null) refreshStatus()
            statusHandler.postDelayed(this, 2_000L)
        }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentSettingsBinding.inflate(inflater, container, false)
        binding = b
        b.manageTags.setOnClickListener { startActivity(Intent(requireContext(), TagsActivity::class.java)) }
        b.currentVersion.text = "当前版本 ${com.example.lightledger.update.InstalledVersion.read(requireContext()).name}"
        b.checkForUpdates.setOnClickListener { startActivity(Intent(requireContext(), AppUpdateActivity::class.java)) }
        b.exportBackup.setOnClickListener { createBackup.launch("lightledger_backup_${fileDate()}.json") }
        b.importBackup.setOnClickListener { openBackup.launch(arrayOf("*/*")) }
        b.notificationSettings.setOnClickListener { NotificationAccess.openSettings(requireActivity()) }
        b.notificationStatus.setOnClickListener { NotificationAccess.repair(requireActivity()) }
        b.quickNotificationSettings.setOnClickListener { NotificationAccess.requestPostPermissionOrOpenSettings(requireActivity(), postPermission) }
        b.backgroundSettings.setOnClickListener { NotificationAccess.backgroundGuide(requireActivity()) }
        b.backgroundProtection.setOnCheckedChangeListener { _, checked ->
            if (!refreshingProtection) {
                when {
                    !checked -> {
                        changingProtection = true
                        ListenerRuntime.disable(requireContext()) {
                            changingProtection = false
                            if (isAdded) refreshStatus()
                        }
                    }
                    !NotificationAccess.isEnabled(requireContext()) -> {
                        Toast.makeText(requireContext(), "请先允许通知读取", Toast.LENGTH_SHORT).show()
                        NotificationAccess.openSettings(requireActivity())
                    }
                    !NotificationAccess.canPost(requireContext()) -> {
                        pendingProtection = true
                        NotificationAccess.requestPostPermissionOrOpenSettings(requireActivity(), postPermission)
                    }
                    else -> enableProtection()
                }
                refreshStatus()
            }
        }
        return b.root
    }
    override fun onResume() {
        super.onResume()
        if (pendingProtection && NotificationAccess.canPost(requireContext())) {
            pendingProtection = false
            enableProtection()
        }
        statusHandler.removeCallbacks(statusCheck)
        statusCheck.run()
    }
    override fun onPause() { statusHandler.removeCallbacks(statusCheck); super.onPause() }
    private fun enableProtection() {
        changingProtection = true
        ListenerRuntime.enable(requireContext()) { started ->
            changingProtection = false
            if (!isAdded) return@enable
            if (!started) Toast.makeText(requireContext(), "后台保护未能启动，请检查系统后台限制", Toast.LENGTH_LONG).show()
            refreshStatus()
        }
    }
    internal fun refreshStatus() {
        ListenerRuntime.check(requireContext())
        val runtime = ListenerRuntime.state(requireContext())
        binding?.notificationStatus?.text = when {
            !NotificationAccess.isEnabled(requireContext()) -> "通知自动记账：未开启"
            !runtime.available -> "通知自动记账：正在获取后台状态"
            runtime.connected -> "通知自动记账：监听已连接"
            runtime.checking -> "通知自动记账：正在检查连接"
            else -> "通知自动记账：未连接，正在恢复"
        }
        refreshingProtection = true
        if (!changingProtection) binding?.backgroundProtection?.isChecked = runtime.protectionEnabled
        binding?.backgroundProtection?.isEnabled = runtime.available && !changingProtection
        refreshingProtection = false
        binding?.protectionStatus?.text = when {
            !runtime.available -> "正在检查后台保护状态"
            !runtime.protectionEnabled -> "开启后台保护有助于持续监听；系统清理仍可能中断"
            !NotificationAccess.isEnabled(requireContext()) -> "请先允许通知读取，后台保护已暂停"
            runtime.protectionRunning -> "后台保护运行中 · 常驻通知无声音"
            else -> "后台保护等待启动，请检查系统后台限制"
        }
        binding?.batteryStatus?.text = when {
            NotificationAccess.backgroundRestricted(requireContext()) -> "系统已限制后台运行，请允许后台活动"
            NotificationAccess.batteryUnrestricted(requireContext()) -> "已允许不受电池优化限制"
            else -> "电池优化未豁免，闲置时可能被系统限制"
        }
        binding?.quickNotificationStatus?.text = if (NotificationAccess.canPost(requireContext())) "快捷分类通知：已开启" else "快捷分类通知：未开启"
    }
    private fun fileDate() = SimpleDateFormat("yyyy_MM_dd", Locale.ROOT).format(Date())

    private fun writeExport(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val snapshot = repository.snapshot()
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                        writer.write(BackupCodec.encode(snapshot))
                    } ?: error("无法创建文件")
                }
                Toast.makeText(requireContext(), "备份已保存", Toast.LENGTH_SHORT).show()
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
