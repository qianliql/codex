package com.example.lightledger.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.lightledger.databinding.ActivityAppUpdateBinding
import com.example.lightledger.update.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class AppUpdateActivity : LedgerActivity() {
    private lateinit var binding: ActivityAppUpdateBinding
    private var pendingInstall = false
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingInstall && packageManager.canRequestPackageInstalls()) installReady()
        else if (pendingInstall) Toast.makeText(this, "允许轻账本安装更新后可继续", Toast.LENGTH_LONG).show()
        pendingInstall = false
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.apply(binding.root)
        pendingInstall = savedInstanceState?.getBoolean("pending_install") == true
        binding.goBack.setOnClickListener { finish() }
        binding.currentVersion.text = "当前版本 ${InstalledVersion.read(this).name}"
        binding.checkForUpdates.setOnClickListener { UpdateManager.check(this, manual = true) }
        binding.downloadUpdate.setOnClickListener {
            when (val value = UpdateManager.state.value) {
                is UpdateState.Available -> UpdateManager.download(this, value.info)
                is UpdateState.Failed -> value.info?.let { UpdateManager.download(this, it) }
                else -> Unit
            }
        }
        binding.installUpdate.setOnClickListener { installReady() }
        binding.updateHistory.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateSpec.RELEASE_PAGE))) }
                .onFailure { Toast.makeText(this, "请先安装可以打开网页的浏览器", Toast.LENGTH_LONG).show() }
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            UpdateManager.state.collect { render(it) }
        } }
        if (savedInstanceState == null && UpdateManager.state.value !is UpdateState.Ready &&
            UpdateManager.state.value !is UpdateState.Downloading) UpdateManager.check(this, manual = true)
    }
    private fun render(value: UpdateState) {
        val info = when (value) {
            is UpdateState.Available -> value.info
            is UpdateState.Downloading -> value.info
            is UpdateState.Ready -> value.info
            is UpdateState.Failed -> value.info
            else -> null
        }
        val compatible = info == null || info.minSdk <= Build.VERSION.SDK_INT
        binding.updateStatus.text = when (value) {
            UpdateState.Idle -> "点击检查更新"
            UpdateState.Checking -> "正在检查新版本"
            UpdateState.Current -> "已是最新版本"
            is UpdateState.Available -> if (compatible) "发现新版本 ${value.info.name}" else "新版本需要更高版本的 Android 系统"
            is UpdateState.Downloading -> "正在下载 ${value.info.name} · ${value.bytes * 100 / value.info.size}%"
            is UpdateState.Ready -> "${value.info.name} 已下载，点击安装"
            is UpdateState.Failed -> value.message
        }
        binding.updateNotes.text = info?.notes.orEmpty()
        binding.updateNotes.visibility = if (info == null) View.GONE else View.VISIBLE
        binding.checkForUpdates.isEnabled = value != UpdateState.Checking && value !is UpdateState.Downloading
        binding.downloadUpdate.visibility = if (compatible && (value is UpdateState.Available || value is UpdateState.Failed && info != null)) View.VISIBLE else View.GONE
        binding.installUpdate.visibility = if (value is UpdateState.Ready) View.VISIBLE else View.GONE
        binding.downloadProgress.visibility = if (value is UpdateState.Downloading) View.VISIBLE else View.GONE
        if (value is UpdateState.Downloading) binding.downloadProgress.progress = (value.bytes * 100 / value.info.size).toInt()
        binding.downloadSize.text = info?.let {
            String.format(Locale.CHINA, "安装包 %.1f MB", it.size / 1024.0 / 1024) +
                if (it.mirrorUrl != null) " · 优先 Gitee，备用 GitHub" else " · GitHub"
        }.orEmpty()
        binding.downloadSize.visibility = if (info == null) View.GONE else View.VISIBLE
    }
    private fun installReady() {
        val ready = UpdateManager.state.value as? UpdateState.Ready ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstall = true
            runCatching { installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                .onFailure { pendingInstall = false; Toast.makeText(this, "请在系统设置中允许轻账本安装应用", Toast.LENGTH_LONG).show() }
            return
        }
        lifecycleScope.launch {
            binding.installUpdate.isEnabled = false
            try {
                withContext(Dispatchers.IO) { UpdateApkVerifier.verify(this@AppUpdateActivity, ready.file, ready.info) }
                val uri = FileProvider.getUriForFile(applicationContext, "$packageName.update-files", ready.file)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (error: Exception) {
                android.util.Log.w("LightLedgerUpdate", "Install handoff failed", error)
                Toast.makeText(this@AppUpdateActivity, "无法安装更新，请重新下载或查看更新记录", Toast.LENGTH_LONG).show()
            }
            finally { binding.installUpdate.isEnabled = true }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("pending_install", pendingInstall); super.onSaveInstanceState(outState) }
}
