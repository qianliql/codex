package com.example.lightledger.parsing

import java.math.BigDecimal

/** Chooses a currency amount only when one non-discount candidate is reliable. */
object AmountExtractor {
    private val amountPattern = Regex("(?:[¥￥]\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?(?![0-9.])|(?<![0-9.])[0-9][0-9,]*(?:\\.[0-9]{1,2})?\\s*元)")
    private val nonAmountCharacter = Regex("[^0-9.]")
    private val highKeywords = listOf("实付金额", "付款金额", "支付金额", "交易金额", "退款金额", "收款金额", "实付", "付款", "支付", "退款", "收款")
    private val lowKeywords = listOf("原价", "优惠", "红包", "余额", "累计", "立减", "折扣")
    private data class Candidate(val cents: Long, val priority: Int)

    fun extract(text: String): Long? {
        val candidates = amountPattern.findAll(text).mapNotNull { match ->
            val digits = match.value.replace(nonAmountCharacter, "")
            val cents = try { BigDecimal(digits).movePointRight(2).longValueExact() } catch (_: Exception) { 0L }
            if (cents <= 0) return@mapNotNull null
            val lineStart = text.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
            val prefix = text.substring(maxOf(lineStart, match.range.first - 30), match.range.first)
            val high = highKeywords.maxOfOrNull(prefix::lastIndexOf) ?: -1
            val low = lowKeywords.maxOfOrNull(prefix::lastIndexOf) ?: -1
            Candidate(cents, when {
                high > low -> 1
                low >= 0 -> -1
                else -> 0
            })
        }.toList()
        val high = candidates.filter { it.priority == 1 }.map { it.cents }.distinct()
        if (high.isNotEmpty()) return high.singleOrNull()
        return candidates.filter { it.priority == 0 }.map { it.cents }.distinct().singleOrNull()
    }
}
