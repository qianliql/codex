package com.example.lightledger.notification

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.example.lightledger.LedgerApp
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Private same-UID bridge. Only the listener process mutates recovery/protection preferences. */
class ListenerRuntimeProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = requireNotNull(context)
        check(Binder.getCallingUid() == android.os.Process.myUid()) { "Private listener state" }
        require(method in setOf("state", "check", "check_start", "enable", "disable", "repair"))
        val task = FutureTask {
            (context.applicationContext as LedgerApp).initializeListenerRuntime()
            var started = false
            when (method) {
                "check" -> ListenerRecovery.check(context)
                "check_start" -> { ListenerRecovery.check(context); started = ListenerProtection.startIfEnabled(context) }
                "enable" -> started = ListenerProtection.enable(context)
                "disable" -> ListenerProtection.disable(context)
                "repair" -> {
                    NotificationAccess.resetRebindThrottle()
                    ListenerBindingRepair.reset(context)
                    ListenerRecovery.check(context)
                }
            }
            ListenerRuntime.localState(context).bundle().apply { putBoolean("started", started) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run()
        else Handler(Looper.getMainLooper()).post(task)
        return try { task.get(2, TimeUnit.SECONDS) } catch (e: Exception) {
            Handler(Looper.getMainLooper()).removeCallbacks(task)
            task.cancel(false)
            null
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
