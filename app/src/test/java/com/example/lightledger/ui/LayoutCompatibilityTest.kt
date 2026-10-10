package com.example.lightledger.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lightledger.databinding.RowCategoryManageBinding
import com.example.lightledger.databinding.RowExpenseSliceBinding
import com.example.lightledger.databinding.DialogCategoryNameBinding
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.example.lightledger.R
import com.example.lightledger.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Isolated Android layouts; preview amounts are fictional and no phone data is read. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29, 35], qualifiers = "w360dp-h760dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutCompatibilityTest {
    class PreviewActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_LightLedger)
            super.onCreate(savedInstanceState)
        }
    }

    @Test fun editorCategoriesAndSelectionFitAndRender() {
        RuntimeEnvironment.setFontScale(1f)
        listOf(R.layout.activity_transaction_editor to "editor", R.layout.activity_category to "categories",
            R.layout.fragment_transactions to "selection").forEach { (layout, name) ->
            val controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
            try {
                val activity = controller.get()
                activity.setContentView(layout)
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                SystemBarInsets.apply(root)
                ViewCompat.dispatchApplyWindowInsets(root, bars(24, 24))
                fillSample(root, layout)
                if (name == "selection") {
                    root.findViewById<View>(R.id.normalActions).visibility = View.GONE
                    root.findViewById<View>(R.id.selectionBar).visibility = View.VISIBLE
                    root.findViewById<TextView>(R.id.selectionCount).text = "已选 1 笔账单"
                    root.findViewById<TextView>(R.id.deleteSelected).text = "删除（1）"
                    (root.findViewById<RecyclerView>(R.id.transactionList).adapter as TransactionAdapter).setSelection(setOf(1), true)
                }
                shadowOf(Looper.getMainLooper()).idle()
                measure(root)
                assertTextFits(root)
                if (layout == R.layout.activity_transaction_editor) {
                    val save = root.findViewById<View>(R.id.save)
                    val scroll = root.findViewById<View>(R.id.editorScroll)
                    assertTrue(save.isShown)
                    assertTrue("scroll area remains usable", scroll.height > root.height / 2)
                    val keyboard = WindowInsetsCompat.Builder(bars(24, 24))
                        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 72, 0, 780))
                        .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 72, 0, 72))
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 780)).build()
                    ViewCompat.dispatchApplyWindowInsets(root, keyboard)
                    measure(root)
                    val location = IntArray(2)
                    save.getLocationOnScreen(location)
                    assertTrue("save clears keyboard", location[1] + save.height <= root.height - root.paddingBottom)
                    ViewCompat.dispatchApplyWindowInsets(root, bars(24, 24))
                    measure(root)
                }
                if (RuntimeEnvironment.getApiLevel() == 29) savePreview(root, name)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun categoryRenameDialogRemainsReadableAndShowsInlineError() {
        val controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        try {
            val activity = controller.get()
            val d = DialogCategoryNameBinding.inflate(activity.layoutInflater)
            d.categoryName.setText("正餐")
            d.nameExplanation.text = "历史账单和商户规则会同步使用新名称"
            d.nameInput.error = "这个名称已存在，请换一个"
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("更改分类名称").setView(d.root).setNegativeButton("取消", null).setPositiveButton("保存", null).show()
            try {
                val root = dialog.window!!.decorView
                shadowOf(Looper.getMainLooper()).idle()
                val metrics = root.resources.displayMetrics
                root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.AT_MOST))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                assertTextFits(root)
                assertEquals("这个名称已存在，请换一个", d.nameInput.error.toString())
                if (RuntimeEnvironment.getApiLevel() == 29) savePreview(root, "rename")
            } finally { dialog.dismiss() }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun primaryScreensFitAndRenderAt1080By2280() {
        RuntimeEnvironment.setFontScale(1f)
        val screens = listOf(R.layout.fragment_home to "home", R.layout.fragment_transactions to "bills",
            R.layout.fragment_statistics to "statistics", R.layout.fragment_settings to "settings")
        screens.forEach { (layout, name) ->
            val controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
            try {
                val activity = controller.get()
                activity.setContentView(R.layout.activity_main)
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val container = root.findViewById<ViewGroup>(R.id.pageContainer)
                activity.layoutInflater.inflate(layout, container, true)
                val nav = root.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)
                nav.selectedItemId = if (layout == R.layout.fragment_settings) R.id.nav_settings else R.id.nav_home
                assertEquals(2, nav.menu.size())
                SystemBarInsets.apply(root)
                ViewCompat.dispatchApplyWindowInsets(root, bars(24, 24))
                fillSample(root, layout)
                shadowOf(Looper.getMainLooper()).idle()
                measure(root)
                assertTextFits(root)
                assertTrue("navigation below safe area", nav.bottom <= root.height - root.paddingBottom)
                if (RuntimeEnvironment.getApiLevel() == 29) savePreview(root, name)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun formsAndPagesRemainReadableWithLargeFonts() {
        RuntimeEnvironment.setQualifiers("w320dp-h700dp-xxhdpi")
        RuntimeEnvironment.setFontScale(1.3f)
        val layouts = listOf(R.layout.fragment_home, R.layout.fragment_transactions, R.layout.fragment_statistics,
            R.layout.fragment_settings, R.layout.activity_transaction_editor, R.layout.activity_category,
            R.layout.dialog_category_name, R.layout.dialog_category_preferences,
            R.layout.activity_tags, R.layout.activity_merchant_rules, R.layout.activity_batch_import)
        layouts.forEach { layout ->
            val controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
            try {
                val activity = controller.get()
                activity.setContentView(layout)
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                fillSample(root, layout)
                measure(root)
                assertTextFits(root)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun billsKeepTwoThirdsOfThePageInNormalSearchAndSelectionModes() {
        listOf(Triple("w360dp-h760dp-xxhdpi", 1f, "standard"),
            Triple("w320dp-h700dp-xxhdpi", 1.3f, "large-font")).forEach { (size, scale, name) ->
            RuntimeEnvironment.setQualifiers(size)
            RuntimeEnvironment.setFontScale(scale)
            val controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
            try {
                val activity = controller.get()
                activity.setContentView(R.layout.activity_main)
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val page = root.findViewById<ViewGroup>(R.id.pageContainer)
                activity.layoutInflater.inflate(R.layout.fragment_transactions, page, true)
                root.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.nav_home
                SystemBarInsets.apply(root)
                ViewCompat.dispatchApplyWindowInsets(root, bars(24, 24))
                fillSample(root, R.layout.fragment_transactions)
                val list = root.findViewById<RecyclerView>(R.id.transactionList)
                listOf("normal", "search", "selection").forEach { mode ->
                    root.findViewById<View>(R.id.normalActions).visibility = if (mode == "selection") View.GONE else View.VISIBLE
                    root.findViewById<View>(R.id.selectionBar).visibility = if (mode == "selection") View.VISIBLE else View.GONE
                    root.findViewById<View>(R.id.resetSearch).visibility = if (mode == "search") View.VISIBLE else View.GONE
                    root.findViewById<View>(R.id.billPageTitle).visibility = if (mode == "search") View.GONE else View.VISIBLE
                    root.findViewById<TextView>(R.id.resultCount).text = if (mode == "search") "100 笔 · 筛选 3 项" else "100 笔 · 长按多选"
                    shadowOf(Looper.getMainLooper()).idle()
                    ViewCompat.dispatchApplyWindowInsets(root, bars(24, 24))
                    measure(root)
                    assertTextFits(root)
                    if (mode == "search") {
                        val reset = root.findViewById<TextView>(R.id.resetSearch)
                        val search = root.findViewById<View>(R.id.searchFilters)
                        val touch = (48 * root.resources.displayMetrics.density).toInt()
                        assertTrue(reset.width >= touch && reset.height >= touch)
                        assertTrue("reset beside search", reset.left >= search.right)
                        assertTrue("reset text fits", reset.layout.getLineWidth(0) <= reset.width - reset.compoundPaddingLeft - reset.compoundPaddingRight)
                        val more = root.findViewById<View>(R.id.moreActions)
                        assertTrue("toolbar stays inside page", more.right <= (more.parent as View).width)
                    }
                    assertTrue("$name/$mode: list takes at least two thirds of bill page", list.height * 3 >= page.height * 2)
                    // Also include the bottom navigation in the denominator, excluding system bars.
                    val usable = root.height - root.paddingTop - root.paddingBottom
                    assertTrue("$name/$mode: list takes at least two thirds including navigation", list.height * 3 >= usable * 2)
                    if (mode != "selection") listOf(R.id.searchFilters, R.id.addTransaction, R.id.moreActions, R.id.previousMonth, R.id.nextMonth, R.id.billMonthPicker).forEach { id ->
                        val button = root.findViewById<View>(id)
                        val touch = (48 * root.resources.displayMetrics.density).toInt()
                        assertTrue("toolbar touch target", button.height >= touch && button.width >= touch)
                    }
                    println("Bill area $name/$mode: ${list.height}/$usable (${list.height * 100 / usable}%)")
                    if (RuntimeEnvironment.getApiLevel() == 29) savePreview(root, "bills-$name-$mode")
                }
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun systemInsetsDoNotAccumulateAndKeyboardGetsSpace() {
        val controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.setContentView(R.layout.activity_main)
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            SystemBarInsets.apply(root)
            val d = root.resources.displayMetrics.density
            repeat(2) { ViewCompat.dispatchApplyWindowInsets(root, bars(24, 24)) }
            assertEquals((24 * d).toInt(), root.paddingTop)
            assertEquals((24 * d).toInt(), root.paddingBottom)
            val keyboard = if (RuntimeEnvironment.getApiLevel() < 30) {
                // Before API 30, IME arrival changes the legacy system-window inset.
                // An IME-only Builder override is lost when dispatched through that platform API.
                WindowInsetsCompat.Builder(bars(24, 24))
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, (24 * d).toInt(), 0, (260 * d).toInt()))
                    .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars(), Insets.of(0, (24 * d).toInt(), 0, (24 * d).toInt())).build()
            } else {
                WindowInsetsCompat.Builder(bars(24, 24))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, (260 * d).toInt())).build()
            }
            ViewCompat.dispatchApplyWindowInsets(root, keyboard)
            assertEquals((260 * d).toInt(), root.paddingBottom)
            ViewCompat.dispatchApplyWindowInsets(root, bars(24, 24))
            assertEquals((24 * d).toInt(), root.paddingBottom)
        } finally { controller.pause().stop().destroy() }
    }

    private fun bars(top: Int, bottom: Int): WindowInsetsCompat {
        val d = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        return WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(),
            Insets.of(0, (top * d).toInt(), 0, (bottom * d).toInt())).build()
    }

    private fun measure(root: View) {
        val metrics = root.resources.displayMetrics
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }
    }

    private fun assertTextFits(view: View) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView && view.text.isNotEmpty() && view.layout != null) {
            assertTrue("text has no width: ${view.text}", view.width > 0)
            val height = view.height - view.compoundPaddingTop - view.compoundPaddingBottom
            assertTrue("text clipped vertically: ${view.text}", view.layout.height <= height + 2)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) assertTextFits(view.getChildAt(i))
    }

    private fun fillSample(root: View, layout: Int) {
        fun text(id: Int, value: String) { root.findViewById<TextView>(id)?.text = value }
        text(R.id.monthLabel, "2026年10月 · 本月账本")
        text(R.id.monthExpense, "¥1,286.50")
        text(R.id.todayExpense, "¥38.00")
        text(R.id.monthDetail, "支出 ¥1,386.50 · 退款 ¥100.00")
        root.findViewById<LinearLayout>(R.id.categorySummary)?.let {
            CategoryRows.show(it, listOf("正餐" to 82650L, "日用" to 29900L, "娱乐" to 16100L), "")
        }
        text(R.id.billMonthLabel, "2026年10月 ▾")
        text(R.id.periodLabel, "2026年10月 ▾")
        text(R.id.netExpense, "¥1,286.50")
        text(R.id.expense, "¥1,386.50")
        text(R.id.refund, "¥100.00")
        root.findViewById<LinearLayout>(R.id.categoryContainer)?.let {
            if (layout == R.layout.activity_category) {
                CategoryCatalog.primary.forEach { label ->
                    val row = RowCategoryManageBinding.inflate(android.view.LayoutInflater.from(root.context), it, false)
                    row.categoryName.text = label
                    row.categoryHint.text = "常用分类 · 点击改名"
                    CategoryIcons.apply(row.categoryIcon, label)
                    it.addView(row.root)
                }
            } else {
                val data = com.example.lightledger.statistics.CategoryBreakdown.from(listOf(
                    ExpenseCategoryTotal(1, "正餐", 82650), ExpenseCategoryTotal(2, "日用", 29900),
                    ExpenseCategoryTotal(3, "娱乐", 16100), ExpenseCategoryTotal(4, "交通", 5000)))
                root.findViewById<CategoryDonutView>(R.id.categoryDonut).setData(data)
                val legend = root.findViewById<LinearLayout>(R.id.categoryLegend)
                data.slices.forEach { slice ->
                    val row = RowExpenseSliceBinding.inflate(android.view.LayoutInflater.from(root.context), legend, false)
                    row.sliceName.text = slice.label
                    row.sliceShare.text = String.format(java.util.Locale.CHINA, "%.2f%%", slice.amountCents.toDouble() / data.totalCents * 100)
                    row.sliceAmount.text = UiFormat.money(slice.amountCents)
                    legend.addView(row.root)
                }
            }
        }
        root.findViewById<ChipGroup>(R.id.categoryChips)?.let { group ->
            CategoryCatalog.primary.forEach { label -> addChip(group, label, label == "正餐") }
            root.findViewById<android.widget.HorizontalScrollView>(R.id.categoryScroll)?.let { EditorChoiceRows.bindCategories(group, it) }
        }
        root.findViewById<ChipGroup>(R.id.paymentMethodChips)?.let { group ->
            PaymentMethod.entries.filter { it != PaymentMethod.OTHER }.forEach { addChip(group, UiFormat.method(it), it == PaymentMethod.WECHAT) }
            EditorChoiceRows.bindPayments(group)
        }
        root.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.typeToggle)?.check(R.id.expenseOption)
        root.findViewById<TextView>(R.id.amount)?.setText("18.60")
        root.findViewById<View>(R.id.save)?.isEnabled = true
        root.findViewById<SpendingBarChartView>(R.id.spendingChart)?.setData(StatisticsPeriod(2026, 10),
            listOf(TimeTotal("1", 15800, 0), TimeTotal("3", 3800, 0), TimeTotal("5", 21000, 0), TimeTotal("9", 9600, 0)))
        text(R.id.notificationStatus, "通知自动记账：已开启")
        text(R.id.quickNotificationStatus, "快捷分类通知：已开启")
        val listId = if (layout == R.layout.fragment_home) R.id.recentList else R.id.transactionList
        root.findViewById<RecyclerView>(listId)?.let { list ->
            val adapter = TransactionAdapter { }
            list.layoutManager = LinearLayoutManager(root.context)
            list.adapter = adapter
            val samples = if (listId == R.id.transactionList) listOf("午餐" to "正餐", "日常用品" to "日用",
                "下午茶" to "零食", "电影" to "娱乐", "打印资料" to "教育", "充电线" to "数码")
                else listOf("午餐" to "正餐", "日常用品" to "日用")
            adapter.submitList(samples.mapIndexed { index, (merchant, category) ->
                TransactionRow(LedgerTransaction(id = index + 1L, amountCents = if (index == 0) 3800 else 2990,
                    type = TransactionType.EXPENSE, merchant = merchant, timestamp = 1791529200000,
                    paymentMethod = PaymentMethod.WECHAT, classificationStatus = ClassificationStatus.CLASSIFIED,
                    note = if (listId == R.id.transactionList && index == 1) "纸巾和洗手液" else null), category)
            })
        }
    }

    private fun savePreview(root: View, name: String) {
        val directory = System.getProperty("lightledger.preview.dir") ?: return
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File(directory, "LightLedger-0.9.0-$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun addChip(group: ChipGroup, label: String, selected: Boolean) {
        val d = group.resources.displayMetrics.density
        group.addView(Chip(group.context).apply {
            text = label; textSize = 14f; isCheckable = true; isCheckedIconVisible = false
            chipBackgroundColor = context.getColorStateList(R.color.ledger_chip_background)
            chipStrokeColor = android.content.res.ColorStateList.valueOf(context.getColor(R.color.ledger_border))
            chipStrokeWidth = d
            setTextColor(context.getColorStateList(R.color.ledger_chip_text))
            chipIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, CategoryIcons.icon(label))
            chipIconTint = android.content.res.ColorStateList.valueOf(context.getColor(R.color.ledger_teal))
            chipIconSize = 18 * d
            setEnsureMinTouchTargetSize(true)
            minHeight = (48 * d).toInt()
            isChecked = selected
        })
    }
}
