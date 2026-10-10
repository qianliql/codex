package com.example.lightledger

import android.app.Application
import com.example.lightledger.data.LedgerDatabase
import com.example.lightledger.data.LedgerRepository

class LedgerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The retired opt-in trace has no UI or background capture in 0.6.0.
        getSharedPreferences("payment_trace", MODE_PRIVATE).edit().clear().apply()
    }
    val repository by lazy { LedgerRepository(LedgerDatabase.get(this).dao()) }
}
