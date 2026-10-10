package com.example.lightledger.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.lightledger.R
import com.example.lightledger.data.Category
import com.example.lightledger.data.Tag
import com.example.lightledger.databinding.ActivityThemeBinding
import com.example.lightledger.databinding.ActivityTransactionSearchBinding
import com.google.android.material.chip.Chip
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29, 35], qualifiers = "w360dp-h760dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchAndThemeTest {
    class PreviewActivity : LedgerActivity()
    private val categories = listOf(Category(id = 10000000000L, name = "正餐"), Category(id = 2, name = "日用"), Category(id = 3, name = "娱乐"))

    @Test fun searchDraftAppliesAllCriteriaAndResetClearsThem() {
        val initial = TransactionFilter(" 午餐 ", 100, 300, 10000000000L, 7, "WECHAT", "NOTIFICATION", "EXPENSE", true)
        assertEquals(initial, TransactionFilter.readFrom(initial.writeTo(Bundle())))
        val controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        try {
            val activity = controller.get()
            val b = ActivityTransactionSearchBinding.inflate(activity.layoutInflater)
            activity.setContentView(b.root)
            var applied: TransactionFilter? = null
            val form = TransactionSearchForm(b, initial, categories, listOf(Tag(7, "日常")), { _, _ -> }, { applied = it })
            assertNull(applied)
            assertEquals(initial.copy(search = "午餐"), form.snapshot())
            b.applyFilters.performClick()
            assertEquals(initial.copy(search = "午餐"), applied)
            b.resetFilters.performClick()
            assertEquals(TransactionFilter(), form.snapshot())
            assertEquals(View.GONE, b.additionalFilters.visibility)
            val chip = (0 until b.categoryChoices.childCount).map { b.categoryChoices.getChildAt(it) }.filterIsInstance<Chip>().first { it.tag == 10000000000L }
            chip.performClick()
            b.keyword.setText("用品")
            b.applyFilters.performClick()
            assertEquals(TransactionFilter(search = "用品", categoryId = 10000000000L), applied)
            val restored = TransactionFilter.readFrom(form.snapshot().writeTo(Bundle()))
            assertEquals(applied, restored)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun invalidDateCannotApplyAndPresetsIncludeLastDay() {
        val controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        try {
            val b = ActivityTransactionSearchBinding.inflate(controller.get().layoutInflater)
            var applied = false
            TransactionSearchForm(b, TransactionFilter(start = 300, end = 100), categories, emptyList(), { _, _ -> }, { applied = true })
            b.applyFilters.performClick()
            assertFalse(applied); assertEquals(View.VISIBLE, b.dateError.visibility)
            val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 31, 23, 59, 59) }.timeInMillis
            val month = SearchDatePreset.THIS_MONTH.range(now)!!
            assertEquals(1, Calendar.getInstance().apply { timeInMillis = month.first }.get(Calendar.DAY_OF_MONTH))
            assertEquals(Calendar.NOVEMBER, Calendar.getInstance().apply { timeInMillis = month.second }.get(Calendar.MONTH))
            val week = SearchDatePreset.LAST_SEVEN_DAYS.range(now)!!
            assertEquals(25, Calendar.getInstance().apply { timeInMillis = week.first }.get(Calendar.DAY_OF_MONTH))
            assertTrue(now >= week.first && now < week.second)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun fourPalettesPersistAndRenderAcrossPages() {
        val context = RuntimeEnvironment.getApplication()
        LedgerPalette.entries.forEach { palette ->
            LedgerThemes.select(context, palette)
            val controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
            try {
                val activity = controller.get()
                assertEquals(palette, LedgerThemes.selected(activity))
                assertEquals(palette.accent, activity.ledgerColor(R.color.ledger_teal))
                assertEquals(palette.background, activity.ledgerColor(R.color.ledger_background))
                val b = ActivityTransactionSearchBinding.inflate(activity.layoutInflater)
                TransactionSearchForm(b, TransactionFilter(search = "午餐", method = "WECHAT"), categories, emptyList(), { _, _ -> }, {})
                activity.setContentView(b.root)
                safeMeasure(b.root)
                assertTextFits(b.root)
                assertTrue(b.searchScroll.height > b.root.height / 2)
                assertEquals(palette.accent, b.applyFilters.backgroundTintList!!.getColorForState(b.applyFilters.drawableState, 0))
                save(b.root, "search-${palette.key}")
                val t = ActivityThemeBinding.inflate(activity.layoutInflater)
                ThemeActivity.populate(t, activity) {}
                activity.setContentView(t.root)
                safeMeasure(t.root); assertTextFits(t.root)
                assertEquals(4, t.themeChoices.childCount)
                save(t.root, "theme-${palette.key}")
                activity.setContentView(R.layout.activity_main)
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                activity.layoutInflater.inflate(R.layout.fragment_home, root.findViewById(R.id.pageContainer), true)
                root.findViewById<TextView>(R.id.monthExpense).text = "¥1,286.50"
                root.findViewById<TextView>(R.id.todayExpense).text = "¥38.00"
                root.findViewById<TextView>(R.id.monthLabel).text = "2026年10月 · 本月账本"
                root.findViewById<TextView>(R.id.monthDetail).text = "支出 ¥1,386.50 · 退款 ¥100.00"
                CategoryRows.show(root.findViewById(R.id.categorySummary), listOf("正餐" to 82650L, "日用" to 29900L, "娱乐" to 16100L), "")
                root.findViewById<View>(R.id.accessBanner).visibility = View.GONE
                safeMeasure(root); assertTextFits(root)
                val nav = root.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)
                assertEquals(palette.accent, nav.itemIconTintList!!.getColorForState(intArrayOf(android.R.attr.state_checked), 0))
                save(root, "home-${palette.key}")
            } finally { controller.pause().stop().destroy() }
        }
        context.getSharedPreferences("appearance", 0).edit().putString("palette", "unknown").apply()
        assertEquals(LedgerPalette.FOREST, LedgerThemes.selected(context))
    }

    @Test fun searchAndThemeFitLargeTextAndKeyboard() {
        RuntimeEnvironment.setQualifiers("w320dp-h700dp-xxhdpi"); RuntimeEnvironment.setFontScale(1.3f)
        val controller = Robolectric.buildActivity(PreviewActivity::class.java).setup()
        try {
            val activity = controller.get()
            val b = ActivityTransactionSearchBinding.inflate(activity.layoutInflater)
            TransactionSearchForm(b, TransactionFilter(), categories, emptyList(), { _, _ -> }, {})
            activity.setContentView(b.root)
            safeMeasure(b.root); assertTextFits(b.root)
            val keyboard = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 72, 0, 650))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 72, 0, 72))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 650)).build()
            ViewCompat.dispatchApplyWindowInsets(b.root, keyboard); measure(b.root)
            val location = IntArray(2); b.applyFilters.getLocationOnScreen(location)
            assertTrue("search button stays above keyboard", location[1] + b.applyFilters.height <= b.root.height - 650)
            val t = ActivityThemeBinding.inflate(activity.layoutInflater); ThemeActivity.populate(t, activity) {}
            activity.setContentView(t.root); safeMeasure(t.root); assertTextFits(t.root)
        } finally { controller.pause().stop().destroy() }
    }

    private fun safeMeasure(root: View) {
        SystemBarInsets.apply(root)
        ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 72, 0, 72)).build())
        shadowOf(Looper.getMainLooper()).idle(); measure(root)
    }
    private fun measure(root: View) {
        val metrics = root.resources.displayMetrics
        root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }
    private fun assertTextFits(view: View) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView && view.text.isNotEmpty() && view.layout != null) assertTrue("clipped: ${view.text}", view.layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom + 2)
        if (view is ViewGroup) for (i in 0 until view.childCount) assertTextFits(view.getChildAt(i))
    }
    private fun save(root: View, name: String) {
        if (RuntimeEnvironment.getApiLevel() != 29) return
        val directory = System.getProperty("lightledger.preview.dir") ?: return
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap)); File(directory, "LightLedger-0.5.3-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
