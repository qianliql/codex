package com.example.lightledger.ui

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.*
import kotlinx.coroutines.Job
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
import java.lang.reflect.Modifier
import java.time.Duration

@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = LedgerApp::class, sdk = [29, 35], qualifiers = "w360dp-h760dp-xxhdpi")
class AmountConfirmationUi0102Test {
    private val app get() = RuntimeEnvironment.getApplication() as LedgerApp
    private lateinit var db: LedgerDatabase
    @Before fun prepare() {
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        ViewModelProvider.AndroidViewModelFactory::class.java.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.type == ViewModelProvider.AndroidViewModelFactory::class.java }
            .forEach { it.isAccessible = true; it.set(null, null) }
        app.getSharedPreferences("permission_guide", 0).edit().putBoolean("shown", true).apply()
        db = LedgerDatabase.get(app)
    }
    @After fun close() {
        runBlocking { app.notificationScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
        db.close()
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
    }
    private fun seed(): Long = runBlocking {
        app.repository.saveNotification(LedgerTransaction(amountCents = 1234, type = TransactionType.EXPENSE,
            timestamp = StatisticsPeriod(2026, 9).bounds().first, source = TransactionSource.NOTIFICATION,
            paymentMethod = PaymentMethod.WECHAT, notificationKey = "synthetic-ui-0102", note = "保留备注"))!!
    }
    @Test fun knownAmountAppearsOnHomeAndConfirmationUpdatesCountWithoutMakingAnotherBill() {
        val id = seed()
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val home = main.get(); await { home.findViewById<TextView>(R.id.unconfirmedAmountCount).text == "1 笔" }
            home.findViewById<View>(R.id.viewUnconfirmedAmounts).performClick()
            await { home.findViewById<RecyclerView>(R.id.transactionList) != null }
            val list = home.findViewById<RecyclerView>(R.id.transactionList).adapter as TransactionAdapter
            await { list.itemCount == 1 }
            assertEquals(1234L, list.currentList.single().transaction.amountCents)
            val edit = Robolectric.buildActivity(TransactionEditorActivity::class.java, Intent(app, TransactionEditorActivity::class.java).putExtra("id", id)).setup()
            try {
                val page = edit.get(); await { page.findViewById<View>(R.id.save).isEnabled }
                assertEquals("确认金额", page.findViewById<TextView>(R.id.pageTitle).text)
                assertEquals("12.34", page.findViewById<EditText>(R.id.amount).text.toString())
                assertEquals("确认并保存", page.findViewById<TextView>(R.id.save).text)
                page.findViewById<EditText>(R.id.amount).setText("12.35")
                page.findViewById<View>(R.id.save).performClick(); await { page.isFinishing }
                val saved = runBlocking { app.repository.transaction(id)!! }
                assertEquals(1235L, saved.amountCents); assertTrue(saved.amountConfirmed)
                assertEquals("保留备注", saved.note); assertEquals(PaymentMethod.WECHAT, saved.paymentMethod)
                assertEquals(TransactionSource.NOTIFICATION, saved.source)
                assertEquals(1, runBlocking { app.repository.snapshot().transactions.size })
            } finally { edit.pause().stop().destroy() }
            await { list.itemCount == 0 }
            home.findViewById<View>(R.id.billBack).performClick()
            await { home.findViewById<TextView>(R.id.unconfirmedAmountCount)?.text == "0 笔" }
        } finally { main.pause().stop().destroy() }
    }
    @Test fun openingThenLeavingConfirmationDoesNotConfirmAmount() {
        val id = seed()
        val edit = Robolectric.buildActivity(TransactionEditorActivity::class.java, Intent(app, TransactionEditorActivity::class.java).putExtra("id", id)).setup()
        try {
            val page = edit.get(); await { page.findViewById<View>(R.id.save).isEnabled }
            page.findViewById<View>(R.id.goBack).performClick()
            assertTrue(page.isFinishing)
            assertFalse(runBlocking { app.repository.transaction(id)!!.amountConfirmed })
            assertEquals(1234L, runBlocking { app.repository.transaction(id)!!.amountCents })
        } finally { edit.pause().stop().destroy() }
    }
    private fun await(ready: () -> Boolean) {
        val end = System.nanoTime() + 15_000_000_000
        while (System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)); if (ready()) return; Thread.sleep(10) }
        fail("Amount confirmation did not update")
    }
}
