package com.example.lightledger.notification

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log

/** requestRebind may be a no-op when the system still regards the listener as enabled. */
internal object ListenerBindingRepair {
    private const val PREFS = "listener_binding_repair"
    fun restoreInterruptedReset(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        if (!prefs.getBoolean("restore_pending", false)) return
        runCatching {
            context.packageManager.setComponentEnabledSetting(component(context), prefs.getInt("previous_state", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT), PackageManager.DONT_KILL_APP)
            prefs.edit().remove("restore_pending").remove("previous_state").commit()
        }.onFailure { Log.w("ListenerRepair", "Could not restore listener component", it) }
    }
    fun reset(context: Context): Boolean {
        if (!NotificationAccess.isEnabled(context) || PaymentNotificationService.isConnected) return false
        return runCatching {
            val component = component(context)
            if (Build.VERSION.SDK_INT >= 34) {
                NotificationListenerService.requestUnbind(component)
                NotificationListenerService.requestRebind(component)
            } else {
                val manager = context.packageManager
                val previous = manager.getComponentEnabledSetting(component)
                val prefs = context.getSharedPreferences(PREFS, 0)
                // Restore immediately; persist the old value in case the process dies mid-reset.
                check(prefs.edit().putInt("previous_state", previous).putBoolean("restore_pending", true).commit())
                try {
                    manager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                } finally {
                    manager.setComponentEnabledSetting(component, previous, PackageManager.DONT_KILL_APP)
                    prefs.edit().remove("restore_pending").remove("previous_state").commit()
                }
                NotificationListenerService.requestRebind(component)
            }
            true
        }.onFailure { Log.w("ListenerRepair", "Could not reset listener binding", it) }.getOrDefault(false)
    }
    private fun component(context: Context) = ComponentName(context, PaymentNotificationService::class.java)
}
