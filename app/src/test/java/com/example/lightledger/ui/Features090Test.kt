package com.example.lightledger.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
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
class Features090Test {
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

    @Test fun donutPeriodFilteringRingAndMergedCategoryDrilldown() {
        val p = StatisticsPeriod(2026, 9); val (start, end) = p.bounds()
        val names = listOf("正餐", "日用", "娱乐", "交通", "教育", "数码")
        runBlocking { db.dao().insertCategory(Category(name = "交通")) }
        val categories = runBlocking { db.dao().categoryList() }.filter { it.name in names }.sortedBy { names.indexOf(it.name) }
        assertEquals(6, categories.size)
        runBlocking {
            db.dao().insertTransactions(listOf(3000L, 2500L, 2000L, 1000L, 800L, 700L).mapIndexed { i, amount ->
                LedgerTransaction(id = i + 1L, amountCents = amount, type = TransactionType.EXPENSE, categoryId = categories[i].id, timestamp = start + 1000, note = "虚构项目$i")
            } + listOf(
                LedgerTransaction(id = 20, amountCents = 2000, type = TransactionType.REFUND, categoryId = categories[0].id, timestamp = start + 2000),
                LedgerTransaction(id = 21, amountCents = 9000, type = TransactionType.INCOME, categoryId = categories[0].id, timestamp = start + 2000),
                LedgerTransaction(id = 22, amountCents = 9000, type = TransactionType.EXPENSE, categoryId = categories[0].id, timestamp = end),
                LedgerTransaction(id = 23, amountCents = 9000, type = TransactionType.EXPENSE, categoryId = categories[0].id, timestamp = start - 1)))
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val fragment = StatisticsFragment()
            activity.supportFragmentManager.beginTransaction().replace(R.id.pageContainer, fragment).commitNow()
            val model = ViewModelProvider(fragment)[StatisticsViewModel::class.java]
            model.choose(p)
            val root = fragment.requireView()
            val legend = root.findViewById<LinearLayout>(R.id.categoryLegend)
            await { legend.childCount == 4 }
            assertEquals(listOf("正餐", "日用", "娱乐", "其他项目"), (0 until 4).map { legend.getChildAt(it).findViewById<TextView>(R.id.sliceName).text.toString() })
            assertEquals("¥25.00", legend.getChildAt(3).findViewById<TextView>(R.id.sliceAmount).text.toString())
            measure(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
            val donut = root.findViewById<CategoryDonutView>(R.id.categoryDonut)
            assertTrue(donut.contentDescription.contains("¥100.00"))
            (root as android.widget.ScrollView).scrollTo(0, (donut.parent.parent as View).top - 100)
            capture(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0), "category-donut")
            assertNull(donut.sliceAt(donut.width / 2f, donut.height / 2f))
            val x = donut.width / 2f + minOf(donut.width, donut.height) * .39f; val y = donut.height / 2f
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                MotionEvent.obtain(0, 10, action, x, y, 0).let { assertTrue(donut.dispatchTouchEvent(it)); it.recycle() }
            }
            val first = shadowOf(activity).nextStartedActivity
            assertEquals(categories[0].id, first.getLongExtra("category_id", -1))
            assertEquals(9, first.getIntExtra("month", -1))
            legend.getChildAt(3).performClick(); idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val labels = texts(dialog.window!!.decorView).map { it.text.toString() }
            listOf("交通", "教育", "数码", "¥10.00", "¥8.00", "¥7.00").forEach { assertTrue(labels.contains(it)) }
            capture(dialog.window!!.decorView, "merged-categories", dialog = true)
            texts(dialog.window!!.decorView).single { it.text.toString() == "教育" }.parent.parent.let { (it as View).performClick() }
            val next = shadowOf(activity).nextStartedActivity
            assertEquals(categories[4].id, next.getLongExtra("category_id", -1))
            assertEquals(2026, next.getIntExtra("year", 0)); assertEquals(9, next.getIntExtra("month", 0))
            root.findViewById<View>(R.id.yearMode).performClick()
            await { donut.contentDescription.contains("¥280.00") }
            root.findViewById<View>(R.id.nextPeriod).performClick()
            await { donut.contentDescription.contains("¥0.00") }
            assertEquals(0, legend.childCount)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun rankPagesStableOrderNotesAndLiveChangesIncludingUnclassified() {
        val p = StatisticsPeriod(2026, 9); val (start, end) = p.bounds()
        val meal = runBlocking { db.dao().categoryList().single { it.name == "正餐" } }
        val bills = (1..167).map { i -> LedgerTransaction(id = i.toLong(), amountCents = 1000 + i / 2L,
            type = TransactionType.EXPENSE, categoryId = meal.id, timestamp = start + i, note = "虚构备注$i", merchant = "虚构商户") }
        runBlocking { db.dao().insertTransactions(bills) }
        val intent = Intent(RuntimeEnvironment.getApplication(), ExpenseRankActivity::class.java)
            .putExtra("year", 2026).putExtra("month", 9).putExtra("category_id", meal.id).putExtra("category_label", "正餐")
        val controller = Robolectric.buildActivity(ExpenseRankActivity::class.java, intent).setup()
        try {
            val activity = controller.get(); val list = activity.findViewById<RecyclerView>(R.id.rankList)
            val adapter = list.adapter as ExpenseRankAdapter
            await { adapter.itemCount >= 80 }
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            measure(root); capture(root, "expense-rank")
            repeat(8) { list.scrollToPosition(adapter.itemCount - 1); measure(root); idle(); Thread.sleep(30) }
            await { adapter.itemCount == 167 }
            assertEquals(bills.sortedWith(compareByDescending<LedgerTransaction> { it.amountCents }.thenByDescending { it.timestamp }.thenByDescending { it.id }).map { it.id }, adapter.currentList.map { it.transaction.id })
            assertEquals("虚构备注167", adapter.currentList.first().transaction.note)
            list.scrollToPosition(0); measure(root); idle(); measure(root)
            assertTrue(texts(list).any { it.text.toString() == "1. 虚构备注167" })
            list.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
            assertEquals(167L, shadowOf(activity).nextStartedActivity.getLongExtra("id", -1))
            runBlocking { db.dao().insertTransaction(LedgerTransaction(id = 200, amountCents = 9000, type = TransactionType.EXPENSE, categoryId = meal.id, timestamp = start + 200)) }
            await { adapter.currentList.firstOrNull()?.transaction?.id == 200L }
            val nullCategory = runBlocking { db.dao().categoryList().single { it.name == "未分类" }.id }
            runBlocking { db.dao().insertTransactions(listOf(
                LedgerTransaction(id = 300, amountCents = 500, type = TransactionType.EXPENSE, timestamp = start),
                LedgerTransaction(id = 301, amountCents = 600, type = TransactionType.EXPENSE, timestamp = end - 1, categoryId = nullCategory),
                LedgerTransaction(id = 302, amountCents = 700, type = TransactionType.REFUND, timestamp = start))) }
            assertEquals(listOf(301L, 300L), runBlocking { db.dao().expenseRank(start, end, 0, 80, 0) }.map { it.transaction.id })
            controller.recreate()
            await { (controller.get().findViewById<RecyclerView>(R.id.rankList).adapter as ExpenseRankAdapter).currentList.firstOrNull()?.transaction?.id == 200L }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun categoryFormDisablesFullTargetsAndGroupsRenderAtLargeFont() {
        val controller = Robolectric.buildActivity(CategoryActivity::class.java).setup()
        try {
            val activity = controller.get(); val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            await { texts(root).any { it.text == "娱乐" } }
            val before = runBlocking { db.dao().snapshot() }
            activity.findViewById<View>(R.id.addCategory).performClick()
            var dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val notification = dialog.findViewById<MaterialButton>(R.id.notificationPriority)!!
            assertFalse(notification.isEnabled)
            val toggle = dialog.findViewById<MaterialButtonToggleGroup>(R.id.priorityChoices)!!
            val selection = toggle.checkedButtonId
            notification.performClick(); assertEquals(selection, toggle.checkedButtonId)
            dialog.findViewById<EditText>(R.id.categoryName)!!.setText("虚构分类")
            idle(); capture(dialog.window!!.decorView, "new-category", dialog = true)
            listOf(R.id.notificationPriority, R.id.commonPriority, R.id.occasionalPriority).forEach { id ->
                val option = dialog.findViewById<MaterialButton>(id)!!
                assertEquals(2, option.layout.lineCount)
                assertEquals(0, option.layout.getEllipsisCount(0)); assertEquals(0, option.layout.getEllipsisCount(1))
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals(before, runBlocking { db.dao().snapshot() })
            val name = texts(root).single { it.text == "日用" }
            (name.parent.parent.parent as View).performClick()
            dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.findViewById<MaterialButton>(R.id.notificationPriority)!!.isEnabled)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            RuntimeEnvironment.setQualifiers("w320dp-h700dp-xxhdpi"); RuntimeEnvironment.setFontScale(1.3f)
            measure(root)
            assertTrue((texts(root).single { it.text == "正餐" }.parent.parent.parent as View).isLongClickable)
            capture(root, "category-groups-large-font")
            activity.findViewById<View>(R.id.addCategory).performClick()
            dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.findViewById<EditText>(R.id.categoryName)!!.setText("虚构分类")
            capture(dialog.window!!.decorView, "new-category-large-font", dialog = true)
            listOf(R.id.notificationPriority, R.id.commonPriority, R.id.occasionalPriority).forEach { id ->
                val option = dialog.findViewById<MaterialButton>(id)!!
                assertTrue(option.layout.lineCount in 2..3)
                assertEquals(option.text.length, option.layout.getLineEnd(option.layout.lineCount - 1))
                (0 until option.layout.lineCount).forEach { line -> assertEquals(0, option.layout.getEllipsisCount(line)) }
            }
            assertFalse(dialog.findViewById<MaterialButton>(R.id.commonPriority)!!.isEnabled)
            dialog.findViewById<MaterialButton>(R.id.commonPriority)!!.performClick()
            assertEquals(R.id.occasionalPriority, dialog.findViewById<MaterialButtonToggleGroup>(R.id.priorityChoices)!!.checkedButtonId)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await { runBlocking { db.dao().categoryList().any { it.name == "虚构分类" && it.priority == 2 } } }
        } finally { controller.pause().stop().destroy() }
    }
    private fun texts(v: View): List<TextView> = (if (v is TextView) listOf(v) else emptyList()) + (if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList())
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    private fun await(ready: () -> Boolean) { val end = System.nanoTime() + 15_000_000_000; while (System.nanoTime() < end) { idle(); if (ready()) return; Thread.sleep(10) }; fail("0.9 screen update timed out") }
    private fun measure(root: View, dialog: Boolean = false) { val m = root.resources.displayMetrics; repeat(3) { root.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, if (dialog) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY)); root.layout(0, 0, root.measuredWidth, root.measuredHeight) } }
    private fun capture(root: View, name: String, dialog: Boolean = false) { idle(); measure(root, dialog); val directory = System.getProperty("lightledger.preview.dir") ?: return; val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bitmap)); File(directory, "LightLedger-0.9.0-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
}
