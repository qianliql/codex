package com.example.lightledger.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.ClassificationStatus
import com.example.lightledger.data.LedgerTransaction
import com.example.lightledger.data.TransactionSource
import com.example.lightledger.databinding.ActivityBatchImportBinding
import com.example.lightledger.ocr.OcrManager
import com.example.lightledger.ocr.ParsedScreenshot
import com.example.lightledger.ocr.ScreenshotParserRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class BatchImportActivity : LedgerActivity() {
    private enum class State { READY, NEED_CONFIRM, FAILED, IMPORTED, SKIPPED }
    private data class Item(
        val number: Int,
        val parsed: ParsedScreenshot?,
        val rawText: String,
        var state: State,
        var duplicate: Boolean = false,
    )

    private lateinit var binding: ActivityBatchImportBinding
    private val repository by lazy { (application as LedgerApp).repository }
    private val items = mutableListOf<Item>()
    private var editingIndex = -1
    private var processing = false
    private val editResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val item = items.getOrNull(editingIndex)
        if (item != null && result.resultCode == RESULT_OK) {
            item.state = when {
                (result.data?.getLongExtra("savedId", 0L) ?: 0L) > 0L -> State.IMPORTED
                result.data?.getBooleanExtra("skipped", false) == true -> State.SKIPPED
                else -> item.state
            }
            render()
        }
        editingIndex = -1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.apply(binding.root)
        val uris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableArrayListExtra("uris", Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra("uris")
        if (uris.isNullOrEmpty()) { finish(); return }
        binding.progress.max = uris.size
        binding.importAll.setOnClickListener { importReady() }
        lifecycleScope.launch { recognizeSequentially(uris) }
    }

    private suspend fun recognizeSequentially(uris: List<Uri>) {
        val ocr = OcrManager(applicationContext)
        uris.forEachIndexed { index, uri ->
            binding.progressLabel.text = "正在识别 ${index + 1}/${uris.size} 张"
            val item = try {
                val raw = ocr.recognize(uri).take(2000)
                val parsed = raw.takeIf { it.isNotBlank() }?.let(ScreenshotParserRegistry::parse)
                val draft = parsed?.let { toDraft(it, raw) }
                val reliable = parsed?.amountCents?.let { it > 0 } == true && parsed.type != null && parsed.timestamp != null
                val duplicate = if (reliable && draft != null) repository.possibleDuplicate(draft) != null else false
                Item(index + 1, parsed, raw, if (reliable && !duplicate) State.READY else if (raw.isBlank()) State.FAILED else State.NEED_CONFIRM, duplicate)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Item(index + 1, null, "", State.FAILED)
            }
            items += item
            binding.progress.progress = index + 1
            render()
        }
        binding.progressLabel.text = "识别完成，共 ${uris.size} 张"
        binding.importAll.isEnabled = items.any { it.state == State.READY }
    }

    private fun toDraft(parsed: ParsedScreenshot, rawText: String) = LedgerTransaction(
        amountCents = parsed.amountCents ?: 0,
        type = parsed.type ?: com.example.lightledger.data.TransactionType.EXPENSE,
        merchant = parsed.merchant,
        paymentMethod = parsed.paymentMethod,
        platform = parsed.platform,
        timestamp = parsed.timestamp ?: System.currentTimeMillis(),
        source = TransactionSource.SCREENSHOT,
        ocrText = rawText,
        classificationStatus = ClassificationStatus.UNCLASSIFIED,
    )

    private fun importReady() {
        if (processing) return
        processing = true
        binding.importAll.isEnabled = false
        lifecycleScope.launch {
            var imported = 0
            try {
                items.filter { it.state == State.READY }.forEach { item ->
                    val draft = toDraft(item.parsed ?: return@forEach, item.rawText)
                    if (repository.possibleDuplicate(draft) != null) {
                        item.duplicate = true
                        item.state = State.NEED_CONFIRM
                    } else {
                        try {
                            repository.saveAutoClassified(draft)
                            item.state = State.IMPORTED
                            imported++
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { item.state = State.NEED_CONFIRM }
                    }
                    render()
                }
                Toast.makeText(this@BatchImportActivity, "已导入 $imported 笔，疑似重复或失败的项目请单笔确认", Toast.LENGTH_LONG).show()
            } finally {
                processing = false
                render()
            }
        }
    }

    private fun render() {
        val ready = items.count { it.state == State.READY }
        val confirm = items.count { it.state == State.NEED_CONFIRM }
        val failed = items.count { it.state == State.FAILED }
        val imported = items.count { it.state == State.IMPORTED }
        binding.summary.text = "成功识别：$ready　需要确认：$confirm　识别失败：$failed　已导入：$imported"
        binding.importAll.isEnabled = !processing && ready > 0 && binding.progress.progress == binding.progress.max
        binding.itemContainer.removeAllViews()
        items.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_list_row)
                setPadding(16, 16, 16, 16)
            }
            val parsed = item.parsed
            val amount = parsed?.amountCents?.let(UiFormat::money) ?: "金额待确认"
            val date = parsed?.timestamp?.let(UiFormat::date) ?: "时间待确认"
            val status = when (item.state) {
                State.READY -> "可导入"
                State.NEED_CONFIRM -> "需要确认"
                State.FAILED -> "识别失败"
                State.IMPORTED -> "已导入"
                State.SKIPPED -> "已跳过"
            }
            row.addView(TextView(this).apply {
                text = "第 ${item.number} 张　$amount　${parsed?.merchant ?: "商户未识别"}\n$date　分类：未分类　$status${if (item.duplicate) "　疑似重复" else ""}"
                textSize = 16f
                setTextColor(context.ledgerColor(R.color.ledger_ink))
            })
            if (item.state != State.IMPORTED && item.state != State.SKIPPED) {
                val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                buttons.addView(com.google.android.material.button.MaterialButton(this).apply {
                    text = "编辑/确认"
                    setOnClickListener { openEditor(index) }
                })
                buttons.addView(com.google.android.material.button.MaterialButton(this).apply {
                    text = "跳过"
                    setOnClickListener { item.state = State.SKIPPED; render() }
                })
                row.addView(buttons)
            }
            binding.itemContainer.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 })
        }
    }

    private fun openEditor(index: Int) {
        val item = items[index]
        val parsed = item.parsed
        editingIndex = index
        editResult.launch(Intent(this, TransactionEditorActivity::class.java).apply {
            action = TransactionEditorActivity.ACTION_SCREENSHOT_CONFIRM
            parsed?.amountCents?.let { putExtra("amountCents", it) }
            parsed?.merchant?.let { putExtra("merchant", it) }
            parsed?.type?.let { putExtra("type", it.name) }
            parsed?.paymentMethod?.let { putExtra("paymentMethod", it.name) }
            parsed?.platform?.let { putExtra("platform", it) }
            parsed?.timestamp?.let { putExtra("timestamp", it) }
            putExtra("rawOcrText", item.rawText)
        })
    }
}
