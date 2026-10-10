package com.example.lightledger.duplicate

import com.example.lightledger.data.LedgerDao
import com.example.lightledger.data.LedgerTransaction
import com.example.lightledger.data.PaymentMethod
import kotlin.math.abs

class DuplicateDetector(private val dao: LedgerDao) {
    suspend fun find(screenshot: LedgerTransaction): LedgerTransaction? {
        if (screenshot.amountCents <= 0) return null
        val window = 10 * 60 * 1000L
        return dao.possibleDuplicates(screenshot.amountCents, screenshot.type, screenshot.paymentMethod,
            screenshot.timestamp - window, screenshot.timestamp + window, screenshot.timestamp)
            .filter { candidate ->
                val sameMethod = screenshot.paymentMethod == PaymentMethod.OTHER ||
                    candidate.paymentMethod == PaymentMethod.OTHER || candidate.paymentMethod == screenshot.paymentMethod
                val merchantCompatible = candidate.merchant.isNullOrBlank() || screenshot.merchant.isNullOrBlank() ||
                    normalize(candidate.merchant) == normalize(screenshot.merchant)
                sameMethod && merchantCompatible
            }
            .minByOrNull { abs(it.timestamp - screenshot.timestamp) }
    }

    private fun normalize(value: String?): String = value.orEmpty().lowercase().replace(Regex("[\\s·•_－-]"), "")
}
