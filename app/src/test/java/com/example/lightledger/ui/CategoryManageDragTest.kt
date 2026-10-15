package com.example.lightledger.ui

import android.app.Application
import com.example.lightledger.data.Category
import com.example.lightledger.data.CategoryScope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = Application::class, sdk = [29])
class CategoryManageDragTest {
    @Test fun draggedRowShiftsInterveningRowsButCannotCrossPriorityGroup() {
        val adapter = CategoryManageAdapter({}, { _, _ -> }, {})
        adapter.show(listOf(
            Category(1, "正餐", priority = 0, sortOrder = 0),
            Category(2, "零食", priority = 0, sortOrder = 1),
            Category(3, "日用", priority = 0, sortOrder = 2),
            Category(4, "娱乐", priority = 1, sortOrder = 0),
            Category(5, "工资", priority = 2, sortOrder = 0, scope = CategoryScope.INCOME),
        ))
        assertTrue(adapter.previewMove(adapter.positionOfCategory("正餐"), adapter.positionOfCategory("日用")))
        assertEquals(listOf(2L, 3L, 1L), adapter.groupIds(0))
        assertFalse(adapter.previewMove(adapter.positionOfCategory("日用"), adapter.positionOfCategory("娱乐")))
        assertEquals(listOf(4L), adapter.groupIds(1))
        assertFalse(adapter.previewMove(adapter.positionOfCategory("工资"), adapter.positionOfCategory("正餐")))
    }
}
