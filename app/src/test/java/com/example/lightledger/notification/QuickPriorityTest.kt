package com.example.lightledger.notification

import android.app.Application
import android.app.NotificationManager
import com.example.lightledger.data.Category
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class QuickPriorityTest {
    @Test fun notificationUsesThreeChosenCategoriesInPriorityOrder() {
        val context = RuntimeEnvironment.getApplication()
        val categories = listOf(Category(1, "旅行", priority = 0, sortOrder = 1, iconKey = "travel"), Category(2, "交通", priority = 0, sortOrder = 0),
            Category(3, "饮品", priority = 0, sortOrder = 2), Category(4, "正餐", priority = 1), Category(5, "其他", priority = 2))
        QuickClassificationManager.show(context, 101, 1234, categories)
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(101)
        assertNotNull(notification)
        assertEquals(listOf("交通", "旅行", "饮品"), notification.actions.map { it.title.toString() })
        assertNotNull(notification.contentIntent)
    }
}
