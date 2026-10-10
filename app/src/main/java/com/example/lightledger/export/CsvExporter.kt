package com.example.lightledger.export

import com.example.lightledger.data.*
import java.io.Writer
import java.text.SimpleDateFormat
import java.math.BigDecimal
import java.util.Date
import java.util.Locale

object CsvExporter {
    fun write(snapshot: LedgerSnapshot, writer: Writer) {
        val categories = snapshot.categories.associateBy { it.id }
        val tags = snapshot.tags.associateBy { it.id }
        val tagsByTransaction = snapshot.transactionTags.groupBy { it.transactionId }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        writer.write("\uFEFF时间,类型,金额,商户,分类,标签,支付方式,来源,备注\r\n")
        snapshot.transactions.forEach { t ->
            val values = listOf(
                dateFormat.format(Date(t.timestamp)), t.type.name,
                BigDecimal.valueOf(t.amountCents, 2).toPlainString(),
                t.merchant.orEmpty(), categories[t.categoryId]?.name ?: "未分类",
                tagsByTransaction[t.id].orEmpty().mapNotNull { tags[it.tagId]?.name }.joinToString(";"),
                t.paymentMethod.name, t.source.name, t.note.orEmpty(),
            )
            writer.write(values.joinToString(",") { escape(it) } + "\r\n")
        }
    }

    private fun escape(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
}
