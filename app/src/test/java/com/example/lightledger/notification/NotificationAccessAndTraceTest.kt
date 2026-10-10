package com.example.lightledger.notification

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29, 35])
class NotificationAccessAndTraceTest {
    class RecordingActivity : Activity() {
        val launched = mutableListOf<Intent>()
        var rejectDetail = false
        override fun startActivity(intent: Intent) {
            launched.add(intent)
            if (rejectDetail && intent.action == Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS) throw ActivityNotFoundException()
        }
    }
    @Test fun permissionPageTargetsThisAppAndFallsBackWhenUnsupported() {
        val controller = Robolectric.buildActivity(RecordingActivity::class.java).setup()
        try {
            val activity = controller.get()
            NotificationAccess.openSettings(activity)
            if (Build.VERSION.SDK_INT >= 30) {
                val intent = activity.launched.single()
                assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, intent.action)
                assertEquals(ComponentName(activity, PaymentNotificationService::class.java).flattenToString(), intent.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME))
                activity.launched.clear(); activity.rejectDetail = true
                NotificationAccess.openSettings(activity)
                assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, activity.launched.last().action)
            } else assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, activity.launched.single().action)
        } finally { controller.pause().stop().destroy() }
    }
}
