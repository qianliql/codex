package com.example.lightledger.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import androidx.lifecycle.ViewModelProvider
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.LedgerDatabase
import com.example.lightledger.data.TransactionType
import com.example.lightledger.ui.MainActivity
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationListenerService
import java.lang.reflect.Modifier
import java.time.Duration

/** Checks component lifecycle and synthetic payments; does not emulate OEM process cleaning. */
@RunWith(RobolectricTestRunner::class)
@Config(application = LedgerApp::class, sdk = [26, 29, 35], qualifiers = "w360dp-h760dp-xxhdpi", shadows = [ModernBindingShadow::class])
class ListenerContinuity093Test {
    private val app get() = RuntimeEnvironment.getApplication() as LedgerApp
    private val component get() = ComponentName(app, PaymentNotificationService::class.java)
    @Before fun prepare() {
        access(false)
        ListenerProtection.disable(app)
        ListenerRecovery.check(app)
        ShadowNotificationListenerService.reset()
        ModernBindingShadow.unbinds.clear()
        ModernBindingShadow.rejectUnbind = false
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        ViewModelProvider.AndroidViewModelFactory::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == ViewModelProvider.AndroidViewModelFactory::class.java }
            .forEach { it.isAccessible = true; it.set(null, null) }
        app.getSharedPreferences("permission_guide", 0).edit().putBoolean("shown", true).apply()
        app.getSharedPreferences("listener_recovery", 0).edit().clear().commit()
    }
    @After fun close() {
        access(false); ListenerProtection.disable(app); ListenerRecovery.check(app)
        runBlocking { app.notificationScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
        LedgerDatabase.get(app).close()
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
    }

    @Test fun taskRemovalKeepsProtectionAndSavesPaymentsAndTransferWithoutActivity() {
        access(true)
        val listener = Robolectric.buildService(PaymentNotificationService::class.java).create()
        listener.get().onListenerConnected()
        ListenerProtection.enable(app)
        val protection = Robolectric.buildService(ListenerProtectionService::class.java).create()
        protection.get().onStartCommand(null, 0, 1)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity.pause().stop().destroy()
        try {
            protection.get().onTaskRemoved(Intent(app, MainActivity::class.java))
            assertTrue(ListenerProtectionService.isRunning)
            assertFalse(shadowOf(protection.get()).isStoppedBySelf)
            assertFalse(shadowOf(protection.get()).isForegroundStopped)
            assertTrue(PaymentNotificationService.isConnected)
            val manager = app.packageManager.getServiceInfo(ComponentName(app, ListenerProtectionService::class.java), 0)
            assertEquals(0, manager.flags and android.content.pm.ServiceInfo.FLAG_STOP_WITH_TASK)
            val service = listener.get()
            listOf(
                Triple("com.tencent.mm", "微信支付", "支付成功，支付金额￥12.34，收款方：虚构商店"),
                Triple("com.eg.android.AlipayGphone", "支付宝", "付款成功，金额￥5.67，商户：虚构小店"),
                Triple("com.tencent.mm", "微信转账", "转账成功，转账金额：20.00")
            ).forEachIndexed { index, (pkg, title, text) ->
                val key = shadowOf(service).addActiveNotification(pkg, index + 10,
                    Notification.Builder(app, "test").setContentTitle(title).setContentText(text).build())
                service.onNotificationPosted(service.activeNotifications.single { it.key == key })
            }
            drainWrites()
            val bills = runBlocking { app.repository.snapshot().transactions }
            assertEquals(listOf(567L, 1234L, 2000L), bills.map { it.amountCents }.sorted())
            assertEquals(2, bills.count { it.type == TransactionType.EXPENSE })
            assertEquals(1, bills.count { it.type == TransactionType.TRANSFER })
            service.onListenerDisconnected(); service.onListenerConnected(); drainWrites()
            assertEquals(3, runBlocking { app.repository.snapshot().transactions.size })
        } finally { protection.destroy(); listener.destroy() }
    }

    @Test fun reopeningConnectedAppDoesNotResetListenerOrShowReconnectBanner() {
        access(true)
        val listener = Robolectric.buildService(PaymentNotificationService::class.java).create()
        listener.get().onListenerConnected()
        val before = ShadowNotificationListenerService.getRebindRequestCount()
        try {
            repeat(3) {
                val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
                try {
                    idle(20_000)
                    assertEquals(View.GONE, activity.get().findViewById<View>(R.id.accessBanner).visibility)
                } finally { activity.pause().stop().destroy() }
            }
            assertEquals(before, ShadowNotificationListenerService.getRebindRequestCount())
            assertTrue(ModernBindingShadow.unbinds.isEmpty())
            assertEquals(0L, app.getSharedPreferences("listener_recovery", 0).getLong("last_reset", 0))
        } finally { listener.destroy() }
    }

    @Test fun coldConnectionChecksBeforeReportingDisconnectedAndCallbackClearsBanner() {
        access(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        val listener = Robolectric.buildService(PaymentNotificationService::class.java).create()
        try {
            assertTrue(ListenerRecovery.checkingInitialConnection)
            assertTrue(NotificationDiagnostics.summary(app).startsWith("正在检查"))
            idle(10_000)
            assertEquals(View.GONE, activity.get().findViewById<View>(R.id.accessBanner).visibility)
            idle(6_000)
            assertFalse(ListenerRecovery.checkingInitialConnection)
            assertEquals(View.VISIBLE, activity.get().findViewById<View>(R.id.accessBanner).visibility)
            listener.get().onListenerConnected(); idle(2_000)
            assertEquals(View.GONE, activity.get().findViewById<View>(R.id.accessBanner).visibility)
        } finally { activity.pause().stop().destroy(); listener.destroy() }
    }

    @Test fun swipingStatusNotificationDoesNotDisableProtectionOrStopPaymentSaving() {
        access(true)
        val listener = Robolectric.buildService(PaymentNotificationService::class.java).create()
        listener.get().onListenerConnected()
        ListenerProtection.enable(app)
        val protection = Robolectric.buildService(ListenerProtectionService::class.java).create()
        protection.get().onStartCommand(null, 0, 1)
        try {
            val receiver = app.packageManager.getReceiverInfo(ComponentName(app, ProtectionNotificationReceiver::class.java), 0)
            assertFalse(receiver.exported)
            val notification = shadowOf(protection.get()).lastForegroundNotification
            assertNotNull(notification.deleteIntent)
            app.getSystemService(android.app.NotificationManager::class.java).cancel(ListenerProtectionService.NOTIFICATION_ID)
            // Robolectric does not dispatch manifest receivers from this PendingIntent.
            val delete = shadowOf(notification.deleteIntent).savedIntent
            assertEquals(ComponentName(app, ProtectionNotificationReceiver::class.java), delete.component)
            assertEquals(ProtectionNotificationReceiver.DISMISSED, delete.action)
            ProtectionNotificationReceiver().onReceive(app, delete); idle(1)
            assertTrue(ListenerProtection.isEnabled(app))
            assertTrue(ListenerProtectionService.isRunning)
            assertTrue(PaymentNotificationService.isConnected)
            assertFalse(shadowOf(protection.get()).isStoppedBySelf)
            assertTrue(NotificationDiagnostics.summary(app).contains("运行中（通知栏未显示）"))
            assertTrue(NotificationDiagnostics.summary(app).contains("状态通知已划走，后台保护仍在运行"))
            val service = listener.get()
            val key = shadowOf(service).addActiveNotification("com.tencent.mm", 31,
                Notification.Builder(app, "test").setContentTitle("微信支付").setContentText("支付成功，支付金额￥7.89，收款方：虚构商店").build())
            service.onNotificationPosted(service.activeNotifications.single { it.key == key }); drainWrites()
            assertEquals(789L, runBlocking { app.repository.snapshot().transactions.single().amountCents })
            protection.destroy()
            assertTrue(NotificationDiagnostics.summary(app).contains("后台保护：当前未运行"))
            ProtectionNotificationReceiver().onReceive(app, Intent(ProtectionNotificationReceiver.DISMISSED))
            assertTrue(NotificationDiagnostics.summary(app).contains("状态通知已划走，后台保护未运行"))
        } finally {
            if (ListenerProtectionService.isRunning) protection.destroy()
            listener.destroy()
        }
    }

    @Test fun bindingRepairKeepsNotificationGrantAndPreviousComponentState() {
        access(true)
        val grant = Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners")
        listOf(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.COMPONENT_ENABLED_STATE_ENABLED).forEach { state ->
            app.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            assertTrue(ListenerBindingRepair.reset(app))
            assertEquals(state, app.packageManager.getComponentEnabledSetting(component))
            assertEquals(grant, Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners"))
            assertFalse(app.getSharedPreferences("listener_binding_repair", 0).getBoolean("restore_pending", false))
        }
        if (RuntimeEnvironment.getApiLevel() >= 34) assertEquals(listOf(component, component), ModernBindingShadow.unbinds)
        assertEquals(2, ShadowNotificationListenerService.getRebindRequestCount())
        val listener = Robolectric.buildService(PaymentNotificationService::class.java).create()
        try { listener.get().onListenerConnected(); assertFalse(ListenerBindingRepair.reset(app)) } finally { listener.destroy() }
        access(false)
        assertFalse(ListenerBindingRepair.reset(app))
    }

    @Test fun interruptedOldAndroidComponentResetRestoresStateBeforeRecovery() {
        val prefs = app.getSharedPreferences("listener_binding_repair", 0)
        prefs.edit().putBoolean("restore_pending", true).putInt("previous_state", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT).commit()
        app.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        ListenerRecovery.check(app)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, app.packageManager.getComponentEnabledSetting(component))
        assertFalse(prefs.getBoolean("restore_pending", false))
    }

    @Test fun sustainedDisconnectionEscalatesOnceWithCooldownAndStopsAfterConnection() {
        access(true); ListenerRecovery.check(app)
        idle(59_000); ListenerRecovery.check(app)
        val prefs = app.getSharedPreferences("listener_recovery", 0)
        assertEquals(0L, prefs.getLong("last_reset", 0))
        idle(2_000); ListenerRecovery.check(app)
        val resetTime = prefs.getLong("last_reset", 0)
        assertTrue(resetTime > 0)
        val unbinds = ModernBindingShadow.unbinds.size
        repeat(20) { ListenerRecovery.check(app) }
        idle(120_000); ListenerRecovery.check(app)
        assertEquals(resetTime, prefs.getLong("last_reset", 0))
        assertEquals(unbinds, ModernBindingShadow.unbinds.size)
        // Expire the persisted wall-time cooldown without assuming Robolectric's clock offset.
        prefs.edit().putLong("last_reset", System.currentTimeMillis() - 601_000).commit()
        ListenerRecovery.check(app)
        assertTrue(prefs.getLong("last_reset", 0) > System.currentTimeMillis() - 1000)
        val listener = Robolectric.buildService(PaymentNotificationService::class.java).create()
        try {
            listener.get().onListenerConnected()
            val count = ShadowNotificationListenerService.getRebindRequestCount()
            idle(600_000); ListenerRecovery.check(app)
            assertEquals(count, ShadowNotificationListenerService.getRebindRequestCount())
        } finally { listener.destroy() }
        access(false); ListenerRecovery.check(app)
        val count = ShadowNotificationListenerService.getRebindRequestCount()
        idle(600_000)
        assertEquals(count, ShadowNotificationListenerService.getRebindRequestCount())
    }

    @Test fun disconnectedDetailsRetainProcessingResultsWithoutNotificationText() {
        access(true)
        NotificationDiagnostics.record(app, "com.tencent.mm", "已自动记账")
        NotificationDiagnostics.record(app, "com.eg.android.AlipayGphone", "没有可靠的单笔金额，已跳过")
        ListenerRecovery.disconnected(app)
        val details = NotificationDiagnostics.summary(app)
        assertTrue(details.startsWith("监听未连接"))
        assertTrue(details.contains("最近状态"))
        assertTrue(details.contains("已自动记账"))
        assertTrue(details.contains("没有可靠的单笔金额"))
        assertTrue(details.contains("最近记账"))
        assertFalse(details.contains("虚构商店"))
    }

    @Test @Config(sdk = [35]) fun rejectedResetAlsoWaitsForCooldownInsteadOfLooping() {
        ModernBindingShadow.rejectUnbind = true
        access(true); ListenerRecovery.check(app)
        idle(61_000); ListenerRecovery.check(app)
        assertEquals(1, ModernBindingShadow.unbinds.size)
        assertTrue(NotificationDiagnostics.summary(app).contains("重新绑定请求未完成"))
        repeat(20) { ListenerRecovery.check(app) }
        idle(120_000); ListenerRecovery.check(app)
        assertEquals(1, ModernBindingShadow.unbinds.size)
        assertFalse(PaymentNotificationService.isConnected)
        ModernBindingShadow.rejectUnbind = false
    }

    private fun drainWrites() = runBlocking { app.notificationScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
    private fun access(enabled: Boolean) = Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", if (enabled) component.flattenToString() else "")
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
}

/** Robolectric 4.17 lacks the API34 static unbind overload; records this public API call only. */
@Implements(NotificationListenerService::class)
class ModernBindingShadow : ShadowNotificationListenerService() {
    companion object {
        val unbinds = mutableListOf<ComponentName>()
        var rejectUnbind = false
        @JvmStatic @Implementation(minSdk = 34) fun requestUnbind(component: ComponentName) {
            unbinds.add(component)
            if (rejectUnbind) throw IllegalStateException("Synthetic system rejection")
        }
    }
}
