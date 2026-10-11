package com.example.lightledger

import android.app.Application
import com.example.lightledger.data.LedgerDatabase
import com.example.lightledger.data.LedgerRepository
import com.example.lightledger.notification.ListenerRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LedgerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The retired opt-in trace has no UI or background capture in 0.6.0.
        getSharedPreferences("payment_trace", MODE_PRIVATE).edit().clear().apply()
        ListenerRecovery.initialize(this)
    }
    // A listener rebind must not cancel a notification already queued for saving.
    val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository by lazy { LedgerRepository(LedgerDatabase.get(this).dao()) }
}
