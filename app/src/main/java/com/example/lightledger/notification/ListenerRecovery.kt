package com.example.lightledger.notification

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.util.Log

/** Recovery outlives the Activity; jobs also survive process death and reboot. */
object ListenerRecovery {
    internal const val WATCH_JOB = 9201
    internal const val RETRY_JOB = 9202
    private val handler = Handler(Looper.getMainLooper())
    private var retry: Runnable? = null
    private var retryDelay = 5_000L
    private var waitingSince: Long? = null
    private var observedDisconnect = false
    internal val checkingInitialConnection: Boolean get() = !observedDisconnect &&
        waitingSince?.let { SystemClock.elapsedRealtime() - it < 15_000L } != false

    fun disconnected(context: Context) {
        observedDisconnect = true
        NotificationDiagnostics.lifecycle(context, "监听断开")
        NotificationAccess.resetRebindThrottle()
        check(context)
    }

    fun initialize(context: Context) {
        val app = context.applicationContext
        ListenerBindingRepair.restoreInterruptedReset(app)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { check(context) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter) // Both actions are system-protected broadcasts.
        check(app)
    }

    fun check(context: Context) {
        val app = context.applicationContext
        ListenerBindingRepair.restoreInterruptedReset(app)
        val scheduler = app.getSystemService(JobScheduler::class.java)
        if (!NotificationAccess.isEnabled(app)) {
            handler.removeCallbacksAndMessages(null)
            retry = null
            retryDelay = 5_000L
            waitingSince = null
            observedDisconnect = false
            scheduler.cancel(WATCH_JOB)
            scheduler.cancel(RETRY_JOB)
            ListenerProtection.stop(app)
            return
        }
        scheduleIfAbsent(app, WATCH_JOB, periodic = true)
        if (PaymentNotificationService.isConnected) {
            retry?.let(handler::removeCallbacks)
            retry = null
            retryDelay = 5_000L
            scheduler.cancel(RETRY_JOB)
            NotificationAccess.resetRebindThrottle()
            waitingSince = null
            observedDisconnect = false
        } else {
            val now = SystemClock.elapsedRealtime()
            if (waitingSince == null) waitingSince = now
            NotificationAccess.reconnectIfNeeded(app)
            val prefs = app.getSharedPreferences("listener_recovery", 0)
            val wall = System.currentTimeMillis()
            val lastReset = prefs.getLong("last_reset", 0L)
            if (now - waitingSince!! >= 60_000L && (lastReset == 0L || wall < lastReset || wall - lastReset >= 600_000L)) {
                // Throttle failed attempts too, so an OEM rejection cannot cause a reset loop.
                prefs.edit().putLong("last_reset", wall).apply()
                if (ListenerBindingRepair.reset(app)) {
                    NotificationDiagnostics.lifecycle(app, "已请求重新绑定监听")
                } else NotificationDiagnostics.lifecycle(app, "重新绑定请求未完成")
            }
            scheduleIfAbsent(app, RETRY_JOB, periodic = false)
            if (retry == null) {
                val task = Runnable {
                    retry = null
                    retryDelay = (retryDelay * 2).coerceAtMost(300_000L)
                    check(app)
                }
                retry = task
                handler.postDelayed(task, retryDelay)
            }
        }
    }

    private fun scheduleIfAbsent(context: Context, id: Int, periodic: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        // Replacing a periodic job on every UI check would continually postpone it.
        if (scheduler.getPendingJob(id) != null) return
        val builder = JobInfo.Builder(id, ComponentName(context, ListenerRecoveryJob::class.java))
            .setPersisted(true)
        if (periodic) builder.setPeriodic(15 * 60_000L)
        else builder.setMinimumLatency(30_000L).setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        runCatching { scheduler.schedule(builder.build()) }
            .onFailure { Log.w("ListenerRecovery", "Could not schedule recovery", it) }
    }
}

class ListenerRecoveryJob : JobService() {
    private val handler = Handler(Looper.getMainLooper())
    override fun onStartJob(params: JobParameters): Boolean {
        handler.post {
            ListenerRecovery.check(this)
            val retry = params.jobId == ListenerRecovery.RETRY_JOB &&
                NotificationAccess.isEnabled(this) && !PaymentNotificationService.isConnected
            jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        handler.removeCallbacksAndMessages(null)
        return params.jobId == ListenerRecovery.RETRY_JOB && NotificationAccess.isEnabled(this) && !PaymentNotificationService.isConnected
    }
}

class ListenerRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        ListenerRecovery.check(context)
        // These system broadcasts allow a specialUse foreground service restart.
        ListenerProtection.startIfEnabled(context)
    }
}
