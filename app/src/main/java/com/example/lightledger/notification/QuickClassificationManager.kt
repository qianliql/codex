package com.example.lightledger.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.lightledger.data.Category
import com.example.lightledger.ui.TransactionEditorActivity
import com.example.lightledger.ui.UiFormat

object QuickClassificationManager {
    const val ACTION_CLASSIFY = "com.example.lightledger.CLASSIFY_EXPENSE"
    const val EXTRA_TRANSACTION_ID = "transactionId"
    const val EXTRA_CATEGORY_ID = "categoryId"
    private const val CHANNEL_ID = "quick_classification"

    @SuppressLint("MissingPermission")
    fun show(context: Context, transactionId: Long, amountCents: Long, categories: List<Category>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val channel = NotificationChannel(CHANNEL_ID, "快速分类", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "新支出的快捷分类按钮"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val open = Intent(context, TransactionEditorActivity::class.java).apply {
            data = Uri.parse("lightledger://transaction/$transactionId")
            putExtra("id", transactionId)
        }
        val openIntent = PendingIntent.getActivity(context, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("刚刚支出 ${UiFormat.money(amountCents)}")
            .setContentText("点选分类；点开可选择更多分类")
            .setAutoCancel(true)
            .setContentIntent(openIntent)

        listOf("正餐", "零食", "日用").forEach { name ->
            val category = categories.firstOrNull { it.name == name } ?: return@forEach
            val action = Intent(context, QuickCategoryReceiver::class.java).apply {
                setAction(ACTION_CLASSIFY)
                data = Uri.parse("lightledger://classify/$transactionId/${category.id}")
                putExtra(EXTRA_TRANSACTION_ID, transactionId)
                putExtra(EXTRA_CATEGORY_ID, category.id)
            }
            val pending = PendingIntent.getBroadcast(context, 0, action,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notification.addAction(0, name, pending)
        }
        notification.addAction(0, "更多", openIntent)
        try { manager.notify(notificationId(transactionId), notification.build()) }
        catch (_: SecurityException) { /* Permission may have been revoked after the check. */ }
    }

    fun cancel(context: Context, transactionId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(transactionId))
    }

    private fun notificationId(transactionId: Long): Int = transactionId.toInt()
}
