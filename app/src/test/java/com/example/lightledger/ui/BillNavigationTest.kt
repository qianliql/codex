package com.example.lightledger.ui

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.LedgerDatabase
import com.example.lightledger.data.LedgerTransaction
import com.example.lightledger.data.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = LedgerApp::class, sdk = [29], qualifiers = "w360dp-h760dp-xxhdpi")
class BillNavigationTest {
    private lateinit var db: LedgerDatabase

    @Before fun prepare() {
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        db = LedgerDatabase.get(RuntimeEnvironment.getApplication())
    }

    @After fun close() {
        db.close()
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
    }

    @Test fun existingBillNeverStartsWithNewBillTitleOrEmptyForm() {
        runBlocking { db.dao().insertTransaction(LedgerTransaction(id = 81, amountCents = 1234,
            timestamp = System.currentTimeMillis(), type = TransactionType.EXPENSE, merchant = "示例商户")) }
        val intent = Intent(RuntimeEnvironment.getApplication(), TransactionEditorActivity::class.java).putExtra("id", 81L)
        val controller = Robolectric.buildActivity(TransactionEditorActivity::class.java, intent).create()
        try {
            val page = controller.get()
            assertEquals("编辑账单", page.findViewById<TextView>(R.id.pageTitle).text.toString())
            if (!page.findViewById<View>(R.id.save).isEnabled) {
                assertEquals(View.INVISIBLE, page.findViewById<View>(R.id.editorScroll).visibility)
            }
            controller.start().resume().visible()
            await { page.findViewById<View>(R.id.save).isEnabled }
            assertEquals("编辑账单", page.findViewById<TextView>(R.id.pageTitle).text.toString())
            assertEquals(View.VISIBLE, page.findViewById<View>(R.id.editorScroll).visibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun returningFromEditKeepsBillListAtItsPreviousPageAndRow() {
        val monthStart = MonthSelection.at().bounds().first
        runBlocking { db.dao().insertTransactions((1L..207L).map { id ->
            LedgerTransaction(id = id, amountCents = id * 100, timestamp = monthStart + id,
                type = TransactionType.EXPENSE, merchant = "示例商户 $id")
        }) }
        val controller = Robolectric.buildActivity(LayoutCompatibilityTest.PreviewActivity::class.java).setup()
        try {
            val activity = controller.get()
            val frame = FrameLayout(activity).apply { id = R.id.pageContainer }
            activity.setContentView(frame)
            val fragment = TransactionsFragment()
            activity.supportFragmentManager.beginTransaction().add(frame.id, fragment).commitNow()
            val list = frame.findViewById<RecyclerView>(R.id.transactionList)
            val adapter = list.adapter as TransactionAdapter
            val manager = list.layoutManager as LinearLayoutManager
            fun layout() {
                val metrics = frame.resources.displayMetrics
                frame.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
                frame.layout(0, 0, frame.measuredWidth, frame.measuredHeight)
            }
            await { layout(); adapter.itemCount == 100 }
            list.scrollToPosition(99); await { layout(); adapter.itemCount == 200 }
            list.scrollToPosition(199); await { layout(); adapter.itemCount == 207 }
            list.scrollToPosition(150); layout()
            val first = manager.findFirstVisibleItemPosition()
            assertTrue(first >= 140)
            val anchorId = adapter.currentList[first].transaction.id
            val top = manager.findViewByPosition(first)!!.top
            runBlocking {
                val old = db.dao().transaction(anchorId)!!
                db.dao().updateTransaction(old.copy(merchant = "修改后的示例商户"))
            }
            fragment.onResume()
            await {
                layout()
                adapter.itemCount >= 200 &&
                    adapter.currentList.firstOrNull { it.transaction.id == anchorId }?.transaction?.merchant == "修改后的示例商户" &&
                    adapter.currentList.getOrNull(manager.findFirstVisibleItemPosition())?.transaction?.id == anchorId
            }
            assertEquals(top, manager.findViewByPosition(manager.findFirstVisibleItemPosition())!!.top)
        } finally { controller.pause().stop().destroy() }
    }

    private fun await(ready: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            if (ready()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for bill screen")
    }
}
