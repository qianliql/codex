package com.example.lightledger.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.*
import com.example.lightledger.databinding.ActivityTransactionSearchBinding
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
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
class CategoryScreen060Test {
    @Test fun actualCategoryGroupsEditIconAndPriorityAndCollapseInSearch() {
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        val db = LedgerDatabase.get(RuntimeEnvironment.getApplication())
        val travelId = runBlocking { db.dao().insertCategory(Category(name = "旅行", priority = 2, iconKey = "travel")) }
        val controller = Robolectric.buildActivity(CategoryActivity::class.java).setup()
        try {
            val activity = controller.get()
            val container = activity.findViewById<ViewGroup>(R.id.categoryContainer)
            await { container.childCount > 5 }
            assertFalse(texts(container).any { it.text.toString() == "旅行" })
            val folded = texts(container).single { it.text.toString().contains("不常用") }
            folded.performClick()
            assertTrue(texts(container).any { it.text.toString() == "旅行" })
            texts(container).single { it.text.toString() == "旅行" }.let { name -> (name.parent.parent.parent as View).performClick() }
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.occasionalPriority)!!.isChecked)
            dialog.findViewById<View>(R.id.commonPriority)!!.performClick()
            dialog.findViewById<View>(R.id.chooseIcon)!!.performClick()
            val icons = ShadowDialog.getLatestDialog() as AlertDialog
            texts(icons.window!!.decorView).filterIsInstance<Chip>().single { it.text.toString() == "交通" }.performClick()
            icons.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await { !dialog.isShowing }
            val selected = runBlocking { db.dao().categoryList() }.single { it.id == travelId }
            assertEquals(1, selected.priority); assertEquals("car", selected.iconKey)
            await { texts(container).any { it.text.toString() == "常用 · 6/8" } }
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            measure(root); save(root, "categories-real")
            runBlocking { db.dao().saveCategoryPreferences(selected.copy(priority = 2)) }
            val search = ActivityTransactionSearchBinding.inflate(activity.layoutInflater)
            val form = TransactionSearchForm(search, TransactionFilter(), runBlocking { db.dao().categoryList() }, emptyList(), { _, _ -> }, {})
            val travel = (0 until search.categoryChoices.childCount).map { search.categoryChoices.getChildAt(it) }.filterIsInstance<Chip>().single { it.tag == travelId }
            assertEquals(View.GONE, travel.visibility)
            search.expandSearchCategories.performClick(); assertEquals(View.VISIBLE, travel.visibility)
            travel.performClick(); assertEquals(travelId, form.snapshot().categoryId)
            assertNotNull(travel.chipIcon)
            RuntimeEnvironment.setFontScale(1.3f)
            RuntimeEnvironment.setQualifiers("w320dp-h700dp-xxhdpi")
            measure(root); assertTextFits(root); save(root, "categories-large-font")
        } finally {
            controller.pause().stop().destroy(); db.close()
            LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        }
    }
    private fun texts(view: View): List<TextView> = (if (view is TextView) listOf(view) else emptyList()) +
        (if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())
    private fun measure(root: View) {
        val m = root.resources.displayMetrics
        root.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }
    private fun assertTextFits(root: View) {
        texts(root).filter { it.isShown && it.text.isNotEmpty() && it.layout != null }.forEach { assertTrue("clipped: ${it.text}", it.layout.height <= it.height - it.compoundPaddingTop - it.compoundPaddingBottom + 2) }
    }
    private fun save(root: View, name: String) {
        if (RuntimeEnvironment.getApiLevel() != 29) return
        val path = System.getProperty("lightledger.preview.dir") ?: return
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bitmap))
        File(path, "LightLedger-0.9.1-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun await(ready: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); if (ready()) return; Thread.sleep(10) }
        fail("Category screen update timed out")
    }
}
