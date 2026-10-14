package com.example.lightledger.ui

import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
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
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = LedgerApp::class, sdk = [29], qualifiers = "w360dp-h760dp-xxhdpi")
class Features060Test {
    class PreviewActivity : LedgerActivity() {
        // The factory singleton otherwise keeps a preceding test's Application
        // and its closed Room scope when Robolectric recreates the app.
        override val defaultViewModelProviderFactory: ViewModelProvider.Factory
            get() = ViewModelProvider.AndroidViewModelFactory(application)
    }
    private lateinit var db: LedgerDatabase
    @Before fun setupDatabase() {
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        // Fragment-owned AndroidViewModels also use this singleton. Clear its old
        // Application when Robolectric starts a new test with a fresh Room instance.
        ViewModelProvider.AndroidViewModelFactory::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == ViewModelProvider.AndroidViewModelFactory::class.java }
            .forEach { it.isAccessible = true; it.set(null, null) }
        db = LedgerDatabase.get(RuntimeEnvironment.getApplication())
    }
    @After fun closeDatabase() {
        db.close()
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
    }

    @Test fun searchResultResetClearsCriteriaReturnsToMonthAndSurvivesViewRestoration() {
        val month = MonthSelection.at()
        runBlocking { db.dao().insertTransactions(listOf(
            LedgerTransaction(id = 1, amountCents = 100, type = TransactionType.EXPENSE,
                merchant = "示例关键字", timestamp = month.bounds().first + 1),
            LedgerTransaction(id = 2, amountCents = 200, type = TransactionType.EXPENSE,
                merchant = "示例关键字", timestamp = month.bounds().first - 1))) }
        val controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        try {
            val activity = controller.get()
            val frame = FrameLayout(activity).apply { id = R.id.pageContainer }
            activity.setContentView(frame)
            var fragment = TransactionsFragment()
            activity.supportFragmentManager.beginTransaction().add(frame.id, fragment).commitNow()
            fun ids() = (frame.findViewById<RecyclerView>(R.id.transactionList).adapter as TransactionAdapter).currentList.map { it.transaction.id }
            await { ids() == listOf(1L) }
            frame.findViewById<View>(R.id.searchFilters).performClick()
            val cancelled = shadowOf(activity).nextStartedActivityForResult
            shadowOf(activity).receiveResult(cancelled.intent, android.app.Activity.RESULT_CANCELED, null)
            assertEquals(View.GONE, frame.findViewById<View>(R.id.resetSearch).visibility)
            frame.findViewById<View>(R.id.searchFilters).performClick()
            val started = shadowOf(activity).nextStartedActivityForResult
            shadowOf(activity).receiveResult(started.intent, android.app.Activity.RESULT_OK,
                android.content.Intent().putExtra(TransactionFilter.EXTRA, TransactionFilter(search = "关键字").writeTo(Bundle())))
            await { ids() == listOf(1L, 2L) }
            assertEquals("全部月份 ▾", frame.findViewById<TextView>(R.id.billMonthLabel).text.toString())
            assertEquals(View.VISIBLE, frame.findViewById<View>(R.id.resetSearch).visibility)
            val saved = activity.supportFragmentManager.saveFragmentInstanceState(fragment)!!
            activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
            fragment = TransactionsFragment().apply { setInitialSavedState(saved) }
            activity.supportFragmentManager.beginTransaction().add(frame.id, fragment).commitNow()
            await { ids() == listOf(1L, 2L) }
            assertEquals(View.VISIBLE, frame.findViewById<View>(R.id.resetSearch).visibility)
            frame.findViewById<View>(R.id.resetSearch).performClick()
            await { ids() == listOf(1L) }
            assertEquals("${month.label()} ▾", frame.findViewById<TextView>(R.id.billMonthLabel).text.toString())
            assertEquals(View.GONE, frame.findViewById<View>(R.id.resetSearch).visibility)
            assertEquals(2, runBlocking { db.dao().allTransactionsOnce().size })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun billArrowsAndPickerUseActualMonthQueryAndRememberIt() {
        val month = StatisticsPeriod(2026, 12)
        runBlocking { db.dao().insertTransactions(listOf(
            LedgerTransaction(id = 1, amountCents = 100, type = TransactionType.EXPENSE, timestamp = month.bounds().first + 1),
            LedgerTransaction(id = 2, amountCents = 200, type = TransactionType.EXPENSE, timestamp = month.bounds().second),
            LedgerTransaction(id = 3, amountCents = 300, type = TransactionType.EXPENSE, timestamp = month.bounds().first - 1))) }
        val controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        try {
            val activity = controller.get()
            val frame = FrameLayout(activity).apply { id = R.id.pageContainer }
            activity.setContentView(frame)
            var fragment = TransactionsFragment()
            activity.supportFragmentManager.beginTransaction().add(frame.id, fragment).commitNow()
            frame.findViewById<View>(R.id.billMonthPicker).performClick()
            val first = ShadowDialog.getLatestDialog() as AlertDialog
            first.findViewById<NumberPicker>(R.id.yearPicker)!!.value = 2026
            first.findViewById<NumberPicker>(R.id.monthPicker)!!.value = 12
            first.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("2026年12月 ▾", frame.findViewById<TextView>(R.id.billMonthLabel).text.toString())
            fun ids() = (frame.findViewById<RecyclerView>(R.id.transactionList).adapter as TransactionAdapter).currentList.map { it.transaction.id }
            await { ids() == listOf(1L) }
            frame.findViewById<View>(R.id.nextMonth).performClick()
            await { ids() == listOf(2L) }
            assertEquals("2027年1月 ▾", frame.findViewById<TextView>(R.id.billMonthLabel).text.toString())
            frame.findViewById<View>(R.id.billMonthPicker).performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.findViewById<NumberPicker>(R.id.yearPicker)!!.value = 2026
            dialog.findViewById<NumberPicker>(R.id.monthPicker)!!.value = 11
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await { ids() == listOf(3L) }
            val saved = activity.supportFragmentManager.saveFragmentInstanceState(fragment)!!
            activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
            fragment = TransactionsFragment().apply { setInitialSavedState(saved) }
            activity.supportFragmentManager.beginTransaction().add(frame.id, fragment).commitNow()
            await { ids() == listOf(3L) }
            assertEquals("2026年11月 ▾", frame.findViewById<TextView>(R.id.billMonthLabel).text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun statisticsPickerAndAnnualAverageIncludeIncomeOnlyMonths() {
        runBlocking { db.dao().insertTransactions(listOf(
            LedgerTransaction(amountCents = 12000, type = TransactionType.EXPENSE, timestamp = StatisticsPeriod(2026, 9).bounds().first + 1),
            LedgerTransaction(amountCents = 5000, type = TransactionType.INCOME, timestamp = StatisticsPeriod(2026, 1).bounds().first + 1))) }
        val controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        try {
            val activity = controller.get()
            val frame = FrameLayout(activity).apply { id = R.id.pageContainer }; activity.setContentView(frame)
            val fragment = StatisticsFragment()
            activity.supportFragmentManager.beginTransaction().add(frame.id, fragment).commitNow()
            frame.findViewById<View>(R.id.periodLabel).performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.findViewById<NumberPicker>(R.id.yearPicker)!!.value = 2026
            dialog.findViewById<NumberPicker>(R.id.monthPicker)!!.value = 9
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(StatisticsPeriod(2026, 9), ViewModelProvider(fragment)[StatisticsViewModel::class.java].period.value)
            assertEquals(2, runBlocking { (activity.application as LedgerApp).repository.snapshot().transactions.size })
            val model = ViewModelProvider(fragment)[StatisticsViewModel::class.java]
            await(detail = { "period=${model.period.value}, totals=${model.totals.value}, ui=${frame.findViewById<TextView>(R.id.netExpense).text}, lifecycle=${fragment.viewLifecycleOwner.lifecycle.currentState}" }) { frame.findViewById<TextView>(R.id.netExpense).text.toString() == UiFormat.money(12000) }
            frame.findViewById<View>(R.id.yearMode).performClick()
            await { frame.findViewById<TextView>(R.id.average).text.toString() == UiFormat.money(6000) }
            assertTrue(frame.findViewById<TextView>(R.id.averageLabel).text.toString().contains("2 个记账月份"))
            frame.findViewById<View>(R.id.monthMode).performClick()
            assertEquals(StatisticsPeriod(2026, 9), ViewModelProvider(fragment)[StatisticsViewModel::class.java].period.value)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun occasionalCategoriesStayVisibleAndSelectedHistoricalCategoryRemainsVisible() {
        val id = runBlocking { db.dao().insertCategory(Category(name = "旅行", priority = 2, iconKey = "travel")) }
        val controller = Robolectric.buildActivity(TransactionEditorActivity::class.java).setup()
        try {
            val activity = controller.get()
            val group = activity.findViewById<ChipGroup>(R.id.occasionalCategoryChips)
            fun travel(): Chip? = (0 until group.childCount).map { group.getChildAt(it) }.filterIsInstance<Chip>().firstOrNull { it.text.toString() == "旅行" }
            await { travel() != null }
            assertEquals(View.VISIBLE, travel()!!.visibility)
            travel()!!.performClick()
            assertTrue(travel()!!.isChecked)
        } finally { controller.pause().stop().destroy() }
        runBlocking { db.dao().insertTransaction(LedgerTransaction(id = 100, amountCents = 1000, type = TransactionType.EXPENSE, timestamp = System.currentTimeMillis(), categoryId = id)) }
        val edit = Robolectric.buildActivity(TransactionEditorActivity::class.java, android.content.Intent(RuntimeEnvironment.getApplication(), TransactionEditorActivity::class.java).putExtra("id", 100L)).setup()
        try {
            val group = edit.get().findViewById<ChipGroup>(R.id.occasionalCategoryChips)
            await { (0 until group.childCount).map { group.getChildAt(it) }.filterIsInstance<Chip>().any { it.text.toString() == "旅行" && it.isChecked && it.visibility == View.VISIBLE } }
        } finally { edit.pause().stop().destroy() }
    }

    private fun await(detail: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); if (condition()) return; Thread.sleep(10) }
        fail("Timed out waiting for screen data: ${detail()}")
    }
}
