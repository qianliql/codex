package com.example.lightledger.ui

import android.content.ComponentName
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.LedgerDatabase
import com.example.lightledger.notification.PaymentNotificationService
import com.google.android.material.bottomnavigation.BottomNavigationView
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
import java.lang.reflect.Modifier
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = LedgerApp::class, sdk = [29, 35], qualifiers = "w360dp-h760dp-xxhdpi")
class NavigationAndAccess070Test {
    private lateinit var db: LedgerDatabase
    @Before fun prepare() {
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        // Keep Robolectric's Application from a preceding test out of the real Activity factory.
        ViewModelProvider.AndroidViewModelFactory::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == ViewModelProvider.AndroidViewModelFactory::class.java }
            .forEach { it.isAccessible = true; it.set(null, null) }
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("permission_guide", 0).edit().putBoolean("shown", true).apply()
        db = LedgerDatabase.get(app)
        setAccess(false)
    }
    @After fun close() {
        db.close()
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
    }

    @Test fun homeOpensBothPagesAndBackOrHomeReturnsIncludingAfterRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            var activity = controller.get()
            idle()
            var nav = activity.findViewById<BottomNavigationView>(R.id.bottomNavigation)
            assertEquals(listOf("首页", "我的"), (0 until nav.menu.size()).map { nav.menu.getItem(it).title.toString() })
            assertTrue(activity.supportFragmentManager.findFragmentById(R.id.pageContainer) is HomeFragment)
            activity.findViewById<View>(R.id.viewTransactions).performClick(); idle()
            assertTrue(activity.supportFragmentManager.findFragmentById(R.id.pageContainer) is TransactionsFragment)
            activity.onBackPressedDispatcher.onBackPressed(); idle()
            assertTrue(activity.supportFragmentManager.findFragmentById(R.id.pageContainer) is HomeFragment)
            activity.findViewById<View>(R.id.viewStatistics).performClick(); idle()
            assertTrue(activity.supportFragmentManager.findFragmentById(R.id.pageContainer) is StatisticsFragment)
            controller.recreate(); activity = controller.get(); idle()
            assertTrue(activity.supportFragmentManager.findFragmentById(R.id.pageContainer) is StatisticsFragment)
            nav = activity.findViewById(R.id.bottomNavigation)
            nav.selectedItemId = R.id.nav_home; idle()
            assertTrue(activity.supportFragmentManager.findFragmentById(R.id.pageContainer) is HomeFragment)
            activity.selectBill(123); idle()
            val bills = activity.supportFragmentManager.findFragmentById(R.id.pageContainer) as TransactionsFragment
            assertEquals(123L, bills.arguments!!.getLong("select_id"))
            nav.selectedItemId = R.id.nav_settings; idle()
            assertTrue(activity.supportFragmentManager.findFragmentById(R.id.pageContainer) is SettingsFragment)
            assertEquals(0, activity.supportFragmentManager.backStackEntryCount)
            nav.selectedItemId = R.id.nav_home; idle()
            activity.findViewById<View>(R.id.viewAllTransactions).performClick(); idle()
            assertTrue(activity.supportFragmentManager.findFragmentById(R.id.pageContainer) is TransactionsFragment)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun disconnectedListenerPromptsAfterGraceAndRecoveryHidesPrompt() {
        setAccess(true)
        val serviceController = Robolectric.buildService(PaymentNotificationService::class.java).create()
        val service = serviceController.get()
        service.onListenerDisconnected()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val banner = activity.findViewById<View>(R.id.accessBanner)
            assertEquals(View.GONE, banner.visibility)
            idle(6_000)
            assertEquals(View.VISIBLE, banner.visibility)
            assertTrue(activity.findViewById<TextView>(R.id.accessMessage).text.toString().contains("关闭再开启"))
            assertEquals("重新开启", activity.findViewById<TextView>(R.id.enableAutomatic).text.toString())
            activity.findViewById<View>(R.id.enableAutomatic).performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(if (android.os.Build.VERSION.SDK_INT >= 30) Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS else Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, intent.action)
            service.onListenerConnected(); idle(2_000)
            assertEquals(View.GONE, banner.visibility)
            service.onListenerDisconnected(); idle(8_000)
            assertEquals(View.VISIBLE, banner.visibility)
            setAccess(false); idle(2_000)
            assertTrue(activity.findViewById<TextView>(R.id.accessMessage).text.toString().contains("允许通知读取"))
            assertEquals("去开启", activity.findViewById<TextView>(R.id.enableAutomatic).text.toString())
            service.onListenerConnected(); idle(2_000)
            assertEquals("revoked access still needs prompt", View.VISIBLE, banner.visibility)
            setAccess(true); idle(2_000)
            assertEquals(View.GONE, banner.visibility)
        } finally { controller.pause().stop().destroy(); serviceController.destroy() }
    }

    private fun setAccess(enabled: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners",
            if (enabled) ComponentName(app, PaymentNotificationService::class.java).flattenToString() else "")
    }
    private fun idle(milliseconds: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
}
