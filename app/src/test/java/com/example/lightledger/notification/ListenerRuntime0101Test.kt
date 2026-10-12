package com.example.lightledger.notification

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.LedgerDatabase
import com.example.lightledger.data.PaymentMethod
import com.example.lightledger.ui.MainActivity
import com.example.lightledger.ui.TransactionEditorActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
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
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowNotificationListenerService
import java.lang.reflect.Modifier
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** RPC/lifecycle simulation, not an OEM process-kill or physical Binder test. */
@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = LedgerApp::class, sdk = [26, 29, 35], shadows = [ModernBindingShadow::class])
class ListenerRuntime0101Test {
    private val app get() = RuntimeEnvironment.getApplication() as LedgerApp
    private val authority get() = "${app.packageName}.listener-runtime"
    private val connected = ListenerRuntime.State(available = true, connected = true, checking = false,
        protectionEnabled = true, protectionRunning = true, paymentMethod = PaymentMethod.ALIPAY)

    @Before fun prepare() {
        ListenerRuntime.processNameReader = null
        resetCache()
        access(false); ListenerProtection.disable(app); ListenerRecovery.check(app)
        ShadowNotificationListenerService.reset()
        ModernBindingShadow.unbinds.clear()
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        ViewModelProvider.AndroidViewModelFactory::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == ViewModelProvider.AndroidViewModelFactory::class.java }
            .forEach { it.isAccessible = true; it.set(null, null) }
        app.getSharedPreferences("permission_guide", 0).edit().putBoolean("shown", true).commit()
    }

    @After fun close() {
        ListenerRuntime.processNameReader = null
        access(false); ListenerProtection.disable(app); ListenerRecovery.check(app)
        LedgerDatabase.get(app).close()
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
    }

    @Test fun activitiesAreSeparateAndListenerIdentityAndPrivateBridgeArePreserved() {
        val info = app.packageManager.getPackageInfo(app.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS)
        assertEquals(12, info.activities!!.count { it.name.startsWith("${app.packageName}.ui.") })
        assertTrue(info.activities!!.all { it.processName == "${app.packageName}:ui" })
        val listener = info.services!!.single { it.name == PaymentNotificationService::class.java.name }
        assertEquals(app.packageName, listener.processName)
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", listener.permission)
        assertTrue(info.receivers!!.all { it.processName == app.packageName })
        val bridge = info.providers!!.single { it.name == ListenerRuntimeProvider::class.java.name }
        assertEquals(authority, bridge.authority)
        assertEquals(app.packageName, bridge.processName)
        assertFalse(bridge.exported)
        assertEquals("${app.packageName}:ui", info.providers!!.single { it.name.endsWith("MlKitInitProvider") }.processName)
        assertEquals("${app.packageName}:ui", info.providers!!.single { it.name == "androidx.startup.InitializationProvider" }.processName)
        ListenerRuntime.processNameReader = { "${app.packageName}:ui" }
        assertFalse(ListenerRuntime.ownsListener(app))
        ListenerRuntime.processNameReader = { app.packageName }
        assertTrue(ListenerRuntime.ownsListener(app))
    }

    @Test fun privateBridgeReportsOwnerAndHealthyRepairDoesNotRebind() {
        access(true)
        val listener = Robolectric.buildService(PaymentNotificationService::class.java).create()
        listener.get().onListenerConnected()
        try {
            val provider = Robolectric.buildContentProvider(ListenerRuntimeProvider::class.java).create(authority).get()
            val before = ShadowNotificationListenerService.getRebindRequestCount()
            assertTrue(ListenerRuntime.State.from(provider.call("state", null, null)!!).connected)
            assertTrue(ListenerRuntime.State.from(provider.call("repair", null, null)!!).connected)
            assertEquals(before, ShadowNotificationListenerService.getRebindRequestCount())
            assertTrue(provider.call("enable", null, null)!!.getBoolean("started"))
            assertTrue(ListenerProtection.isEnabled(app))
            provider.call("disable", null, null)
            assertFalse(ListenerProtection.isEnabled(app))
            assertThrows(IllegalArgumentException::class.java) { provider.call("unexpected", null, null) }
        } finally { listener.destroy() }
    }

    @Test fun foregroundStartRequestIsKeptWhileStartupCheckIsInFlight() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val methods = CopyOnWriteArrayList<String>()
        fakeBridge { method ->
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            methods += method
            if (method == "check") { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            connected.bundle()
        }
        ListenerRuntime.check(app)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        ListenerRuntime.check(app, startProtection = true)
        release.countDown()
        await { methods.contains("check_start") && ListenerRuntime.state(app).connected }
        assertEquals(listOf("check", "check_start"), methods.toList())
        assertEquals(PaymentMethod.ALIPAY, DetectedPaymentMethod.latest(app))
    }

    @Test fun expiredOrFailedBridgeCannotKeepShowingCachedConnected() {
        val responds = AtomicBoolean(true)
        fakeBridge { if (responds.get()) connected.bundle() else null }
        ListenerRuntime.check(app); await { ListenerRuntime.state(app).connected }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertFalse(ListenerRuntime.state(app).available)
        assertFalse(ListenerRuntime.state(app).connected)
        responds.set(false)
        ListenerRuntime.check(app); await { !isChecking() }
        assertFalse(ListenerRuntime.state(app).connected)
    }

    @Test fun unavailableRuntimeShowsBackgroundAdviceAfterGracePeriod() {
        access(true)
        fakeBridge { null }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            await { !isChecking() }
            assertEquals(View.GONE, activity.get().findViewById<View>(R.id.accessBanner).visibility)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
            assertEquals(View.VISIBLE, activity.get().findViewById<View>(R.id.accessBanner).visibility)
            assertTrue(activity.get().findViewById<TextView>(R.id.accessMessage).text.contains("后台状态暂不可读"))
            assertTrue(ListenerRuntime.state(app).checking)
        } finally { activity.pause().stop().destroy(); await { !isChecking() } }
    }

    @Test fun slowBinderDoesNotHoldEditorWaitPastItsDeadline() {
        val release = CountDownLatch(1)
        fakeBridge { release.await(5, TimeUnit.SECONDS); connected.bundle() }
        val start = System.nanoTime()
        try {
            assertNull(runBlocking { ListenerRuntime.latestPayment(app) })
            assertTrue("IPC wait must be bounded", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_500)
        } finally { release.countDown() }
    }

    @Test fun latePaymentDefaultDoesNotReplaceManualSelection() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fakeBridge { entered.countDown(); release.await(5, TimeUnit.SECONDS); connected.bundle() }
        val controller = Robolectric.buildActivity(TransactionEditorActivity::class.java).setup()
        try {
            await { entered.count == 0L }
            val group = controller.get().findViewById<ChipGroup>(R.id.paymentMethodChips)
            (0 until group.childCount).map { group.getChildAt(it) as Chip }.single { it.text == "现金" }.performClick()
            release.countDown()
            await { controller.get().findViewById<View>(R.id.save).isEnabled }
            assertEquals("现金", group.findViewById<Chip>(group.checkedChipId).text)
        } finally { release.countDown(); controller.pause().stop().destroy() }
    }

    @Test fun coldEditorReadsPaymentMethodFromOwner() {
        fakeBridge { connected.bundle() }
        val controller = Robolectric.buildActivity(TransactionEditorActivity::class.java).setup()
        try {
            await { controller.get().findViewById<View>(R.id.save).isEnabled }
            val group = controller.get().findViewById<ChipGroup>(R.id.paymentMethodChips)
            assertEquals("支付宝", group.findViewById<Chip>(group.checkedChipId).text)
        } finally { controller.pause().stop().destroy() }
    }

    private fun fakeBridge(reply: (String) -> Bundle?) {
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun call(method: String, arg: String?, extras: Bundle?) = reply(method)
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(app, ProviderInfo().apply { authority = this@ListenerRuntime0101Test.authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        ListenerRuntime.processNameReader = { "${app.packageName}:ui" }
    }
    private fun access(enabled: Boolean) = Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners",
        if (enabled) ComponentName(app, PaymentNotificationService::class.java).flattenToString() else "")
    private fun isChecking() = (field("checking").get(ListenerRuntime) as AtomicBoolean).get()
    private fun field(name: String) = ListenerRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun resetCache() {
        field("cached").set(ListenerRuntime, ListenerRuntime.State())
        field("updatedAt").setLong(ListenerRuntime, 0)
        (field("checking").get(ListenerRuntime) as AtomicBoolean).set(false)
        (field("startRequested").get(ListenerRuntime) as AtomicBoolean).set(false)
    }
    private fun await(condition: () -> Boolean) {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for UI/RPC work")
    }
}
