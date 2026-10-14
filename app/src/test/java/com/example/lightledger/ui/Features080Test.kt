package com.example.lightledger.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.backup.BackupCodec
import com.example.lightledger.data.*
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = LedgerApp::class, sdk = [29, 35], qualifiers = "w360dp-h760dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Features080Test {
    private lateinit var db: LedgerDatabase
    @Before fun prepare() {
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        ViewModelProvider.AndroidViewModelFactory::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == ViewModelProvider.AndroidViewModelFactory::class.java }
            .forEach { it.isAccessible = true; it.set(null, null) }
        RuntimeEnvironment.setFontScale(1f)
        db = LedgerDatabase.get(RuntimeEnvironment.getApplication())
    }
    @After fun close() {
        db.close()
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
    }

    @Test fun newEditorKeepsNoteOutsideMoreAndRowsCompactAndSavesHiddenMerchant() {
        runBlocking { listOf("虚构服装", "虚构医疗", "虚构小费").forEach { db.dao().insertCategory(Category(name = it, priority = CategoryPriority.OCCASIONAL)) } }
        listOf(false, true).forEach { large ->
            if (large) { RuntimeEnvironment.setQualifiers("w320dp-h700dp-xxhdpi"); RuntimeEnvironment.setFontScale(1.3f) }
            val controller = Robolectric.buildActivity(TransactionEditorActivity::class.java).setup()
            try {
                val activity = controller.get()
                await { activity.findViewById<View>(R.id.save).isEnabled }
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                measure(root)
                val more = activity.findViewById<View>(R.id.additionalDetails)
                val note = activity.findViewById<EditText>(R.id.note)
                val merchant = activity.findViewById<EditText>(R.id.merchant)
                assertEquals(View.GONE, more.visibility)
                assertTrue(note.isShown); assertFalse(merchant.isShown)
                val categories = activity.findViewById<ChipGroup>(R.id.categoryChips)
                val categoryChips = chips(categories).filter { it.visibility == View.VISIBLE }
                assertEquals(8, categoryChips.size)
                assertEquals("categories in two columns", 4, categoryChips.map { it.top }.distinct().size)
                val occasional = activity.findViewById<ChipGroup>(R.id.occasionalCategoryChips)
                assertEquals(3, chips(occasional).size)
                assertTrue(chips(occasional).all { it.isShown && it.right <= occasional.width })
                val payments = activity.findViewById<ChipGroup>(R.id.paymentMethodChips)
                assertEquals(listOf("微信", "支付宝", "银行卡", "现金"), chips(payments).map { it.text.toString() })
                assertEquals("payments in one row", 1, chips(payments).map { it.top }.distinct().size)
                chips(payments).forEach {
                    assertTrue(it.right <= payments.width)
                    assertTrue("payment text fits: ${it.text}, width=${it.width}, text=${it.paint.measureText(it.text.toString())}, size=${it.textSize}", it.paint.measureText(it.text.toString()) + it.chipStartPadding + it.chipEndPadding <= it.width + 1)
                }
                assertFalse(texts(root).any { it.text.toString().contains("记录每一笔") })
                note.setText("虚构午餐备注")
                activity.findViewById<EditText>(R.id.amount).setText("12.50")
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
                measure(root)
                val prefix = activity.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.amountInput).prefixTextView
                val amountView = activity.findViewById<EditText>(R.id.amount)
                assertEquals(amountView.textSize, prefix.textSize, 1f)
                assertEquals(android.graphics.Typeface.BOLD, prefix.typeface.style)
                assertTrue(note.height <= (if (large) 86 else 72) * root.resources.displayMetrics.density)
                chips(payments).forEach { chip ->
                    val textCenter = chip.totalPaddingLeft + (chip.layout.getLineLeft(0) + chip.layout.getLineRight(0)) / 2
                    assertEquals("centered payment ${chip.text}", chip.width / 2f, textCenter, 2 * root.resources.displayMetrics.density)
                }
                measure(root); save(root, if (large) "editor-compact-large-font" else "editor-compact")
                if (large) {
                    activity.findViewById<android.widget.ScrollView>(R.id.editorScroll).fullScroll(View.FOCUS_DOWN)
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
                    measure(root); save(root, "editor-payment-large-font")
                }
                activity.findViewById<View>(R.id.moreDetails).performClick()
                assertTrue(merchant.isShown)
                merchant.setText("虚构商户")
                activity.findViewById<CheckBox>(R.id.rememberMerchant).isChecked = true
                activity.findViewById<View>(R.id.moreDetails).performClick()
                assertFalse(merchant.isShown); assertTrue(note.isShown)
                activity.findViewById<View>(R.id.save).performClick()
                await { activity.isFinishing }
                val bill = runBlocking { db.dao().allTransactionsOnce().maxBy { it.id } }
                assertEquals("虚构商户", bill.merchant); assertEquals("虚构午餐备注", bill.note)
                assertEquals(1250L, bill.amountCents)
                assertEquals(PaymentMethod.WECHAT, bill.paymentMethod)
                assertEquals(bill.categoryId, runBlocking { db.dao().merchantRules().single().categoryId })
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun editPreservesLegacyOtherPaymentAndNote() {
        runBlocking { db.dao().insertTransaction(LedgerTransaction(id = 77, amountCents = 1300,
            type = TransactionType.EXPENSE, paymentMethod = PaymentMethod.OTHER,
            merchant = "虚构原商户", note = "虚构原备注", timestamp = 1000)) }
        val intent = Intent(RuntimeEnvironment.getApplication(), TransactionEditorActivity::class.java).putExtra("id", 77L)
        val controller = Robolectric.buildActivity(TransactionEditorActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            await { activity.findViewById<View>(R.id.save).isEnabled }
            assertTrue(chips(activity.findViewById(R.id.paymentMethodChips)).none { it.isChecked || it.text.toString() == "其他" })
            assertEquals("虚构原备注", activity.findViewById<EditText>(R.id.note).text.toString())
            activity.findViewById<View>(R.id.save).performClick(); await { activity.isFinishing }
            val saved = runBlocking { db.dao().transaction(77)!! }
            assertEquals(PaymentMethod.OTHER, saved.paymentMethod)
            assertEquals("虚构原备注", saved.note); assertEquals("虚构原商户", saved.merchant)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun categoryEditAndDefaultCategoryDeleteCancelConfirmAndEmptyBackupRestore() {
        val repository = (RuntimeEnvironment.getApplication() as LedgerApp).repository
        val initial = runBlocking { repository.categoryList() }
        val meal = initial.single { it.name == "正餐" }
        runBlocking {
            db.dao().insertTransactions(listOf(
                LedgerTransaction(id = 1, amountCents = 2500, type = TransactionType.EXPENSE, categoryId = meal.id, timestamp = 1000, note = "虚构支出备注", classificationStatus = ClassificationStatus.CLASSIFIED),
                LedgerTransaction(id = 2, amountCents = 500, type = TransactionType.REFUND, categoryId = meal.id, timestamp = 2000, refundOfTransactionId = 1, note = "虚构退款备注", classificationStatus = ClassificationStatus.CLASSIFIED)))
            db.dao().insertTag(Tag(1, "虚构标签")); db.dao().insertTransactionTags(listOf(TransactionTagCrossRef(1, 1)))
            repository.addMerchantRule("虚构规则", meal.id, true)
        }
        val controller = Robolectric.buildActivity(CategoryActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.findViewById<ViewGroup>(R.id.categoryContainer)
            fun row(name: String) = texts(root).single { it.text.toString() == name }.parent.parent.parent as View
            await { texts(root).any { it.text.toString() == "日用" } }
            assertFalse(row("日用").isLongClickable)
            row("日用").performClick()
            var editDialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals("日用", editDialog.findViewById<EditText>(R.id.categoryName)!!.text.toString())
            editDialog.findViewById<View>(R.id.occasionalPriority)!!.performClick()
            assertEquals(R.id.occasionalPriority, editDialog.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.priorityChoices)!!.checkedButtonId)
            val editSave = editDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            assertTrue(editSave.isEnabled)
            assertTrue(editSave.performClick())
            await { runBlocking { repository.categoryList().single { it.name == "日用" }.priority == CategoryPriority.OCCASIONAL } ||
                editDialog.findViewById<View>(R.id.categoryError)!!.visibility == View.VISIBLE }
            assertEquals(editDialog.findViewById<TextView>(R.id.categoryError)!!.text.toString(),
                CategoryPriority.OCCASIONAL, runBlocking { repository.categoryList().single { it.name == "日用" }.priority })
            assertTrue(texts(root).any { it.text.toString() == "日用" })
            fun requestDelete(): AlertDialog {
                row("正餐").findViewById<View>(R.id.editIcon).performClick()
                val list = findList(shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow.contentView)!!
                val items = (0 until list.adapter.count).map { list.adapter.getItem(it) as MenuItem }
                assertEquals(listOf("上移", "下移", "删除分类"), items.map { it.title.toString() })
                val index = items.indexOfFirst { it.title == "删除分类" }
                list.performItemClick(list.adapter.getView(index, null, list), index, index.toLong())
                await { (ShadowDialog.getLatestDialog() as? AlertDialog)?.let { it.isShowing && it.getButton(AlertDialog.BUTTON_POSITIVE)?.text == "确认删除" } == true }
                return ShadowDialog.getLatestDialog() as AlertDialog
            }
            val before = runBlocking { db.dao().snapshot() }
            var dialog = requestDelete()
            assertTrue(dialog.findViewById<TextView>(android.R.id.message)!!.text.toString().contains("2 笔"))
            assertTrue(dialog.findViewById<TextView>(android.R.id.message)!!.text.toString().contains("1 条"))
            assertEquals(before, runBlocking { db.dao().snapshot() })
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
            assertEquals(before, runBlocking { db.dao().snapshot() })
            dialog = requestDelete(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await { runBlocking { repository.categoryList().none { it.id == meal.id } } }
            val after = runBlocking { db.dao().snapshot() }
            assertEquals(before.transactions.map { it.amountCents }, after.transactions.map { it.amountCents })
            assertEquals(before.transactions.map { it.note }, after.transactions.map { it.note })
            assertTrue(after.transactions.all { it.categoryId == null && it.classificationStatus == ClassificationStatus.UNCLASSIFIED })
            assertEquals(1L, after.transactions.single { it.id == 2L }.refundOfTransactionId)
            assertEquals(before.transactionTags, after.transactionTags); assertTrue(after.merchantRules.isEmpty())
            runBlocking { repository.categoryList().forEach { repository.deleteCategory(it) } }
            val empty = runBlocking { db.dao().snapshot() }
            assertTrue(empty.categories.isEmpty())
            val restored = BackupCodec.decode(BackupCodec.encode(empty))
            runBlocking { db.dao().insertCategory(Category(name = "临时分类")); repository.restore(restored) }
            assertEquals(empty, runBlocking { db.dao().snapshot() })
        } finally { controller.pause().stop().destroy() }
        val editor = Robolectric.buildActivity(TransactionEditorActivity::class.java).setup()
        try {
            val activity = editor.get(); await { activity.findViewById<View>(R.id.save).isEnabled }
            activity.findViewById<EditText>(R.id.amount).setText("1.00")
            activity.findViewById<View>(R.id.save).performClick(); await { activity.isFinishing }
            val saved = runBlocking { db.dao().allTransactionsOnce().maxBy { it.id } }
            assertEquals(100L, saved.amountCents)
            assertNull(saved.categoryId)
        } finally { editor.pause().stop().destroy() }
    }

    private fun chips(group: ChipGroup) = (0 until group.childCount).map { group.getChildAt(it) }.filterIsInstance<Chip>()
    private fun texts(view: View): List<TextView> = (if (view is TextView) listOf(view) else emptyList()) +
        (if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())
    private fun findList(view: View): ListView? {
        if (view is ListView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findList(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
    private fun await(ready: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < end) { idle(); if (ready()) return; Thread.sleep(10) }
        fail("0.8 screen update timed out")
    }
    private fun measure(root: View) {
        val m = root.resources.displayMetrics
        repeat(3) { root.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY)); root.layout(0, 0, root.measuredWidth, root.measuredHeight) }
    }
    private fun save(root: View, name: String) {
        val dir = System.getProperty("lightledger.preview.dir") ?: return
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bitmap))
        val suffix = if (RuntimeEnvironment.getApiLevel() == 29) "" else "-api${RuntimeEnvironment.getApiLevel()}"
        File(dir, "LightLedger-0.9.1-$name$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
