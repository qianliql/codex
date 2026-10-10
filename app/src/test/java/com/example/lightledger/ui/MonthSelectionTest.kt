package com.example.lightledger.ui

import android.app.Application
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import android.view.View
import android.widget.NumberPicker
import androidx.appcompat.app.AlertDialog
import com.example.lightledger.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29, 35], qualifiers = "w320dp-h700dp-xxhdpi")
class MonthSelectionTest {
    @Test fun monthBoundsHandleLeapYearsYearEdgesAndRetainOtherFilters() {
        val old = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            val filter = TransactionFilter(search = "示例", categoryId = 15, pendingOnly = true)
            val december = StatisticsPeriod(2026, 12)
            assertEquals(StatisticsPeriod(2027, 1), MonthSelection.move(december, 1))
            assertEquals(december, MonthSelection.move(StatisticsPeriod(2027, 1), -1))
            val february = MonthSelection.apply(filter, StatisticsPeriod(2024, 2))
            assertEquals(filter.copy(start = february.start, end = february.end), february)
            assertEquals(StatisticsPeriod(2024, 2), MonthSelection.from(february))
            assertEquals(29L * 24 * 60 * 60 * 1000, february.end - february.start)
            assertNull(MonthSelection.from(TransactionFilter()))
            assertNull(MonthSelection.from(february.copy(end = february.end - 1)))
            val midnight = Calendar.getInstance().apply { clear(); set(2024, Calendar.MARCH, 1) }.timeInMillis
            assertEquals(midnight, february.end)
        } finally { TimeZone.setDefault(old) }
    }

    @Test fun pickerConfirmsBothFieldsAndCancellingLeavesAppliedPeriodIntact() {
        RuntimeEnvironment.setFontScale(1.3f)
        val controller = Robolectric.buildActivity(LayoutCompatibilityTest.PreviewActivity::class.java).setup()
        try {
            var applied = StatisticsPeriod(2026, 9)
            val dialog = MonthSelection.show(controller.get(), applied) { applied = it }
            dialog.findViewById<NumberPicker>(R.id.yearPicker)!!.value = 2027
            dialog.findViewById<NumberPicker>(R.id.monthPicker)!!.value = 2
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(StatisticsPeriod(2027, 2), applied)
            val cancel = MonthSelection.show(controller.get(), applied) { applied = it }
            cancel.findViewById<NumberPicker>(R.id.monthPicker)!!.value = 8
            cancel.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(StatisticsPeriod(2027, 2), applied)
            val year = MonthSelection.show(controller.get(), StatisticsPeriod(2026, null)) { applied = it }
            assertEquals(View.GONE, year.findViewById<View>(R.id.monthColumn)!!.visibility)
            year.findViewById<NumberPicker>(R.id.yearPicker)!!.value = 2025
            year.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(StatisticsPeriod(2025, null), applied)
        } finally { controller.pause().stop().destroy() }
    }
}
