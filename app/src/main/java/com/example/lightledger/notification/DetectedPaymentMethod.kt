package com.example.lightledger.notification

import android.content.Context
import com.example.lightledger.data.PaymentMethod

/** Stores only the method and time of a trusted payment notification. */
internal object DetectedPaymentMethod {
    private const val PREFS = "detected_payment_method"

    fun remember(context: Context, method: PaymentMethod, timestamp: Long) {
        if (method != PaymentMethod.WECHAT && method != PaymentMethod.ALIPAY) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Reconnecting replays retained notifications; an older one must not replace the latest choice.
        if (timestamp < prefs.getLong("time", 0)) return
        if (timestamp == prefs.getLong("time", 0) && prefs.getString("method", null) == method.name) return
        prefs.edit().putString("method", method.name).putLong("time", timestamp).apply()
    }

    fun latest(context: Context): PaymentMethod? = when (
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("method", null)
    ) {
        PaymentMethod.WECHAT.name -> PaymentMethod.WECHAT
        PaymentMethod.ALIPAY.name -> PaymentMethod.ALIPAY
        else -> null
    }
}
