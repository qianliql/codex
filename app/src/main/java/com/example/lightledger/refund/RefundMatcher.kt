package com.example.lightledger.refund

import com.example.lightledger.data.LedgerTransaction
import com.example.lightledger.data.PaymentMethod
import com.example.lightledger.data.TransactionType

object RefundMatcher {
    fun candidates(refund: LedgerTransaction, expenses: List<LedgerTransaction>): List<LedgerTransaction> {
        if (refund.type != TransactionType.REFUND || refund.amountCents <= 0) return emptyList()
        val normalizedMerchant = normalize(refund.merchant)
        return expenses.filter { expense ->
            expense.type == TransactionType.EXPENSE && expense.amountCents >= refund.amountCents &&
                expense.timestamp <= refund.timestamp &&
                compatibleMethod(refund.paymentMethod, expense.paymentMethod) &&
                (normalizedMerchant.isEmpty() || normalize(expense.merchant).isEmpty() ||
                    normalize(expense.merchant) == normalizedMerchant)
        }.sortedWith(compareByDescending<LedgerTransaction> { normalize(it.merchant) == normalizedMerchant && normalizedMerchant.isNotEmpty() }
            .thenBy { it.amountCents != refund.amountCents }
            .thenByDescending { it.timestamp })
    }

    fun confidentMatch(refund: LedgerTransaction, expenses: List<LedgerTransaction>): LedgerTransaction? {
        val merchant = normalize(refund.merchant)
        if (merchant.isEmpty() || refund.paymentMethod == PaymentMethod.OTHER) return null
        val strong = candidates(refund, expenses).filter {
            normalize(it.merchant) == merchant && it.paymentMethod == refund.paymentMethod &&
                it.amountCents == refund.amountCents
        }
        return strong.singleOrNull()
    }

    private fun compatibleMethod(a: PaymentMethod, b: PaymentMethod) =
        a == PaymentMethod.OTHER || b == PaymentMethod.OTHER || a == b

    private fun normalize(value: String?) = value.orEmpty().lowercase().trim()
        .replace(Regex("(退款|退回|已退)$"), "")
        .replace(Regex("[\\s·•_－-]"), "")
}
