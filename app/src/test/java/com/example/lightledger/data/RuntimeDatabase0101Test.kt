package com.example.lightledger.data

import android.os.Looper
import androidx.lifecycle.Observer
import com.example.lightledger.LedgerApp
import com.example.lightledger.LedgerTestRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Two database instances using Room's local Binder, not two physical Android processes. */
@RunWith(LedgerTestRunner::class)
@Config(application = LedgerApp::class, sdk = [29, 35])
class RuntimeDatabase0101Test {
    private val app get() = RuntimeEnvironment.getApplication() as LedgerApp
    private fun newInstance(): LedgerDatabase {
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        return LedgerDatabase.get(app)
    }

    @Test fun listenerWritesUpdatesAndDeletesRefreshUiInstanceAndSurviveUiReopening() {
        val ui = newInstance(); val owner = newInstance()
        var rows: List<TransactionRow>? = null
        val live = ui.dao().allTransactions()
        val observer = Observer<List<TransactionRow>> { rows = it }
        try {
            runBlocking { ui.dao().categoryList(); owner.dao().categoryList() }
            live.observeForever(observer)
            await { rows != null }
            val repo = LedgerRepository(owner.dao())
            val bill = LedgerTransaction(amountCents = 1234, type = TransactionType.EXPENSE,
                timestamp = 1_800_000_000_000, paymentMethod = PaymentMethod.WECHAT,
                source = TransactionSource.NOTIFICATION, notificationKey = "synthetic:runtime:1", note = "虚构备注")
            val id = runBlocking { repo.saveNotification(bill)!! }
            await { rows?.singleOrNull()?.transaction?.id == id }
            runBlocking { repo.save(repo.transaction(id)!!.copy(amountCents = 2345, note = "虚构修改")) }
            await { rows?.singleOrNull()?.transaction?.amountCents == 2345L }
            live.removeObserver(observer)
            ui.close()
            val reopened = newInstance()
            try {
                val saved = runBlocking { reopened.dao().transaction(id)!! }
                assertEquals(2345L, saved.amountCents)
                assertEquals("虚构修改", saved.note)
                assertEquals(PaymentMethod.WECHAT, saved.paymentMethod)
                var reopenedRows: List<TransactionRow>? = null
                val reopenedLive = reopened.dao().allTransactions()
                val secondObserver = Observer<List<TransactionRow>> { reopenedRows = it }
                reopenedLive.observeForever(secondObserver)
                await { reopenedRows?.size == 1 }
                runBlocking { repo.delete(repo.transaction(id)!!) }
                await { reopenedRows?.isEmpty() == true }
                reopenedLive.removeObserver(secondObserver)
            } finally { reopened.close() }
        } finally {
            live.removeObserver(observer); ui.close(); owner.close()
            LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        }
    }

    @Test fun concurrentNotificationReplayAcrossInstancesStoresOneBill() {
        val first = newInstance(); val second = newInstance()
        try {
            runBlocking {
                first.dao().categoryList(); second.dao().categoryList()
                val bill = LedgerTransaction(amountCents = 567, type = TransactionType.EXPENSE,
                    timestamp = 1_800_000_000_000, paymentMethod = PaymentMethod.ALIPAY,
                    source = TransactionSource.NOTIFICATION, notificationKey = "synthetic:runtime:duplicate")
                val one = async(Dispatchers.IO) { LedgerRepository(first.dao()).saveNotification(bill) }
                val two = async(Dispatchers.IO) { LedgerRepository(second.dao()).saveNotification(bill) }
                assertEquals(1, listOf(one.await(), two.await()).count { it != null })
                assertEquals(1, LedgerRepository(first.dao()).snapshot().transactions.size)
                assertEquals(567L, second.dao().transactionForKey(bill.notificationKey!!)!!.amountCents)
            }
        } finally {
            first.close(); second.close()
            LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        }
    }

    private fun await(condition: () -> Boolean) {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for Room invalidation")
    }
}
