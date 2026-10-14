package com.example.lightledger.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.*
import com.example.lightledger.databinding.ActivityTransactionSearchBinding
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
import java.io.File
import java.time.Duration

@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = LedgerApp::class, sdk = [29, 35], qualifiers = "w360dp-h760dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Features091Test {
    private lateinit var db: LedgerDatabase
    @Before fun prepare() {
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        ViewModelProvider.AndroidViewModelFactory::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == ViewModelProvider.AndroidViewModelFactory::class.java }
            .forEach { it.isAccessible = true; it.set(null, null) }
        RuntimeEnvironment.setFontScale(1f)
        db = LedgerDatabase.get(RuntimeEnvironment.getApplication())
    }
    @After fun close() { db.close(); LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) } }

    @Test fun incomeFormSavesAndEditsHiddenDetailsAndSwitchesCategoryScope() {
        listOf(false, true).forEach { large ->
            if (large) { RuntimeEnvironment.setQualifiers("w320dp-h700dp-xxhdpi"); RuntimeEnvironment.setFontScale(1.3f) }
            val controller = Robolectric.buildActivity(TransactionEditorActivity::class.java).setup()
            try {
                val activity = controller.get(); await { activity.findViewById<View>(R.id.save).isEnabled }
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val group = activity.findViewById<ChipGroup>(R.id.categoryChips)
                chips(group).single { it.text == "正餐" }.performClick()
                activity.findViewById<View>(R.id.incomeOption).performClick(); measure(root)
                assertEquals(listOf("工资", "奖金", "生活费", "其他"), chips(group).map { it.text.toString() })
                assertEquals(1, chips(group).map { it.top }.distinct().size)
                assertEquals(1, chips(group).map { it.width }.distinct().size)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.expandCategories).visibility)
                assertFalse(activity.findViewById<View>(R.id.paymentMethodChips).isShown)
                assertTrue(activity.findViewById<View>(R.id.note).isShown)
                chips(group).single { it.text == "工资" }.performClick()
                activity.findViewById<View>(R.id.expenseOption).performClick(); measure(root)
                assertEquals("正餐", chips(group).single { it.isChecked }.text.toString())
                assertFalse(chips(group).any { it.text == "工资" })
                activity.findViewById<View>(R.id.incomeOption).performClick(); measure(root)
                assertEquals("工资", chips(group).single { it.isChecked }.text.toString())
                activity.findViewById<EditText>(R.id.amount).setText("8000.25")
                activity.findViewById<EditText>(R.id.note).setText("虚构收入备注")
                capture(root, if (large) "income-large-font" else "income")
                activity.findViewById<View>(R.id.moreDetails).performClick(); measure(root)
                assertTrue(activity.findViewById<View>(R.id.paymentMethodChips).isShown)
                assertFalse(activity.findViewById<View>(R.id.rememberMerchant).isShown)
                chips(activity.findViewById(R.id.paymentMethodChips)).single { it.text == "银行卡" }.performClick()
                activity.findViewById<EditText>(R.id.merchant).setText("虚构付款方")
                activity.findViewById<View>(R.id.moreDetails).performClick()
                activity.findViewById<View>(R.id.save).performClick(); await { activity.isFinishing }
                val bill = runBlocking { db.dao().allTransactionsOnce().maxBy { it.id } }
                assertEquals(TransactionType.INCOME, bill.type); assertEquals(800025L, bill.amountCents)
                assertEquals(PaymentMethod.BANK_CARD, bill.paymentMethod); assertEquals("虚构付款方", bill.merchant)
                assertEquals("虚构收入备注", bill.note)
                assertEquals("工资", runBlocking { db.dao().categoryList().single { it.id == bill.categoryId }.name })
                val edit = Robolectric.buildActivity(TransactionEditorActivity::class.java,
                    Intent(activity, TransactionEditorActivity::class.java).putExtra("id", bill.id)).setup()
                try {
                    val page = edit.get(); await { page.findViewById<View>(R.id.save).isEnabled }
                    assertEquals(View.GONE, page.findViewById<View>(R.id.additionalDetails).visibility)
                    page.findViewById<EditText>(R.id.note).setText("修改后的虚构备注")
                    page.findViewById<View>(R.id.save).performClick(); await { page.isFinishing }
                    val updated = runBlocking { db.dao().transaction(bill.id)!! }
                    assertEquals(bill.copy(note = updated.note, updatedAt = updated.updatedAt), updated)
                    assertEquals("修改后的虚构备注", updated.note)
                } finally { edit.pause().stop().destroy() }
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun legacyIncomeCategoryAndPaymentRemainUnchangedWhenOnlyNoteIsEdited() {
        val meal = runBlocking { db.dao().categoryList().single { it.name == "正餐" } }
        val original = LedgerTransaction(id = 88, type = TransactionType.INCOME, amountCents = 12345,
            categoryId = meal.id, paymentMethod = PaymentMethod.OTHER, timestamp = 1000, note = "原备注",
            classificationStatus = ClassificationStatus.CLASSIFIED)
        runBlocking { db.dao().insertTransaction(original) }
        val controller = Robolectric.buildActivity(TransactionEditorActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), TransactionEditorActivity::class.java).putExtra("id", 88L)).setup()
        try {
            val activity = controller.get(); await { activity.findViewById<View>(R.id.save).isEnabled }
            assertEquals("正餐", chips(activity.findViewById(R.id.categoryChips)).single { it.isChecked }.text.toString())
            activity.findViewById<EditText>(R.id.note).setText("新备注")
            activity.findViewById<View>(R.id.save).performClick(); await { activity.isFinishing }
            val result = runBlocking { db.dao().transaction(88)!! }
            assertEquals(original.copy(note = "新备注", updatedAt = result.updatedAt), result)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun searchUsesTwoLeadingTypesAndMatchingChoicesAndClearsWithoutThirdType() {
        val controller = Robolectric.buildActivity(SearchAndThemeTest.PreviewActivity::class.java).setup()
        try {
            val activity = controller.get(); val b = ActivityTransactionSearchBinding.inflate(activity.layoutInflater)
            activity.setContentView(b.root)
            var applied: TransactionFilter? = null
            val form = TransactionSearchForm(b, TransactionFilter(), runBlocking { db.dao().categoryList() }, emptyList(), { _, _ -> }, { applied = it })
            listOf(false, true).forEach { large ->
                if (large) { RuntimeEnvironment.setQualifiers("w320dp-h700dp-xxhdpi"); RuntimeEnvironment.setFontScale(1.3f) }
                b.resetFilters.performClick(); b.searchExpenseOption.performClick(); measure(b.root)
                assertEquals(2, b.typeChoices.childCount)
                assertTrue((b.typeChoices.parent.parent as View).top < b.keywordInput.top)
                assertEquals("EXPENSE", form.snapshot().type)
                assertFalse(chips(b.categoryChoices).any { it.text == "工资" })
                assertEquals(2, chips(b.categoryChoices).filter { it.visibility == View.VISIBLE }.map { it.top }.distinct().size)
                assertEquals(listOf("微信", "支付宝", "银行卡", "现金"), chips(b.methodChoices).map { it.text.toString() })
                assertEquals(1, chips(b.methodChoices).map { it.top }.distinct().size)
                idle(); measure(b.root)
                capture(b.root, if (large) "search-large-font" else "search")
                chips(b.methodChoices).forEach { chip ->
                    assertTrue(chip.right <= b.methodChoices.width)
                    val center = chip.totalPaddingLeft + (chip.layout.getLineLeft(0) + chip.layout.getLineRight(0)) / 2
                    assertEquals("${chip.text}: width=${chip.width}, target=${chip.layoutParams.width}, group=${b.methodChoices.width}, padding=${chip.totalPaddingLeft}/${chip.totalPaddingRight}", chip.width / 2f, center, 2 * b.root.resources.displayMetrics.density)
                }
                chips(b.categoryChoices).single { it.text == "正餐" }.performClick()
                chips(b.methodChoices).single { it.text == "支付宝" }.performClick()
                b.keyword.setText("虚构备注"); b.applyFilters.performClick()
                assertEquals(form.snapshot(), applied)
                b.searchIncomeOption.performClick(); measure(b.root)
                assertEquals(-1L, form.snapshot().categoryId)
                assertEquals(listOf("工资", "奖金", "生活费", "其他", "未分类"), chips(b.categoryChoices).map { it.text.toString() })
                assertTrue(chips(b.categoryChoices).all { it.visibility == View.VISIBLE })
                chips(b.categoryChoices).single { it.text == "奖金" }.performClick()
                b.clearCategory.performClick(); b.clearMethod.performClick()
                assertEquals(-1L, form.snapshot().categoryId); assertEquals("", form.snapshot().method)
                b.searchIncomeOption.performClick(); assertEquals("", form.snapshot().type)
                b.resetFilters.performClick(); assertEquals(TransactionFilter(), form.snapshot())
                b.searchExpenseOption.performClick(); measure(b.root)
                capture(b.root, if (large) "search-large-font" else "search")
            }
            val foldedOther = ActivityTransactionSearchBinding.inflate(activity.layoutInflater)
            TransactionSearchForm(foldedOther, TransactionFilter(type = "INCOME"),
                runBlocking { db.dao().categoryList() }.map { if (it.name == "其他") it.copy(priority = CategoryPriority.OCCASIONAL) else it },
                emptyList(), { _, _ -> }, {})
            assertTrue(chips(foldedOther.categoryChoices).single { it.text == "其他" }.visibility == View.VISIBLE)
            assertEquals(View.GONE, foldedOther.expandSearchCategories.visibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun billArrowReturnsHomeAndSearchArrowCancelsUnappliedDraft() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get(); activity.supportFragmentManager.executePendingTransactions()
            activity.openBills(); activity.supportFragmentManager.executePendingTransactions()
            assertTrue(activity.supportFragmentManager.findFragmentById(R.id.pageContainer) is TransactionsFragment)
            activity.findViewById<View>(R.id.billBack).performClick(); idle()
            assertTrue(activity.supportFragmentManager.findFragmentById(R.id.pageContainer) is HomeFragment)
            assertFalse(activity.isFinishing)
            val search = Robolectric.buildActivity(TransactionSearchActivity::class.java).setup()
            try {
                val page = search.get(); await { page.findViewById<View>(R.id.applyFilters).isEnabled }
                page.findViewById<EditText>(R.id.keyword).setText("尚未应用的条件")
                page.findViewById<View>(R.id.goBack).performClick()
                assertTrue(page.isFinishing)
                assertEquals(android.app.Activity.RESULT_CANCELED, shadowOf(page).resultCode)
            } finally { search.pause().stop().destroy() }
        } finally { controller.pause().stop().destroy() }
    }

    private fun chips(group: ChipGroup) = (0 until group.childCount).map { group.getChildAt(it) }.filterIsInstance<Chip>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    private fun await(ready: () -> Boolean) { val end = System.nanoTime() + 15_000_000_000; while (System.nanoTime() < end) { idle(); if (ready()) return; Thread.sleep(10) }; fail("0.9.1 screen update timed out") }
    private fun measure(root: View) {
        val m = root.resources.displayMetrics
        // The manual preview has no ViewRoot traversal; consume layout requests made by sizing listeners.
        fun invalidateMeasure(view: View) { view.forceLayout(); if (view is ViewGroup) (0 until view.childCount).forEach { invalidateMeasure(view.getChildAt(it)) } }
        repeat(3) { invalidateMeasure(root); root.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY)); root.layout(0, 0, root.measuredWidth, root.measuredHeight) }
    }
    private fun capture(root: View, name: String) { idle(); measure(root); val directory = System.getProperty("lightledger.preview.dir") ?: return; val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bitmap)); File(directory, "LightLedger-0.9.1-$name-api${RuntimeEnvironment.getApiLevel()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
}
