package com.example.lightledger.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.doOnLayout
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.*
import com.example.lightledger.databinding.ActivityTransactionEditorBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar

class TransactionEditorActivity : LedgerActivity() {
    companion object { const val ACTION_SCREENSHOT_CONFIRM = "com.example.lightledger.CONFIRM_SCREENSHOT" }
    private lateinit var binding: ActivityTransactionEditorBinding
    private val repository by lazy { (application as LedgerApp).repository }
    private var categories: List<Category> = emptyList()
    private var showOccasional = false
    private var existing: LedgerTransaction? = null
    private var tags: List<Tag> = emptyList()
    private val selectedTagIds = mutableSetOf<Long>()
    private var refundOfId: Long? = null
    private var selectedType = TransactionType.EXPENSE
    private var specialType = TransactionType.REFUND
    private var selectedCategoryId: Long? = null
    private val typeCategories = mutableMapOf<TransactionType, Long?>()
    private var selectedPaymentMethod = PaymentMethod.WECHAT
    private var paymentChosenManually = false
    private var applyingPaymentSelection = false
    private val categoryChips = mutableMapOf<Long, Chip>()
    private val paymentChips = mutableMapOf<PaymentMethod, Chip>()
    private val selectedTime = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransactionEditorBinding.inflate(layoutInflater)
        val id = intent.getLongExtra("id", 0)
        if (id != 0L) {
            // The record is read asynchronously. Never render the new-bill form for an edit.
            binding.pageTitle.text = "编辑账单"
            binding.editorScroll.visibility = View.INVISIBLE
            binding.save.visibility = View.INVISIBLE
        } else if (intent.action == ACTION_SCREENSHOT_CONFIRM) {
            binding.pageTitle.text = "确认截图账单"
        }
        setContentView(binding.root)
        SystemBarInsets.apply(binding.root)
        EditorChoiceRows.bindCategories(binding.categoryChips, binding.categoryScroll)
        EditorChoiceRows.bindPayments(binding.paymentMethodChips)
        binding.expandCategories.setOnClickListener { showOccasional = !showOccasional; updateCategoryVisibility() }
        binding.goBack.setOnClickListener { finish() }
        binding.moreDetails.setOnClickListener { showMoreDetails(binding.additionalDetails.visibility != View.VISIBLE) }
        binding.amount.doAfterTextChanged { binding.amountInput.error = null }
        binding.typeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            typeCategories[selectedType] = selectedCategoryId
            selectedType = when (checkedId) {
                R.id.incomeOption -> TransactionType.INCOME
                R.id.specialTypeOption -> specialType
                else -> TransactionType.EXPENSE
            }
            binding.refundLink.visibility = if (selectedType == TransactionType.REFUND) View.VISIBLE else View.GONE
            binding.rememberMerchant.visibility = if (selectedType == TransactionType.EXPENSE) View.VISIBLE else View.GONE
            binding.amountLabel.text = "${UiFormat.type(selectedType)}金额（元）"
            val paymentHost = if (selectedType == TransactionType.INCOME) binding.additionalDetails else binding.primaryPayment
            if (binding.paymentChoices.parent != paymentHost) {
                (binding.paymentChoices.parent as android.view.ViewGroup).removeView(binding.paymentChoices)
                paymentHost.addView(binding.paymentChoices, 0)
            }
            selectedCategoryId = if (typeCategories.containsKey(selectedType)) typeCategories[selectedType]
                else categories.firstOrNull { it.name == "其他" }?.id
            showOccasional = false
            if (selectedType == TransactionType.INCOME) showMoreDetails(false)
            showCategoryChoices()
        }
        binding.typeToggle.check(R.id.expenseOption)
        showPaymentChoices()
        binding.timestamp.setOnClickListener { chooseDate() }
        binding.tags.setOnClickListener { chooseTags() }
        binding.refundLink.setOnClickListener { chooseRefund() }
        binding.save.setOnClickListener { save() }
        binding.delete.setOnClickListener { confirmDelete() }
        updateTimeLabel()
        lifecycleScope.launch {
            categories = repository.categoryList()
            tags = repository.tags()
            showCategoryChoices()
            if (id != 0L) {
                existing = repository.transaction(id)
                if (existing == null) { finish(); return@launch }
                showExisting(existing!!)
                selectedTagIds.addAll(repository.tagIdsForTransaction(id))
                refundOfId = existing!!.refundOfTransactionId
                updateTagLabel()
                updateRefundLabel()
                binding.editorScroll.visibility = View.VISIBLE
                binding.save.visibility = View.VISIBLE
            } else if (intent.action == ACTION_SCREENSHOT_CONFIRM) {
                showScreenshotDraft()
                if (selectedType == TransactionType.EXPENSE) repository.suggestedCategory(binding.merchant.text?.toString())?.let(::selectCategory)
            } else {
                selectCategory(categories.firstOrNull { it.name == "其他" }?.id)
                val detected = com.example.lightledger.notification.ListenerRuntime.latestPayment(this@TransactionEditorActivity)
                if (!paymentChosenManually && detected != null) selectPaymentMethod(detected)
            }
            binding.save.isEnabled = true
        }
    }

    private fun showScreenshotDraft() {
        showMoreDetails(true)
        binding.pageTitle.text = "确认截图账单"
        val cents = intent.getLongExtra("amountCents", 0)
        if (cents > 0) binding.amount.setText(BigDecimal.valueOf(cents, 2).toPlainString())
        val type = intent.getStringExtra("type")?.let { name -> TransactionType.entries.firstOrNull { it.name == name } }
        if (type != null) setType(type)
        val method = intent.getStringExtra("paymentMethod")?.let { name -> PaymentMethod.entries.firstOrNull { it.name == name } }
        if (method != null) selectPaymentMethod(method)
        binding.merchant.setText(intent.getStringExtra("merchant").orEmpty())
        if (intent.hasExtra("timestamp")) selectedTime.timeInMillis = intent.getLongExtra("timestamp", selectedTime.timeInMillis)
        updateTimeLabel()
        val warnings = listOfNotNull(
            if (cents == 0L) "金额未能可靠识别" else null,
            if (type == null) "收支类型未识别" else null,
            if (!intent.hasExtra("timestamp")) "时间未识别，已暂用当前时间" else null,
        )
        binding.ocrWarning.text = if (warnings.isEmpty()) "请核对识别结果后保存" else "请确认：${warnings.joinToString("、")}"
        binding.ocrWarning.visibility = View.VISIBLE
        binding.ocrRawText.text = intent.getStringExtra("rawOcrText").orEmpty().ifBlank { "未识别到文字" }
        binding.ocrRawText.visibility = View.VISIBLE
    }

    private fun showExisting(t: LedgerTransaction) {
        showMoreDetails(false)
        val pending = t.requiresAmountConfirmation()
        binding.pageTitle.text = if (pending) "确认金额" else "编辑账单"
        binding.save.text = if (pending) "确认并保存" else "保存"
        if (pending) {
            binding.ocrWarning.text = if (t.amountCents > 0) "请核对金额，正确可直接确认，也可修改后确认" else "请填写正确金额后确认"
            binding.ocrWarning.visibility = View.VISIBLE
        }
        binding.amount.setText(if (t.amountCents == 0L && t.classificationStatus == ClassificationStatus.NEED_CONFIRM) "" else BigDecimal.valueOf(t.amountCents, 2).toPlainString())
        setType(t.type)
        selectPaymentMethod(t.paymentMethod)
        selectCategory(t.categoryId)
        binding.merchant.setText(t.merchant.orEmpty())
        binding.note.setText(t.note.orEmpty())
        selectedTime.timeInMillis = t.timestamp
        updateTimeLabel()
        binding.delete.visibility = View.VISIBLE
    }

    private fun updateTagLabel() {
        val names = tags.filter { it.id in selectedTagIds }.map { it.name }
        binding.tags.text = if (names.isEmpty()) "标签：未选择" else "标签：${names.joinToString("、")}"
    }

    private fun showMoreDetails(show: Boolean) {
        binding.additionalDetails.visibility = if (show) View.VISIBLE else View.GONE
        binding.moreDetails.text = if (show) "收起更多 ▴" else "更多 ▾"
    }

    private fun setType(type: TransactionType) {
        if (type == TransactionType.REFUND || type == TransactionType.TRANSFER) {
            specialType = type
            binding.specialTypeOption.text = UiFormat.type(type)
            binding.specialTypeOption.visibility = View.VISIBLE
            binding.typeToggle.check(R.id.specialTypeOption)
        } else {
            binding.typeToggle.check(if (type == TransactionType.INCOME) R.id.incomeOption else R.id.expenseOption)
        }
    }

    private fun showCategoryChoices() {
        val selection = selectedCategoryId
        binding.categoryChips.removeAllViews()
        categoryChips.clear()
        CategoryCatalog.choices(categories, selectedType).forEach(::addCategoryChip)
        updateCategoryVisibility()
        selectCategory(selection)
    }

    private fun updateCategoryVisibility() {
        val occasional = if (selectedType == TransactionType.INCOME) emptyList() else
            CategoryCatalog.choices(categories, selectedType).filter { it.priority == CategoryPriority.OCCASIONAL }
        occasional.forEach { category -> categoryChips[category.id]?.visibility = if (showOccasional) View.VISIBLE else View.GONE }
        binding.expandCategories.visibility = if (occasional.isEmpty()) View.GONE else View.VISIBLE
        binding.expandCategories.text = if (showOccasional) "收起不常用分类 ▴" else "展开不常用分类（${occasional.size}）▾"
        EditorChoiceRows.fitCategories(binding.categoryChips, binding.categoryScroll)
    }

    private fun addCategoryChip(category: Category) {
        val chip = choiceChip(category.name).apply {
            chipIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@TransactionEditorActivity, CategoryIcons.icon(category.name, category.iconKey))
            setOnCheckedChangeListener { _, checked ->
                if (checked) selectedCategoryId = category.id
                else if (selectedCategoryId == category.id) selectedCategoryId = null
            }
        }
        categoryChips[category.id] = chip
        binding.categoryChips.addView(chip)
    }

    private fun showPaymentChoices() {
        PaymentMethod.entries.filter { it != PaymentMethod.OTHER }.forEach { method ->
            val chip = choiceChip(UiFormat.method(method)).apply {
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        selectedPaymentMethod = method
                        if (!applyingPaymentSelection) paymentChosenManually = true
                    }
                }
            }
            paymentChips[method] = chip
            binding.paymentMethodChips.addView(chip)
        }
        selectPaymentMethod(com.example.lightledger.notification.DetectedPaymentMethod.latest(this) ?: PaymentMethod.WECHAT)
        EditorChoiceRows.fitPayments(binding.paymentMethodChips)
    }

    private fun selectPaymentMethod(method: PaymentMethod) {
        selectedPaymentMethod = method
        applyingPaymentSelection = true
        try {
            val chip = paymentChips[method]
            if (chip != null) chip.isChecked = true else binding.paymentMethodChips.clearCheck()
        } finally { applyingPaymentSelection = false }
    }

    private fun choiceChip(label: String): Chip {
        val density = resources.displayMetrics.density
        return Chip(this).apply {
            id = View.generateViewId()
            text = label
            textSize = 14f
            isCheckable = true
            isCheckedIconVisible = false
            chipBackgroundColor = this@TransactionEditorActivity.getColorStateList(R.color.ledger_chip_background)
            setTextColor(this@TransactionEditorActivity.getColorStateList(R.color.ledger_chip_text))
            chipStrokeColor = ColorStateList.valueOf(this@TransactionEditorActivity.ledgerColor(R.color.ledger_border))
            chipStrokeWidth = density
            chipIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@TransactionEditorActivity, CategoryIcons.icon(label))
            chipIconTint = ColorStateList.valueOf(this@TransactionEditorActivity.ledgerColor(R.color.ledger_teal))
            chipIconSize = 18 * density
            setEnsureMinTouchTargetSize(true)
            minHeight = (48 * density).toInt()
        }
    }

    private fun selectCategory(categoryId: Long?) {
        if (categoryId == null) {
            binding.categoryChips.clearCheck()
            selectedCategoryId = null
            return
        }
        if (categoryId !in categoryChips) categories.firstOrNull { it.id == categoryId }?.let(::addCategoryChip)
        if (selectedType != TransactionType.INCOME && categories.any { it.id == categoryId && it.priority == CategoryPriority.OCCASIONAL }) { showOccasional = true; updateCategoryVisibility() }
        EditorChoiceRows.fitCategories(binding.categoryChips, binding.categoryScroll)
        categoryChips[categoryId]?.let { chip ->
            chip.isChecked = true
            binding.categoryChips.doOnLayout { binding.categoryScroll.scrollTo((chip.right - binding.categoryScroll.width).coerceAtLeast(0), 0) }
        }
    }

    private fun chooseTags() {
        if (tags.isEmpty()) {
            Toast.makeText(this, "请先在“我的”页面创建标签", Toast.LENGTH_SHORT).show()
            return
        }
        val draftIds = selectedTagIds.toMutableSet()
        val checked = tags.map { it.id in draftIds }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("选择标签")
            .setMultiChoiceItems(tags.map { it.name }.toTypedArray(), checked) { _, which, selected ->
                if (selected) draftIds += tags[which].id else draftIds -= tags[which].id
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("完成") { _, _ -> selectedTagIds.clear(); selectedTagIds.addAll(draftIds); updateTagLabel() }
            .show()
    }

    private fun updateRefundLabel() {
        binding.refundLink.text = if (refundOfId == null) "关联原支出（可选）" else "已关联支出 #$refundOfId（点击修改）"
    }

    private fun chooseRefund() {
        val cents = runCatching { BigDecimal(binding.amount.text.toString().trim()).movePointRight(2).longValueExact() }.getOrNull()
        if (cents == null || cents <= 0) {
            binding.amountInput.error = "请先填写退款金额"
            return
        }
        val draft = LedgerTransaction(amountCents = cents, type = TransactionType.REFUND,
            timestamp = selectedTime.timeInMillis,
            merchant = binding.merchant.text.toString().trim().ifEmpty { null },
            paymentMethod = selectedPaymentMethod)
        lifecycleScope.launch {
            val candidates = repository.refundCandidates(draft)
            val labels = listOf("不关联，保留独立退款") + candidates.map {
                "${it.merchant ?: "商户未知"} ${UiFormat.money(it.amountCents)} ${UiFormat.date(it.timestamp)}"
            }
            AlertDialog.Builder(this@TransactionEditorActivity).setTitle("选择原支出")
                .setItems(labels.toTypedArray()) { _, which ->
                    refundOfId = if (which == 0) null else candidates[which - 1].id
                    updateRefundLabel()
                }.show()
        }
    }

    private fun chooseDate() {
        DatePickerDialog(this, { _, year, month, day ->
            selectedTime.set(year, month, day)
            TimePickerDialog(this, { _, hour, minute ->
                selectedTime.set(Calendar.HOUR_OF_DAY, hour)
                selectedTime.set(Calendar.MINUTE, minute)
                selectedTime.set(Calendar.SECOND, 0)
                updateTimeLabel()
            }, selectedTime.get(Calendar.HOUR_OF_DAY), selectedTime.get(Calendar.MINUTE), true).show()
        }, selectedTime.get(Calendar.YEAR), selectedTime.get(Calendar.MONTH), selectedTime.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun updateTimeLabel() { binding.timestamp.text = UiFormat.date(selectedTime.timeInMillis) }

    private fun save() {
        val amount = try {
            BigDecimal(binding.amount.text.toString().trim()).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        } catch (_: Exception) { 0L }
        if (amount <= 0) { binding.amountInput.error = "请输入大于 0 的金额，最多两位小数"; return }
        val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId }
        val type = selectedType
        val method = selectedPaymentMethod
        val old = existing
        val isScreenshot = intent.action == ACTION_SCREENSHOT_CONFIRM
        val transaction = (old ?: LedgerTransaction(amountCents = amount, type = type, timestamp = selectedTime.timeInMillis,
            source = if (isScreenshot) TransactionSource.SCREENSHOT else TransactionSource.MANUAL)).copy(
            amountCents = amount,
            amountConfirmed = true,
            type = type,
            merchant = binding.merchant.text.toString().trim().ifEmpty { null },
            categoryId = selectedCategory?.id,
            paymentMethod = method,
            timestamp = selectedTime.timeInMillis,
            note = binding.note.text.toString().trim().ifEmpty { null },
            platform = if (isScreenshot && old == null) intent.getStringExtra("platform") else old?.platform,
            ocrText = if (isScreenshot && old == null) intent.getStringExtra("rawOcrText") else old?.ocrText,
            refundOfTransactionId = if (type == TransactionType.REFUND) refundOfId else null,
            classificationStatus = when {
                type == TransactionType.REFUND && refundOfId == null -> ClassificationStatus.NEED_CONFIRM
                selectedCategory == null -> ClassificationStatus.UNCLASSIFIED
                else -> ClassificationStatus.CLASSIFIED
            },
        )
        binding.save.isEnabled = false
        lifecycleScope.launch {
            try {
                val duplicate = if (old == null && isScreenshot) repository.possibleDuplicate(transaction) else null
                if (duplicate != null) {
                    binding.save.isEnabled = true
                    showDuplicateChoice(transaction, duplicate)
                } else {
                    persist(transaction)
                }
            }
            catch (e: Exception) { binding.save.isEnabled = true; Toast.makeText(this@TransactionEditorActivity, "保存失败：${e.localizedMessage}", Toast.LENGTH_LONG).show() }
        }
    }

    private suspend fun persist(transaction: LedgerTransaction) {
        val id = repository.save(transaction)
        repository.replaceTransactionTags(id, selectedTagIds.toList())
        if (transaction.type == TransactionType.EXPENSE && binding.rememberMerchant.isChecked && transaction.categoryId != null && !transaction.merchant.isNullOrBlank()) {
            repository.addMerchantRule(transaction.merchant, transaction.categoryId, true)
        }
        setResult(RESULT_OK, intentWithResult(id))
        finish()
    }

    private fun intentWithResult(id: Long) = android.content.Intent().putExtra("savedId", id)

    private fun showDuplicateChoice(screenshot: LedgerTransaction, existingTransaction: LedgerTransaction) {
        AlertDialog.Builder(this)
            .setTitle("发现疑似重复账单")
            .setMessage("已有 ${existingTransaction.merchant ?: UiFormat.method(existingTransaction.paymentMethod)} ${UiFormat.money(existingTransaction.amountCents)}，时间 ${UiFormat.date(existingTransaction.timestamp)}。请选择处理方式。")
            .setPositiveButton("合并") { _, _ -> lifecycleScope.launch {
                try {
                    repository.mergeScreenshot(existingTransaction, screenshot)
                    if (selectedTagIds.isNotEmpty()) {
                        val current = repository.tagIdsForTransaction(existingTransaction.id)
                        repository.replaceTransactionTags(existingTransaction.id, (current + selectedTagIds).distinct())
                    }
                    setResult(RESULT_OK, intentWithResult(existingTransaction.id))
                    finish()
                } catch (e: Exception) { Toast.makeText(this@TransactionEditorActivity, "合并失败：${e.localizedMessage}", Toast.LENGTH_LONG).show() }
            } }
            .setNeutralButton("跳过") { _, _ ->
                setResult(RESULT_OK, android.content.Intent().putExtra("skipped", true))
                finish()
            }
            .setNegativeButton("仍然导入") { _, _ -> lifecycleScope.launch {
                try { persist(screenshot) }
                catch (e: Exception) { Toast.makeText(this@TransactionEditorActivity, "保存失败：${e.localizedMessage}", Toast.LENGTH_LONG).show() }
            } }
            .show()
    }

    private fun confirmDelete() {
        val transaction = existing ?: return
        AlertDialog.Builder(this).setTitle("删除这笔账单？")
            .setMessage("删除后无法撤销")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try { repository.delete(transaction); finish() }
                    catch (e: Exception) { Toast.makeText(this@TransactionEditorActivity, "删除失败：${e.localizedMessage}", Toast.LENGTH_LONG).show() }
                }
            }.show()
    }
}
