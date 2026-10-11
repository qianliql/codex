package com.example.lightledger.notification

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.example.lightledger.LedgerApp
import com.example.lightledger.data.LedgerDatabase
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder
import org.robolectric.shadows.ShadowNotificationListenerService
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = LedgerApp::class, sdk = [26, 29, 35], shadows = [ModernBindingShadow::class])
class ProcessRecovery094Test {
    private val app get() = RuntimeEnvironment.getApplication() as LedgerApp
    private val jobs get() = app.getSystemService(JobScheduler::class.java)
    private val prefs get() = app.getSharedPreferences("listener_recovery", 0)
    @Before fun prepare() {
        access(false); ListenerProtection.disable(app); ListenerRecovery.check(app)
        prefs.edit().clear().commit()
        app.getSharedPreferences("process_exit_diagnostics", 0).edit().clear().commit()
        ShadowNotificationListenerService.reset()
        NotificationAccess.resetRebindThrottle()
        ModernBindingShadow.rejectUnbind = false; ModernBindingShadow.unbinds.clear()
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
    }
    @After fun close() {
        access(false); ListenerProtection.disable(app); ListenerRecovery.check(app)
        LedgerDatabase.get(app).close()
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
    }

    @Test fun taskRemovalLeavesDurableCheckEvenWhenListenerIsConnected() {
        access(true)
        val listener = Robolectric.buildService(PaymentNotificationService::class.java).create()
        listener.get().onListenerConnected()
        ListenerProtection.enable(app)
        val protection = Robolectric.buildService(ListenerProtectionService::class.java).create()
        protection.get().onStartCommand(null, 0, 1)
        try {
            protection.get().onTaskRemoved(Intent())
            val check = jobs.getPendingJob(ListenerRecovery.TASK_REMOVED_JOB)!!
            assertTrue(check.isPersisted); assertFalse(check.isPeriodic)
            assertEquals(30_000L, check.minLatencyMillis)
            assertNull(jobs.getPendingJob(ListenerRecovery.RETRY_JOB))
            repeat(10) { ListenerRecovery.check(app) }
            assertSame(check, jobs.getPendingJob(ListenerRecovery.TASK_REMOVED_JOB))
            assertTrue(ProcessExitDiagnostics.summary(app).contains("最近任务移除回调"))
            val task = Robolectric.buildService(ListenerRecoveryJob::class.java).create()
            try {
                task.get().onStartJob(params()); idle()
                assertFalse(shadowOf(task.get()).isRescheduleNeeded)
                assertFalse(task.get().onStopJob(params()))
            } finally { task.destroy() }
            ListenerProtection.disable(app)
            assertNull(jobs.getPendingJob(ListenerRecovery.TASK_REMOVED_JOB))
        } finally { protection.destroy(); listener.destroy() }
    }

    @Test fun oldWaitingTimeStillTriggersRepairAfterInMemoryRecoveryStateIsLost() {
        access(true); ListenerRecovery.check(app)
        val started = System.currentTimeMillis() - 70_000
        prefs.edit().putLong("waiting_started", started).commit()
        // Emulates process-local state loss; the saved wall-time remains in SharedPreferences.
        ReflectionHelpers.getField<Handler>(ListenerRecovery, "handler").removeCallbacksAndMessages(null)
        ReflectionHelpers.setField(ListenerRecovery, "retry", null)
        ReflectionHelpers.setField(ListenerRecovery, "waitingSince", null)
        NotificationAccess.resetRebindThrottle()
        val task = Robolectric.buildService(ListenerRecoveryJob::class.java).create()
        try {
            task.get().onStartJob(params()); idle()
            assertTrue(prefs.getLong("last_reset", 0) > 0)
            assertEquals(started, prefs.getLong("waiting_started", 0))
            assertTrue(ShadowNotificationListenerService.getRebindRequestCount() >= 2)
            assertNotNull(jobs.getPendingJob(ListenerRecovery.RETRY_JOB))
            assertFalse(shadowOf(task.get()).isRescheduleNeeded)
        } finally { task.destroy() }
        val listener = Robolectric.buildService(PaymentNotificationService::class.java).create()
        try { listener.get().onListenerConnected(); assertFalse(prefs.contains("waiting_started")) }
        finally { listener.destroy() }
    }

    @Test fun clockRollbackStartsNewWaitAndRevocationClearsWaitingAndAllJobs() {
        access(true)
        prefs.edit().putLong("waiting_started", System.currentTimeMillis() + 86_400_000).commit()
        ListenerRecovery.check(app)
        assertEquals(0L, prefs.getLong("last_reset", 0))
        assertTrue(prefs.getLong("waiting_started", 0) <= System.currentTimeMillis())
        app.getSharedPreferences("listener_protection", 0).edit().putBoolean("enabled", true).apply()
        assertTrue(ListenerRecovery.afterTaskRemoved(app))
        access(false); ListenerRecovery.check(app)
        assertFalse(prefs.contains("waiting_started"))
        assertTrue(jobs.allPendingJobs.isEmpty())
        assertFalse(ListenerRecovery.afterTaskRemoved(app))
    }

