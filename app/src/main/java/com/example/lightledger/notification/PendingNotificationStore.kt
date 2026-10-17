package com.example.lightledger.notification

import android.content.Context
import com.example.lightledger.data.ClassificationStatus
import com.example.lightledger.data.LedgerTransaction
import com.example.lightledger.data.PaymentMethod
import com.example.lightledger.data.TransactionSource
import com.example.lightledger.data.TransactionType
import org.json.JSONObject

/** A small, private handoff for a parsed payment awaiting its Room write. */
internal object PendingNotificationStore {
    private const val PREFS = "pending_payment_notifications"
    private const val PREFIX = "payment_"

    @Synchronized fun enqueue(context: Context, transaction: LedgerTransaction): Boolean {
        val key = requireNotNull(transaction.notificationKey)
        require(transaction.amountCents > 0 && transaction.timestamp > 0)
        val value = JSONObject().apply {
            put("amountCents", transaction.amountCents)
            put("type", transaction.type.name)
            put("merchant", transaction.merchant)
            put("paymentMethod", transaction.paymentMethod.name)
            put("platform", transaction.platform)
            put("timestamp", transaction.timestamp)
            put("notificationKey", key)
        }.toString()
        // commit is intentional: an OEM may kill the listener before the asynchronous DB write.
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREFIX + key, value).commit()
    }

    @Synchronized fun pending(context: Context): List<LedgerTransaction> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .filterKeys { it.startsWith(PREFIX) }
            .mapNotNull { (storageKey, value) ->
                runCatching {
                    val json = JSONObject(value as String)
                    val key = json.getString("notificationKey")
                    require(storageKey == PREFIX + key)
                    val amount = json.getLong("amountCents")
                    val timestamp = json.getLong("timestamp")
                    require(amount > 0 && timestamp > 0)
                    val type = TransactionType.valueOf(json.getString("type"))
                    LedgerTransaction(
                        amountCents = amount,
                        type = type,
                        merchant = json.optString("merchant").takeIf { it.isNotBlank() && it != "null" },
                        paymentMethod = PaymentMethod.valueOf(json.getString("paymentMethod")),
                        platform = json.optString("platform").takeIf { it.isNotBlank() && it != "null" },
                        timestamp = timestamp,
                        source = TransactionSource.NOTIFICATION,
                        classificationStatus = if (type == TransactionType.REFUND)
                            ClassificationStatus.NEED_CONFIRM else ClassificationStatus.UNCLASSIFIED,
                        notificationKey = key,
                    )
                }.getOrNull()
            }.sortedBy { it.timestamp }

    @Synchronized fun remove(context: Context, key: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PREFIX + key).commit()
}
