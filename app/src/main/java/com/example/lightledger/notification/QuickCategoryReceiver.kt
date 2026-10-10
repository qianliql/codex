package com.example.lightledger.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.lightledger.LedgerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class QuickCategoryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != QuickClassificationManager.ACTION_CLASSIFY) return
        val transactionId = intent.getLongExtra(QuickClassificationManager.EXTRA_TRANSACTION_ID, 0)
        val categoryId = intent.getLongExtra(QuickClassificationManager.EXTRA_CATEGORY_ID, 0)
        if (transactionId <= 0 || categoryId <= 0) return
        val pending = goAsync()
        val app = context.applicationContext as LedgerApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (app.repository.classifyExpense(transactionId, categoryId)) {
                    QuickClassificationManager.cancel(app, transactionId)
                }
            } catch (e: Exception) {
                Log.w("QuickCategory", "Could not classify expense", e)
            } finally { pending.finish() }
        }
    }
}