    @Test fun taskCheckConvertsToRetryWithoutStartingForegroundProtectionFromBackground() {
        access(true)
        app.getSharedPreferences("listener_protection", 0).edit().putBoolean("enabled", true).apply()
        ListenerRecovery.afterTaskRemoved(app)
        val task = Robolectric.buildService(ListenerRecoveryJob::class.java).create()
        try {
            task.get().onStartJob(params()); idle()
            assertTrue(shadowOf(task.get()).isJobFinished)
            assertFalse(shadowOf(task.get()).isRescheduleNeeded)
            assertNotNull(jobs.getPendingJob(ListenerRecovery.RETRY_JOB))
            assertNull(shadowOf(app).nextStartedService)
            assertTrue(task.get().onStopJob(params()))
        } finally { task.destroy() }
    }

    @Test @Config(sdk = [26, 29]) fun olderAndroidUsesLifecycleEvidenceWithoutClaimingExitReason() {
        ProcessExitDiagnostics.capture(app)
        ProcessExitDiagnostics.taskRemoved(app)
        ProcessExitDiagnostics.protection(app, true)
        assertTrue(ProcessExitDiagnostics.summary(app).contains("此系统不提供进程退出原因"))
        assertTrue(ProcessExitDiagnostics.summary(app).contains("最近保护服务启动"))
        ProcessExitDiagnostics.protection(app, false)
        assertTrue(ProcessExitDiagnostics.summary(app).contains("最近保护服务结束"))
    }

    @Test @Config(sdk = [30, 35]) fun capturesLatestOwnProcessReasonWithoutDescriptionOrTrace() {
        val manager = shadowOf(app.getSystemService(ActivityManager::class.java))
        val now = System.currentTimeMillis()
        manager.addApplicationExitInfo(ApplicationExitInfoBuilder.newBuilder()
            .setProcessName(app.applicationInfo.processName).setTimestamp(now - 2000)
            .setReason(ApplicationExitInfo.REASON_CRASH).build())
        manager.addApplicationExitInfo(ApplicationExitInfoBuilder.newBuilder()
            .setProcessName(app.applicationInfo.processName).setTimestamp(now - 1000)
            .setReason(ApplicationExitInfo.REASON_LOW_MEMORY).setDescription("虚构敏感描述，不应复制").build())
        manager.addApplicationExitInfo(ApplicationExitInfoBuilder.newBuilder()
            .setProcessName(app.applicationInfo.processName + ":unrelated").setTimestamp(now)
            .setReason(ApplicationExitInfo.REASON_ANR).build())
        ProcessExitDiagnostics.capture(app)
        val summary = ProcessExitDiagnostics.summary(app)
        assertTrue(summary.contains("系统内存不足回收"))
        assertFalse(summary.contains("应用无响应")); assertFalse(summary.contains("虚构敏感描述"))
        assertEquals(setOf("exit_time", "exit_reason"), app.getSharedPreferences("process_exit_diagnostics", 0).all.keys)
    }

    @Test @Config(sdk = [30, 35]) fun unavailableNewHistoryKeepsPreviouslyCapturedEvidence() {
        app.getSharedPreferences("process_exit_diagnostics", 0).edit()
            .putLong("exit_time", System.currentTimeMillis()).putInt("exit_reason", ApplicationExitInfo.REASON_ANR).commit()
        ProcessExitDiagnostics.capture(app)
        assertTrue(ProcessExitDiagnostics.summary(app).contains("应用无响应"))
    }

    @Test @Config(sdk = [30, 35]) fun userRequestedReasonNeverClaimsToDistinguishSwipeAndForceStop() {
        val reason = ProcessExitDiagnostics.reasonLabel(ApplicationExitInfo.REASON_USER_REQUESTED)
        assertTrue(reason.contains("不能区分最近任务与强行停止"))
        if (RuntimeEnvironment.getApiLevel() < 34) assertTrue(reason.contains("更新或组件变化"))
        else assertFalse(reason.contains("更新或组件变化"))
    }

    private fun access(enabled: Boolean) = Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", if (enabled) ComponentName(app, PaymentNotificationService::class.java).flattenToString() else "")
    private fun params(): JobParameters = Shadow.newInstanceOf(JobParameters::class.java).apply { ReflectionHelpers.setField(this, "jobId", ListenerRecovery.TASK_REMOVED_JOB) }
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
}
