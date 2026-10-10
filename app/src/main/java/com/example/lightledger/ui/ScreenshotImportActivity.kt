package com.example.lightledger.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.lightledger.databinding.ActivityScreenshotImportBinding
import com.example.lightledger.ocr.OcrManager
import com.example.lightledger.ocr.ParsedScreenshot
import com.example.lightledger.ocr.ScreenshotParserRegistry
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class ScreenshotImportActivity : LedgerActivity() {
    private lateinit var binding: ActivityScreenshotImportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenshotImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.apply(binding.root)
        binding.cancel.setOnClickListener { finish() }
        binding.manualEntry.setOnClickListener { openConfirmation(null, "") }
        val uri = intent.data ?: run { finish(); return }
        lifecycleScope.launch {
            try {
                val text = OcrManager(applicationContext).recognize(uri)
                if (text.isBlank()) {
                    showFailure("没有识别到文字，可以手动填写。")
                } else {
                    openConfirmation(ScreenshotParserRegistry.parse(text), text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showFailure("识别失败：${e.localizedMessage ?: "图片无法处理"}")
            }
        }
    }

    private fun showFailure(message: String) {
        binding.progress.visibility = View.GONE
        binding.status.text = message
        binding.manualEntry.visibility = View.VISIBLE
    }

    private fun openConfirmation(parsed: ParsedScreenshot?, rawText: String) {
        startActivity(Intent(this, TransactionEditorActivity::class.java).apply {
            action = TransactionEditorActivity.ACTION_SCREENSHOT_CONFIRM
            parsed?.amountCents?.let { putExtra("amountCents", it) }
            parsed?.merchant?.let { putExtra("merchant", it) }
            parsed?.type?.let { putExtra("type", it.name) }
            parsed?.paymentMethod?.let { putExtra("paymentMethod", it.name) }
            parsed?.platform?.let { putExtra("platform", it) }
            parsed?.timestamp?.let { putExtra("timestamp", it) }
            putExtra("rawOcrText", rawText.take(2000))
        })
        finish()
    }
}
