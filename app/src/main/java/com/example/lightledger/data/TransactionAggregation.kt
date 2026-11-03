package com.example.lightledger.data

/** Values proposed for a bill that replaces several existing bills. */
data class AggregationPreview(
    val amountCents: Long,
    val type: TransactionType,
    val timestamp: Long,
    val paymentMethod: PaymentMethod,
    val categoryId: Long?,
    val merchant: String?,
    val note: String?,
)

object TransactionAggregation {
    fun preview(bills: List<LedgerTransaction>): AggregationPreview {
        require(bills.size >= 2) { "至少选择两笔账单" }
        require(bills.map { it.id }.distinct().size == bills.size && bills.all { it.id > 0 }) { "账单选择无效" }
        require(bills.all { it.type == bills.first().type }) { "只能聚合同一收支类型的账单" }
        require(bills.all { it.type != TransactionType.REFUND && it.refundOfTransactionId == null }) { "已关联退款的账单不能聚合" }
        require(bills.all { it.amountCents > 0 && it.amountConfirmed }) { "请先确认每笔账单的金额" }
        val latest = bills.maxWith(compareBy<LedgerTransaction> { it.timestamp }.thenBy { it.id })
        val amount = bills.fold(0L) { sum, bill -> Math.addExact(sum, bill.amountCents) }
        fun <T> mostFrequent(value: (LedgerTransaction) -> T): T = bills.groupBy(value)
            .maxWith(compareBy<Map.Entry<T, List<LedgerTransaction>>> { it.value.size }
                .thenBy { group -> group.value.maxOf { it.timestamp } }
                .thenBy { group -> group.value.maxOf { it.id } }).key
        val merchant = bills.map { it.merchant?.trim().orEmpty() }.distinct().singleOrNull()?.ifBlank { null }
        val notes = bills.mapNotNull { it.note?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        return AggregationPreview(amount, latest.type, latest.timestamp,
            mostFrequent { it.paymentMethod }, mostFrequent { it.categoryId }, merchant,
            notes.joinToString("；").ifBlank { null })
    }
}
