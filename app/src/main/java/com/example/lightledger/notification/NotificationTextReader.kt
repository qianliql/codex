package com.example.lightledger.notification

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle

/** Reads one current message. Compact, expanded and historical text must not be concatenated. */
internal object NotificationTextReader {
    data class Content(val title: String, val text: String)

    @Suppress("DEPRECATION")
    fun read(extras: Bundle): Content {
        fun value(key: String) = extras.getCharSequence(key)?.toString()?.trim().orEmpty()
        val titles = listOf(value(Notification.EXTRA_TITLE), value(Notification.EXTRA_TITLE_BIG), value(Notification.EXTRA_CONVERSATION_TITLE))
        var title = titles.firstOrNull(::officialTitle) ?: titles.firstOrNull { it.isNotBlank() }.orEmpty()
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?.mapNotNull { it as? Bundle }
            ?.filter { !it.getCharSequence("text").isNullOrBlank() }.orEmpty()
        val latest = messages.withIndex().maxWithOrNull(compareBy<IndexedValue<Bundle>> { it.value.getLong("time") }.thenBy { it.index })?.value
        val text = if (latest != null) {
            val sender = if (Build.VERSION.SDK_INT >= 28) (latest.getParcelable<android.os.Parcelable>("sender_person") as? Person)?.name?.toString() else null
            val senderName = sender ?: latest.getCharSequence("sender")?.toString()
            if (senderName != null && officialTitle(senderName)) title = senderName
            latest.getCharSequence("text")?.toString().orEmpty()
        } else value(Notification.EXTRA_BIG_TEXT).takeUnless { Regex("^(?:\\[?\\d+条\\]?\\s*(?:新消息|未读消息)|消息已隐藏)$").matches(it) }.orEmpty().ifBlank {
            value(Notification.EXTRA_TEXT).ifBlank {
                // Multiple inbox lines have no dependable event order. Never pick a historical amount.
                extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.singleOrNull()?.toString().orEmpty()
            }
        }
        return Content(title, text.trim().take(4000))
    }
    private fun officialTitle(title: String) = listOf("微信支付", "微信收款", "微信退款", "微信转账", "支付宝", "交易提醒", "支付提醒", "收款提醒", "转账提醒").any(title::contains)
}
