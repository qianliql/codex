package com.example.lightledger.notification

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationAccess {
    private var lastRebindAt = 0L
    fun isEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun reconnectIfNeeded(context: Context) {
        if (!isEnabled(context) || PaymentNotificationService.isConnected) return
        val now = SystemClock.elapsedRealtime()
        if (lastRebindAt != 0L && now - lastRebindAt < 30_000L) return
        lastRebindAt = now
        runCatching {
            NotificationListenerService.requestRebind(ComponentName(context, PaymentNotificationService::class.java))
        }
    }

    fun requestPostPermissionOrOpenSettings(activity: Activity, launcher: ActivityResultLauncher<String>) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            try {
                activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(activity, "请在系统设置中打开轻账本的通知", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun openSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                activity.startActivity(detailIntent(activity))
                return
            } catch (_: ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        try { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "无法打开通知访问设置，请在系统设置中手动开启", Toast.LENGTH_LONG).show()
        }
    }
    internal fun detailIntent(context: Context): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, PaymentNotificationService::class.java).flattenToString())
}
