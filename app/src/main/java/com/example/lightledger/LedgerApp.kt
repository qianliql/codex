package com.example.lightledger

import android.app.Application
import com.example.lightledger.data.LedgerDatabase
import com.example.lightledger.data.LedgerRepository

class LedgerApp : Application() {
    val repository by lazy { LedgerRepository(LedgerDatabase.get(this).dao()) }
}
