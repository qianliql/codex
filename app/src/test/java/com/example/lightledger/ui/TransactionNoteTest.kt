package com.example.lightledger.ui

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import com.example.lightledger.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class TransactionNoteTest {
    @Test fun noteStaysInItsOwnFieldAndRecycledRowClearsMissingNote() {
        val controller = Robolectric.buildActivity(SearchAndThemeTest.PreviewActivity::class.java).setup()
        try {
            val parent = FrameLayout(controller.get())
            val adapter = TransactionAdapter {}
            val noted = LedgerTransaction(id = 1, amountCents = 1100, type = TransactionType.EXPENSE, timestamp = 100, note = "购买纸巾")
            adapter.submitList(listOf(TransactionRow(noted, "日用")))
            val holder = adapter.onCreateViewHolder(parent, 0)
            adapter.onBindViewHolder(holder, 0)
            assertEquals("日用", holder.binding.title.text.toString())
            assertEquals("备注：购买纸巾", holder.binding.notePreview.text.toString())
            assertEquals(View.VISIBLE, holder.binding.notePreview.visibility)
            adapter.submitList(listOf(TransactionRow(noted.copy(note = null), "日用")))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            // New adapter avoids asynchronous DiffUtil timing influencing the binding test.
            val plain = TransactionAdapter {}
            plain.submitList(listOf(TransactionRow(noted.copy(note = null), "日用")))
            plain.onBindViewHolder(holder, 0)
            assertEquals(View.GONE, holder.binding.notePreview.visibility)
            assertTrue(holder.binding.notePreview.text.isEmpty())
        } finally { controller.pause().stop().destroy() }
    }
}
