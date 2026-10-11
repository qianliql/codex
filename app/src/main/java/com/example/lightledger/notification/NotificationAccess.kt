package com.example.lightledger.notification

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.PowerManager
import android.net.Uri
import android.service.notification.NotificationListenerService
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object NotificationAccess {
    private var lastRebindAt: Long? = null
    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, PaymentNotificationService::class.java)
        return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun reconnectIfNeeded(context: Context) {
        if (!isEnabled(context) || PaymentNotificationService.isConnected) return
        val now = SystemClock.elapsedRealtime()
        if (lastRebindAt?.let { now - it < 30_000L } == true) return
        lastRebindAt = now
        runCatching {
            NotificationListenerService.requestRebind(ComponentName(context, PaymentNotificationService::class.java))
        }
    }

    internal fun resetRebindThrottle() { lastRebindAt = null }

    fun repair(activity: Activity) {
        MaterialAlertDialogBuilder(activity).setTitle("恢复自动记账")
            .setMessage("正在自动尝试重连。闲置后经常断开，可以在“我的”开启后台保护，并允许后台运行。仍无法连接时，再关闭并重新开启通知读取权限。")
            .setPositiveButton("尝试重连") { _, _ ->
                resetRebindThrottle()
                ListenerRecovery.check(activity)
            }
            .setNeutralButton("后台设置") { _, _ -> backgroundGuide(activity) }
            .setNegativeButton("通知权限") { _, _ -> openSettings(activity) }.show()
    }

    fun batteryUnrestricted(context: Context) = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    fun backgroundRestricted(context: Context) = Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    fun backgroundGuide(activity: Activity) {
        val huawei = Build.MANUFACTURER.contains("huawei", true) || Build.MANUFACTURER.contains("honor", true)
        val message = if (huawei) "允许不受电池优化限制后，还请在系统设置的“应用启动管理”中找到轻账本，关闭自动管理，允许自启动、关联启动和后台运行。系统授权必须由你确认。"
            else "允许不受电池优化限制；如果系统仍会清理轻账本，请在应用的电池设置中允许后台运行或选择“不受限制”，并允许自启动。各手机的名称可能不同。"
        MaterialAlertDialogBuilder(activity).setTitle("允许后台运行").setMessage(message)
            .setPositiveButton("允许不受限") { _, _ -> requestBatteryExemption(activity) }
            .setNeutralButton("应用设置") { _, _ -> openAppDetails(activity) }
            .setNegativeButton("稍后", null).show()
    }

    internal fun batteryIntent(context: Context) = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
    internal fun requestBatteryExemption(activity: Activity) {
        val primary = if (batteryUnrestricted(activity)) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) else batteryIntent(activity)
        if (!tryOpen(activity, primary) && !tryOpen(activity, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppDetails(activity)
    }
    private fun openAppDetails(activity: Activity) {
        if (!tryOpen(activity, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${activity.packageName}"))))
            Toast.makeText(activity, "请在系统设置中允许轻账本后台运行", Toast.LENGTH_LONG).show()
    }
    private fun tryOpen(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent); true
    } catch (_: ActivityNotFoundException) { false }
    catch (_: SecurityException) { false }

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
