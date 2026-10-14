package com.example.lightledger

import android.app.Application
import com.example.lightledger.data.LedgerDatabase
import com.example.lightledger.data.LedgerRepository
import com.example.lightledger.notification.ListenerRecovery
import com.example.lightledger.notification.ProcessExitDiagnostics
import com.example.lightledger.notification.ListenerRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob

class LedgerApp : Application() {
    private var listenerInitialized = false
    override fun onCreate() {
        super.onCreate()
        if (ListenerRuntime.ownsListener(this)) initializeListenerRuntime()
        else ListenerRuntime.check(this)
    }
    internal fun initializeListenerRuntime() {
        if (listenerInitialized) return
        listenerInitialized = true
        // The retired opt-in trace has no UI or background capture in 0.6.0.
        getSharedPreferences("payment_trace", MODE_PRIVATE).edit().clear().apply()
        ProcessExitDiagnostics.capture(this)
        ListenerRecovery.initialize(this)
    }
    // A listener rebind must not cancel a notification already queued for saving.
    // A notification may be replayed after reconnect. Keep parsing and database writes in
    // arrival order without tying up the listener callback or opening parallel SQLite work.
    @OptIn(ExperimentalCoroutinesApi::class)
    val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    val repository by lazy { LedgerRepository(LedgerDatabase.get(this).dao()) }
}
