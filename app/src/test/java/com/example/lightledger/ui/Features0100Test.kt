package com.example.lightledger.ui

import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.*
import com.example.lightledger.databinding.ActivityTransactionSearchBinding
import com.example.lightledger.notification.DetectedPaymentMethod
import com.example.lightledger.notification.PaymentNotificationService
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Job
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
import java.lang.reflect.Modifier
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = LedgerApp::class, sdk = [29, 35], qualifiers = "w360dp-h760dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Features0100Test {
    private val app get() = RuntimeEnvironment.getApplication() as LedgerApp
    private lateinit var db: LedgerDatabase
    @Before fun prepare() {
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        ViewModelProvider.AndroidViewModelFactory::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == ViewModelProvider.AndroidViewModelFactory::class.java }
            .forEach { it.isAccessible = true; it.set(null, null) }
        app.getSharedPreferences("permission_guide", 0).edit().putBoolean("shown", true).apply()
        app.getSharedPreferences("detected_payment_method", 0).edit().clear().commit()
        RuntimeEnvironment.setFontScale(1f)
        db = LedgerDatabase.get(app)
    }
    @After fun close() {
        drainWrites(); db.close()
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
    }

    @Test fun statisticsArrowReturnsHomeInMonthAndYearAfterRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            var activity = controller.get(); idle()
            repeat(2) { index ->
                activity.findViewById<View>(R.id.viewStatistics).performClick(); idle()
                if (index == 1) {
                    activity.findViewById<View>(R.id.yearMode).performClick(); idle()
                    controller.recreate(); activity = controller.get(); idle()
                }
                assertTrue(activity.supportFragmentManager.findFragmentById(R.id.pageContainer) is StatisticsFragment)
                activity.findViewById<View>(R.id.statisticsBack).performClick(); idle()
                assertTrue(activity.supportFragmentManager.findFragmentById(R.id.pageContainer) is HomeFragment)
                assertFalse(activity.isFinishing)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun trustedNotificationSavesSourceMethodAndRejectedMessagesDoNotReplaceIt() {
        val controller = Robolectric.buildService(PaymentNotificationService::class.java).create()
        val service = controller.get()
        var id = 100
        fun send(pkg: String, title: String, text: String, summary: Boolean = false) {
            val notification = Notification.Builder(app, "synthetic").setContentTitle(title).setContentText(text).build()
            if (summary) notification.flags = notification.flags or Notification.FLAG_GROUP_SUMMARY
            val key = shadowOf(service).addActiveNotification(pkg, id++, notification)
            service.onNotificationPosted(service.activeNotifications.single { it.key == key })
            drainWrites()
        }
        try {
            send("com.tencent.mm", "微信支付", "支付成功，支付金额￥12.34")
            assertEquals(PaymentMethod.WECHAT, DetectedPaymentMethod.latest(app))
            send("com.eg.android.AlipayGphone", "支付宝", "付款成功，金额￥5.67")
            assertEquals(PaymentMethod.ALIPAY, DetectedPaymentMethod.latest(app))
            listOf(
                "昨日记账日报，支出￥100.00", "付款失败，金额￥7.00",
                "付款成功，查看详情", "付款成功，￥7.00与￥8.00"
            ).forEach { send("com.tencent.mm", "微信支付", it) }
            send("com.tencent.mm", "朋友", "支付成功，￥8.00")
            send("com.tencent.mm", "微信支付", "支付成功，￥8.00", true)
            send("untrusted.package", "微信支付", "支付成功，￥8.00")
            assertEquals(PaymentMethod.ALIPAY, DetectedPaymentMethod.latest(app))
            val bills = runBlocking { app.repository.snapshot().transactions }
            assertEquals(2, bills.size)
            assertEquals(setOf(1234L to PaymentMethod.WECHAT, 567L to PaymentMethod.ALIPAY), bills.map { it.amountCents to it.paymentMethod }.toSet())
            assertTrue(bills.all { it.source == TransactionSource.NOTIFICATION && it.amountCents > 0 })
            val prefs = app.getSharedPreferences("detected_payment_method", 0)
            val latestTime = prefs.getLong("time", 0)
            DetectedPaymentMethod.remember(app, PaymentMethod.WECHAT, latestTime - 1)
            DetectedPaymentMethod.remember(app, PaymentMethod.CASH, latestTime + 1)
            assertEquals(PaymentMethod.ALIPAY, DetectedPaymentMethod.latest(app))
            assertEquals(setOf("method", "time"), prefs.all.keys)
        } finally { controller.destroy() }
    }

    @Test fun newManualBillPrefillsLatestButManualChoiceStillSaves() {
        DetectedPaymentMethod.remember(app, PaymentMethod.ALIPAY, 2_000)
        val controller = Robolectric.buildActivity(TransactionEditorActivity::class.java).setup()
        try {
            val activity = controller.get(); await { activity.findViewById<View>(R.id.save).isEnabled }
            assertEquals("支付宝", checkedMethod(activity))
            val chips = activity.findViewById<ChipGroup>(R.id.paymentMethodChips)
            chips.children().single { it.text == "现金" }.performClick()
            DetectedPaymentMethod.remember(app, PaymentMethod.WECHAT, 3_000)
            assertEquals("现金", checkedMethod(activity))
            activity.findViewById<EditText>(R.id.amount).setText("18.20")
            activity.findViewById<View>(R.id.save).performClick(); await { activity.isFinishing }
            val bill = runBlocking { app.repository.snapshot().transactions.single() }
            assertEquals(PaymentMethod.CASH, bill.paymentMethod)
            assertEquals(1820L, bill.amountCents)
            assertEquals(TransactionSource.MANUAL, bill.source)
        } finally { controller.pause().stop().destroy() }
        val next = Robolectric.buildActivity(TransactionEditorActivity::class.java).setup()
        try { await { next.get().findViewById<View>(R.id.save).isEnabled }; assertEquals("微信", checkedMethod(next.get())) }
        finally { next.pause().stop().destroy() }
    }

    @Test fun editingOldOtherAndConfirmingScreenshotKeepTheirOwnPaymentMethod() {
        DetectedPaymentMethod.remember(app, PaymentMethod.ALIPAY, 5_000)
        val billId = runBlocking { app.repository.save(LedgerTransaction(amountCents = 123, type = TransactionType.INCOME, timestamp = 1_800_000_000_000, paymentMethod = PaymentMethod.OTHER, note = "保留备注")) }
        val edit = Robolectric.buildActivity(TransactionEditorActivity::class.java, Intent(app, TransactionEditorActivity::class.java).putExtra("id", billId)).setup()
        try {
            val activity = edit.get(); await { activity.findViewById<View>(R.id.save).isEnabled }
            assertNull(checkedMethod(activity))
            activity.findViewById<EditText>(R.id.note).setText("修改备注")
            activity.findViewById<View>(R.id.save).performClick(); await { activity.isFinishing }
            assertEquals(PaymentMethod.OTHER, runBlocking { app.repository.transaction(billId)!!.paymentMethod })
        } finally { edit.pause().stop().destroy() }
        val intent = Intent(app, TransactionEditorActivity::class.java).setAction(TransactionEditorActivity.ACTION_SCREENSHOT_CONFIRM)
            .putExtra("amountCents", 400L).putExtra("type", "EXPENSE").putExtra("paymentMethod", "BANK_CARD")
        val screenshot = Robolectric.buildActivity(TransactionEditorActivity::class.java, intent).setup()
        try { await { screenshot.get().findViewById<View>(R.id.save).isEnabled }; assertEquals("银行卡", checkedMethod(screenshot.get())) }
        finally { screenshot.pause().stop().destroy() }
    }

    @Test fun homeShowsCrossMonthUnknownAmountsAndCountUpdatesWhenConfirmed() {
        seedUnknownAmounts()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            var activity = controller.get(); await { activity.findViewById<TextView>(R.id.unconfirmedAmountCount).text == "2 笔" }
            activity.findViewById<View>(R.id.viewUnconfirmedAmounts).performClick(); idle()
            fun adapter() = activity.findViewById<RecyclerView>(R.id.transactionList).adapter as TransactionAdapter
            await { adapter().itemCount == 2 }
            assertEquals(setOf(101L, 102L), adapter().currentList.map { it.transaction.id }.toSet())
            assertEquals("全部月份 ▾", activity.findViewById<TextView>(R.id.billMonthLabel).text)
            assertTrue(activity.findViewById<TextView>(R.id.resultCount).text.contains("待确认金额"))
            controller.recreate(); activity = controller.get(); await { adapter().itemCount == 2 }
            val edit = Robolectric.buildActivity(TransactionEditorActivity::class.java, Intent(app, TransactionEditorActivity::class.java).putExtra("id", 101L)).setup()
            try {
                val page = edit.get(); await { page.findViewById<View>(R.id.save).isEnabled }
                assertEquals("", page.findViewById<EditText>(R.id.amount).text.toString())
                page.findViewById<EditText>(R.id.amount).setText("6.25")
                page.findViewById<View>(R.id.save).performClick(); await { page.isFinishing }
                assertEquals("跨年备注", runBlocking { app.repository.transaction(101)!!.note })
            } finally { edit.pause().stop().destroy() }
            await { adapter().itemCount == 1 }
            assertEquals(102L, adapter().currentList.single().transaction.id)
            activity.findViewById<View>(R.id.billBack).performClick(); await { activity.findViewById<TextView>(R.id.unconfirmedAmountCount)?.text == "1 笔" }
            activity.findViewById<View>(R.id.viewUnconfirmedAmounts).performClick(); await { adapter().itemCount == 1 }
            activity.findViewById<View>(R.id.resetSearch).performClick(); idle()
            assertFalse(activity.findViewById<TextView>(R.id.resultCount).text.contains("待确认金额"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun amountFilterIsVisibleEditableAndOldBundlesKeepNormalSearch() {
        val controller = Robolectric.buildActivity(LayoutCompatibilityTest.PreviewActivity::class.java).setup()
        try {
            val page = controller.get()
            val filter = TransactionFilter(amountPendingOnly = true)
            assertEquals(filter, TransactionFilter.readFrom(filter.writeTo(Bundle())))
            assertFalse(TransactionFilter.readFrom(Bundle().apply { putBoolean("pending_only", true) }).amountPendingOnly)
            val b = ActivityTransactionSearchBinding.inflate(page.layoutInflater)
            val form = TransactionSearchForm(b, filter, runBlocking { db.dao().categoryList() }, emptyList(), { _, _ -> }, {})
            assertEquals(View.VISIBLE, b.additionalFilters.visibility)
            assertTrue(b.amountPendingOnly.isChecked)
            assertTrue(form.snapshot().description(emptyList(), emptyList()).contains("待确认金额"))
            b.amountPendingOnly.performClick(); assertFalse(form.snapshot().amountPendingOnly)
            b.amountPendingOnly.performClick(); b.resetFilters.performClick(); assertEquals(TransactionFilter(), form.snapshot())
            runBlocking {
                seedUnknownAmounts()
                val rows = app.repository.searchTransactions("", 0, Long.MAX_VALUE, -1, 0, "", "", "", false, 1, 1, true)
                assertEquals(listOf(101L), rows.map { it.transaction.id })
                assertEquals(4, app.repository.searchTransactions("", 0, Long.MAX_VALUE, -1, 0, "", "", "", false, 100, 0).size)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun nativePreviewsHaveReachableShortcutAndStatisticsBackOnNarrowLargeFont() {
        seedUnknownAmounts()
        listOf(false, true).forEach { narrow ->
            if (narrow) { RuntimeEnvironment.setQualifiers("w320dp-h700dp-xxhdpi"); RuntimeEnvironment.setFontScale(1.3f) }
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val page = controller.get(); await { page.findViewById<TextView>(R.id.unconfirmedAmountCount).text == "2 笔" }
                val root = page.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                capture(root, if (narrow) "home-large-font" else "home")
                val shortcut = page.findViewById<View>(R.id.viewUnconfirmedAmounts)
                assertTrue(shortcut.width > 0 && shortcut.height >= 48 * root.resources.displayMetrics.density)
                page.findViewById<View>(R.id.viewStatistics).performClick(); idle()
                capture(root, if (narrow) "statistics-large-font" else "statistics")
                assertTrue(page.findViewById<View>(R.id.statisticsBack).width >= 48 * root.resources.displayMetrics.density)
                page.findViewById<View>(R.id.statisticsBack).performClick(); idle()
                page.findViewById<View>(R.id.viewUnconfirmedAmounts).performClick()
                await { (page.findViewById<RecyclerView>(R.id.transactionList).adapter as TransactionAdapter).itemCount == 2 }
                capture(root, if (narrow) "unconfirmed-large-font" else "unconfirmed")
                val back = page.findViewById<View>(R.id.billBack)
                assertEquals("bill return arrow remains at the start while filters are active", 0, back.left)
            } finally { controller.pause().stop().destroy() }
        }
    }

    private fun seedUnknownAmounts() = runBlocking {
        db.dao().insertTransactions(listOf(
            LedgerTransaction(id = 101, amountCents = 0, type = TransactionType.EXPENSE, timestamp = StatisticsPeriod(2025, 1).bounds().first, source = TransactionSource.SCREENSHOT, note = "跨年备注", classificationStatus = ClassificationStatus.NEED_CONFIRM),
            LedgerTransaction(id = 102, amountCents = 0, type = TransactionType.INCOME, timestamp = StatisticsPeriod(2026, 9).bounds().first, classificationStatus = ClassificationStatus.NEED_CONFIRM),
            LedgerTransaction(id = 103, amountCents = 800, type = TransactionType.REFUND, timestamp = StatisticsPeriod(2026, 9).bounds().first, classificationStatus = ClassificationStatus.NEED_CONFIRM),
            LedgerTransaction(id = 104, amountCents = 900, type = TransactionType.EXPENSE, timestamp = StatisticsPeriod(2026, 9).bounds().first, classificationStatus = ClassificationStatus.UNCLASSIFIED)
        ))
    }
    private fun ChipGroup.children() = (0 until childCount).map { getChildAt(it) }.filterIsInstance<Chip>()
    private fun checkedMethod(page: TransactionEditorActivity) = page.findViewById<ChipGroup>(R.id.paymentMethodChips).children().firstOrNull { it.isChecked }?.text?.toString()
    private fun drainWrites() = runBlocking { app.notificationScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    private fun await(ready: () -> Boolean) { val end = System.nanoTime() + 15_000_000_000; while (System.nanoTime() < end) { idle(); if (ready()) return; Thread.sleep(10) }; fail("0.10.0 screen update timed out") }
    private fun capture(root: View, name: String) {
        val metrics = root.resources.displayMetrics
        fun force(view: View) { view.forceLayout(); if (view is ViewGroup) (0 until view.childCount).forEach { force(view.getChildAt(it)) } }
        repeat(3) { force(root); root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)); root.layout(0, 0, root.measuredWidth, root.measuredHeight) }
        System.getProperty("lightledger.preview.dir")?.let { directory ->
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bitmap))
            File(directory, "LightLedger-0.10.0-$name-api${RuntimeEnvironment.getApiLevel()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
