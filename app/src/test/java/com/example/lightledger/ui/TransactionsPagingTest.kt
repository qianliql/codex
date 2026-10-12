package com.example.lightledger.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.MenuItem
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Exercises the real fragment, asynchronous Room reads, scrolling, and selection together. */
@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = LedgerApp::class, sdk = [29], qualifiers = "w360dp-h760dp-xxhdpi")
class TransactionsPagingTest {
    @Test fun scrollingLoadsAllPagesOnceAndKeepsSelectionThenRefreshesAndFilters() {
        val db = LedgerDatabase.get(RuntimeEnvironment.getApplication())
        runBlocking {
            db.dao().insertTransactions((1L..207L).map { id ->
                LedgerTransaction(id = id, amountCents = id * 100, timestamp = MonthSelection.at().bounds().first + id,
                    type = TransactionType.EXPENSE, merchant = "示例商户 $id",
                    classificationStatus = if (id <= 7) ClassificationStatus.NEED_CONFIRM else ClassificationStatus.CLASSIFIED)
            })
        }
        val controller = Robolectric.buildActivity(LayoutCompatibilityTest.PreviewActivity::class.java).setup()
        try {
            val activity = controller.get()
            val frame = FrameLayout(activity).apply { id = R.id.pageContainer }
            activity.setContentView(frame)
            val fragment = TransactionsFragment()
            activity.supportFragmentManager.beginTransaction().add(frame.id, fragment).commitNow()
            val list = frame.findViewById<RecyclerView>(R.id.transactionList)
            val adapter = list.adapter as TransactionAdapter
            fun layout() {
                val metrics = frame.resources.displayMetrics
                frame.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
                frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
            }
            fun awaitCount(count: Int) = await { layout(); adapter.itemCount == count }
            fun moreAction(id: Int) {
                frame.findViewById<View>(R.id.moreActions).performClick()
                val popup = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
                val menuList = findList(popup.contentView)!!
                val titles = (0 until menuList.adapter.count).map { (menuList.adapter.getItem(it) as MenuItem).title.toString() }
                assertEquals(3, titles.size)
                assertFalse(titles.any { it.contains("刷新") || it.contains("清除") || it.contains("重置") })
                val position = (0 until menuList.adapter.count).first { (menuList.adapter.getItem(it) as MenuItem).itemId == id }
                menuList.performItemClick(menuList.adapter.getView(position, null, menuList), position, menuList.adapter.getItemId(position))
            }
            fun nextPage(from: Int, count: Int) {
                list.scrollToPosition(from)
                layout()
                list.scrollBy(0, 120)
                awaitCount(count)
            }

            awaitCount(100)
            assertEquals(View.GONE, frame.findViewById<View>(R.id.resetSearch).visibility)
            assertEquals((207L downTo 108L).toList(), adapter.currentList.map { it.transaction.id })
            assertTrue(list.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick())
            nextPage(99, 200)
            nextPage(199, 207)
            assertEquals((207L downTo 1L).toList(), adapter.currentList.map { it.transaction.id })
            assertEquals("已选 1 笔", frame.findViewById<TextView>(R.id.selectionCount).text.toString())
            assertFalse(frame.findViewById<SwipeRefreshLayout>(R.id.refreshBills).isEnabled)
            // A later page does not implicitly select newly loaded bills.
            val first = adapter.onCreateViewHolder(list, 0)
            adapter.onBindViewHolder(first, 0)
            assertTrue(first.binding.selectionCheck.isChecked)
            val last = adapter.onCreateViewHolder(list, 0)
            adapter.onBindViewHolder(last, 206)
            assertFalse(last.binding.selectionCheck.isChecked)

            frame.findViewById<View>(R.id.cancelSelection).performClick()
            awaitCount(100)
            assertTrue(frame.findViewById<SwipeRefreshLayout>(R.id.refreshBills).isEnabled)
            moreAction(R.id.action_pending)
            awaitCount(7)
            assertEquals((7L downTo 1L).toList(), adapter.currentList.map { it.transaction.id })
            assertTrue(frame.findViewById<TextView>(R.id.resultCount).text.toString().contains("筛选 1 项"))
            assertEquals(View.VISIBLE, frame.findViewById<View>(R.id.resetSearch).visibility)
            frame.findViewById<View>(R.id.resetSearch).performClick()
            awaitCount(100)
            assertEquals(View.GONE, frame.findViewById<View>(R.id.resetSearch).visibility)
            assertEquals(View.VISIBLE, frame.findViewById<View>(R.id.billPageTitle).visibility)
            assertFalse(frame.findViewById<TextView>(R.id.resultCount).text.toString().contains("筛选"))
            // None of the UI actions write or delete records.
            assertEquals(207, runBlocking { db.dao().allTransactionsOnce().size })
        } finally { controller.pause().stop().destroy(); db.close() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for asynchronous bill page")
    }

    private fun findList(view: View): ListView? {
        if (view is ListView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findList(view.getChildAt(i))?.let { return it }
        return null
    }
}
