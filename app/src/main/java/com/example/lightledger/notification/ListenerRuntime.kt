package com.example.lightledger.notification

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.lightledger.data.PaymentMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** UI runs in :ui; the default process owns listener state and its private preferences. */
internal object ListenerRuntime {
    data class State(
        val available: Boolean = false,
        val connected: Boolean = false,
        val checking: Boolean = true,
        val protectionEnabled: Boolean = false,
        val protectionRunning: Boolean = false,
        val details: String = "正在获取后台监听状态",
        val paymentMethod: PaymentMethod? = null,
    ) {
        fun bundle() = Bundle().apply {
            putBoolean("available", available); putBoolean("connected", connected)
            putBoolean("checking", checking); putBoolean("enabled", protectionEnabled)
            putBoolean("running", protectionRunning); putString("details", details)
            putString("method", paymentMethod?.name)
        }
        companion object {
            fun from(bundle: Bundle) = State(
                available = bundle.getBoolean("available"), connected = bundle.getBoolean("connected"),
                checking = bundle.getBoolean("checking", true), protectionEnabled = bundle.getBoolean("enabled"),
                protectionRunning = bundle.getBoolean("running"), details = bundle.getString("details").orEmpty(),
                paymentMethod = PaymentMethod.entries.firstOrNull { it.name == bundle.getString("method") },
            )
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val dispatcher = Executors.newSingleThreadExecutor { work ->
        Thread(work, "listener-ui-bridge").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val checking = AtomicBoolean(false)
    private val startRequested = AtomicBoolean(false)
    @Volatile private var cached = State()
    @Volatile private var updatedAt = 0L
    internal var processNameReader: ((Context) -> String?)? = null

    fun ownsListener(context: Context): Boolean {
        val name = processNameReader?.invoke(context) ?: if (Build.VERSION.SDK_INT >= 28)
            Application.getProcessName() else context.getSystemService(ActivityManager::class.java)
                .runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        return name.orEmpty().ifBlank { context.applicationInfo.processName } == context.packageName
    }

    internal fun localState(context: Context) = State(
        available = true,
        connected = NotificationAccess.isEnabled(context) && PaymentNotificationService.isConnected,
        checking = ListenerRecovery.checkingInitialConnection,
        protectionEnabled = ListenerProtection.isEnabled(context),
        protectionRunning = ListenerProtectionService.isRunning,
        details = NotificationDiagnostics.summary(context),
        paymentMethod = DetectedPaymentMethod.localLatest(context),
    )

    fun state(context: Context): State {
        if (ownsListener(context)) return localState(context)
        if (updatedAt != 0L && SystemClock.elapsedRealtime() - updatedAt <= 10_000L) return cached
        // A cached true must not turn a dead or unreachable process into "connected".
        return cached.copy(available = false, connected = false, checking = true,
            protectionRunning = false, details = "后台状态暂不可读，正在重新检查")
    }

    fun check(context: Context, startProtection: Boolean = false) {
        if (ownsListener(context)) {
            ListenerRecovery.check(context)
            if (startProtection) ListenerProtection.startIfEnabled(context)
            return
        }
        if (startProtection) startRequested.set(true)
        if (!checking.compareAndSet(false, true)) return
        request(context, if (startRequested.getAndSet(false)) "check_start" else "check") {
            checking.set(false)
            // Application startup may already be checking when the first Activity resumes.
            if (startRequested.get()) check(context)
        }
    }

    fun enable(context: Context, done: (Boolean) -> Unit) {
        if (ownsListener(context)) { done(ListenerProtection.enable(context)); return }
        request(context, "enable") { done(it?.getBoolean("started") == true) }
    }

    fun disable(context: Context, done: () -> Unit = {}) {
        if (ownsListener(context)) { ListenerProtection.disable(context); done() }
        else request(context, "disable") { done() }
    }

    fun repair(context: Context) {
        if (ownsListener(context)) {
            NotificationAccess.resetRebindThrottle(); ListenerBindingRepair.reset(context); ListenerRecovery.check(context)
        } else request(context, "repair") {}
    }

    private fun request(context: Context, method: String, done: (Bundle?) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val result = runCatching { call(app, method) }.getOrNull()
            if (result != null) { cached = State.from(result); updatedAt = SystemClock.elapsedRealtime() }
            else { cached = cached.copy(available = false, connected = false, checking = true); updatedAt = 0L }
            main.post { done(result) }
        }
    }

    internal fun call(context: Context, method: String): Bundle? = context.contentResolver.call(
        Uri.parse("content://${context.packageName}.listener-runtime"), method, null, null)

    suspend fun latestPayment(context: Context): PaymentMethod? {
        if (ownsListener(context)) return DetectedPaymentMethod.localLatest(context)
        val app = context.applicationContext
        // Waiting for a frozen Binder must never block the editor's main thread.
        val pending = scope.async {
            runCatching { call(app, "state")?.let(State::from)?.paymentMethod }.getOrNull()
        }
        return withTimeoutOrNull(1_500L) { pending.await() } ?: state(app).paymentMethod
    }
}
