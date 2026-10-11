package com.example.lightledger.notification

import android.app.Activity
import android.app.Notification
import android.app.Service
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.LedgerDatabase
import com.example.lightledger.ui.MainActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNotificationListenerService
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.lang.reflect.Modifier
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = LedgerApp::class, sdk = [26, 29, 35], qualifiers = "w360dp-h760dp-xxhdpi", shadows = [ModernBindingShadow::class])
class ListenerRecovery092Test {
    private val app get() = RuntimeEnvironment.getApplication() as LedgerApp
    private val jobs get() = app.getSystemService(JobScheduler::class.java)
    @Before fun prepare() {
        setAccess(false)
        ListenerProtection.disable(app)
        ListenerRecovery.check(app)
        NotificationAccess.resetRebindThrottle()
        ShadowNotificationListenerService.reset()
        ModernBindingShadow.rejectUnbind = false
        ModernBindingShadow.unbinds.clear()
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        ViewModelProvider.AndroidViewModelFactory::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == ViewModelProvider.AndroidViewModelFactory::class.java }
            .forEach { it.isAccessible = true; it.set(null, null) }
        app.getSharedPreferences("permission_guide", 0).edit().putBoolean("shown", true).apply()
    }
    @After fun close() {
        setAccess(false); ListenerProtection.disable(app); ListenerRecovery.check(app)
        LedgerDatabase.get(app).close()
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
    }

    @Test fun idleRecoveryDoesNotDependOnActivityAndDoesNotPostponeExistingJobs() {
        setAccess(true)
        ListenerRecovery.check(app)
        val watch = jobs.getPendingJob(ListenerRecovery.WATCH_JOB)!!
        val retry = jobs.getPendingJob(ListenerRecovery.RETRY_JOB)!!
        assertTrue(watch.isPersisted); assertTrue(watch.isPeriodic)
        assertEquals(15 * 60_000L, watch.intervalMillis)
        assertTrue(retry.isPersisted); assertFalse(retry.isPeriodic)
        assertEquals(30_000L, retry.minLatencyMillis)
        assertEquals(1, ShadowNotificationListenerService.getRebindRequestCount())
        repeat(20) { ListenerRecovery.check(app) }
        assertSame(watch, jobs.getPendingJob(ListenerRecovery.WATCH_JOB))
        assertSame(retry, jobs.getPendingJob(ListenerRecovery.RETRY_JOB))
        assertEquals(1, ShadowNotificationListenerService.getRebindRequestCount())
        // No Activity is running, yet subsequent attempts continue with backoff.
        idle(120_000)
        assertTrue(ShadowNotificationListenerService.getRebindRequestCount() >= 3)
        val controller = Robolectric.buildService(PaymentNotificationService::class.java).create()
        try {
            controller.get().onListenerConnected()
            assertNull(jobs.getPendingJob(ListenerRecovery.RETRY_JOB))
            assertNotNull(jobs.getPendingJob(ListenerRecovery.WATCH_JOB))
            val count = ShadowNotificationListenerService.getRebindRequestCount()
            idle(300_000)
            assertEquals(count, ShadowNotificationListenerService.getRebindRequestCount())
        } finally { controller.destroy() }
        setAccess(false); ListenerRecovery.check(app)
        assertTrue(jobs.allPendingJobs.isEmpty())
        val count = ShadowNotificationListenerService.getRebindRequestCount()
        idle(300_000); assertEquals(count, ShadowNotificationListenerService.getRebindRequestCount())
    }

    @Test fun persistedRetryJobReschedulesOnlyWhilePermissionGrantedAndDisconnected() {
        setAccess(true)
        val controller = Robolectric.buildService(ListenerRecoveryJob::class.java).create()
        val params = Shadow.newInstanceOf(JobParameters::class.java)
        ReflectionHelpers.setField(params, "jobId", ListenerRecovery.RETRY_JOB)
        try {
            assertTrue(controller.get().onStartJob(params)); idle(1)
            assertTrue(shadowOf(controller.get()).isJobFinished)
            assertTrue(shadowOf(controller.get()).isRescheduleNeeded)
            val listener = Robolectric.buildService(PaymentNotificationService::class.java).create()
            try {
                listener.get().onListenerConnected()
                controller.get().onStartJob(params); idle(1)
                assertFalse(shadowOf(controller.get()).isRescheduleNeeded)
                assertFalse(controller.get().onStopJob(params))
            } finally { listener.destroy() }
            setAccess(false)
            controller.get().onStartJob(params); idle(1)
            assertFalse(shadowOf(controller.get()).isRescheduleNeeded)
            assertFalse(controller.get().onStopJob(params))
            assertTrue(jobs.allPendingJobs.isEmpty())
        } finally { controller.destroy() }
    }

    @Test fun oldListenerDestructionCannotClearReplacementConnection() {
        setAccess(true)
        val old = Robolectric.buildService(PaymentNotificationService::class.java).create()
        val replacement = Robolectric.buildService(PaymentNotificationService::class.java).create()
        try {
            old.get().onListenerConnected()
            replacement.get().onListenerConnected()
            old.get().onListenerDisconnected(); old.destroy()
            assertTrue(PaymentNotificationService.isConnected)
            assertNull(jobs.getPendingJob(ListenerRecovery.RETRY_JOB))
        } finally { replacement.destroy() }
        assertFalse(PaymentNotificationService.isConnected)
        assertNotNull(jobs.getPendingJob(ListenerRecovery.RETRY_JOB))
    }

    @Test fun reconnectReplaysRetainedWechatAndAlipayNotificationsWithoutDuplicates() {
        setAccess(true)
        val controller = Robolectric.buildService(PaymentNotificationService::class.java).create()
        val listener = controller.get()
        try {
            shadowOf(listener).addActiveNotification("com.tencent.mm", 1,
                Notification.Builder(app, "test").setContentTitle("微信支付").setContentText("支付成功，支付金额￥12.34，收款方：虚构商店").build())
            shadowOf(listener).addActiveNotification("com.eg.android.AlipayGphone", 2,
                Notification.Builder(app, "test").setContentTitle("支付宝").setContentText("付款成功，金额￥5.67，商户：虚构小店").build())
            shadowOf(listener).addActiveNotification("com.tencent.mm", 3,
                Notification.Builder(app, "test").setContentTitle("微信支付").setContentText("昨日记账日报，点击查看详情").build())
            listener.onListenerConnected()
            await { runBlocking { app.repository.snapshot().transactions.size == 2 } }
            listener.onListenerDisconnected(); listener.onListenerConnected()
            assertTrue(PaymentNotificationService.inspectCurrent())
            // Wait for all queued duplicate writes, not a timing-dependent snapshot.
            runBlocking { app.notificationScope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().forEach { it.join() } }
            assertEquals(listOf(567L, 1234L), runBlocking { app.repository.snapshot().transactions.map { it.amountCents }.sorted() })
        } finally { controller.destroy() }
    }

    @Test fun listenerDestructionDoesNotCancelPaymentSaveAlreadyQueued() {
        setAccess(true)
        val controller = Robolectric.buildService(PaymentNotificationService::class.java).create()
        val listener = controller.get()
        val key = shadowOf(listener).addActiveNotification("com.tencent.mm", 4,
            Notification.Builder(app, "test").setContentTitle("微信支付").setContentText("支付成功，支付金额￥8.00，收款方：虚构商店").build())
        listener.onNotificationPosted(listener.activeNotifications.single { it.key == key })
        controller.destroy()
        await { runBlocking { app.repository.snapshot().transactions.size == 1 } }
        assertEquals(800L, runBlocking { app.repository.snapshot().transactions.single().amountCents })
    }

    @Test fun bootAndUpgradeRecoverJobsAndOnlyRestartOptedInProtection() {
        setAccess(true)
        val receiver = ListenerRestartReceiver()
        receiver.onReceive(app, Intent("unrelated"))
        assertTrue(jobs.allPendingJobs.isEmpty())
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNotNull(jobs.getPendingJob(ListenerRecovery.WATCH_JOB))
        assertNull(shadowOf(app).nextStartedService)
        app.getSharedPreferences("listener_protection", 0).edit().putBoolean("enabled", true).apply()
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(ComponentName(app, ListenerProtectionService::class.java), shadowOf(app).nextStartedService.component)
        setAccess(false); receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(jobs.allPendingJobs.isEmpty())
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun optionalProtectionHasSilentVisibleStatusStopsWhenDisabledAndNeverPretendsConnected() {
        setAccess(true)
        assertFalse(ListenerProtection.isEnabled(app))
        assertTrue(ListenerProtection.enable(app))
        val controller = Robolectric.buildService(ListenerProtectionService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
            assertTrue(ListenerProtectionService.isRunning)
            assertFalse(PaymentNotificationService.isConnected)
            val notification = shadowOf(service).lastForegroundNotification
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue(notification.extras.getString(Notification.EXTRA_TEXT)!!.contains("正在检查监听连接"))
            val channel = app.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(ListenerProtectionService.CHANNEL_ID)
            assertEquals(android.app.NotificationManager.IMPORTANCE_LOW, channel.importance)
            val listener = Robolectric.buildService(PaymentNotificationService::class.java).create()
            try {
                listener.get().onListenerConnected(); idle(30_000)
                val updated = shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).getNotification(ListenerProtectionService.NOTIFICATION_ID)
                assertTrue(updated.extras.getString(Notification.EXTRA_TEXT)!!.contains("监听已连接"))
            } finally { listener.destroy() }
            ListenerProtection.disable(app)
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally { controller.destroy() }
        assertFalse(ListenerProtectionService.isRunning)
        assertTrue(shadowOf(controller.get()).isForegroundStopped)
    }

    class RejectingBatteryActivity : Activity() {
        val launched = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            launched.add(intent)
            if (intent.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS) throw ActivityNotFoundException()
        }
    }
    @Test fun permissionChecksExactListenerAndBatteryDialogTargetsAppWithFallback() {
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", ComponentName(app.packageName, "OtherListener").flattenToString())
        assertFalse(NotificationAccess.isEnabled(app))
        setAccess(true); assertTrue(NotificationAccess.isEnabled(app))
        assertEquals("package:${app.packageName}", NotificationAccess.batteryIntent(app).data.toString())
        val controller = Robolectric.buildActivity(RejectingBatteryActivity::class.java).setup()
        try {
            NotificationAccess.requestBatteryExemption(controller.get())
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, controller.get().launched.last().action)
            assertEquals("package:${app.packageName}", controller.get().launched.last().data.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(sdk = [29, 35])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun settingsExposeProtectionWithoutAddingItemsToDataManagement() {
        setAccess(true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.nav_settings
            activity.supportFragmentManager.executePendingTransactions()
            assertFalse(activity.findViewById<SwitchMaterial>(R.id.backgroundProtection).isChecked)
            assertTrue(activity.findViewById<TextView>(R.id.notificationStatus).text.toString().contains("正在检查连接"))
            val protection = activity.findViewById<SwitchMaterial>(R.id.backgroundProtection)
            assertTrue(protection.isEnabled)
            val data = activity.findViewById<View>(R.id.exportBackup).parent as ViewGroup
            assertEquals(2, data.childCount)
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val metrics = root.resources.displayMetrics
            fun force(view: View) { view.forceLayout(); if (view is ViewGroup) (0 until view.childCount).forEach { force(view.getChildAt(it)) } }
            repeat(3) {
                force(root)
                root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            }
            val scroll = activity.supportFragmentManager.findFragmentById(R.id.pageContainer)!!.requireView() as android.widget.ScrollView
            scroll.scrollTo(0, scroll.getChildAt(0).height)
            assertTrue(protection.width > 0)
            System.getProperty("lightledger.preview.dir")?.let { directory ->
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(directory, "LightLedger-0.9.3-settings-api${RuntimeEnvironment.getApiLevel()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun setAccess(enabled: Boolean) { Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", if (enabled) ComponentName(app, PaymentNotificationService::class.java).flattenToString() else "") }
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun await(ready: () -> Boolean) { val end = System.nanoTime() + 15_000_000_000; while (System.nanoTime() < end) { idle(1); if (ready()) return; Thread.sleep(10) }; fail("Notification persistence timed out") }
}
