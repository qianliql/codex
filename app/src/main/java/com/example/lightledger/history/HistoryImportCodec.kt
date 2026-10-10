package com.example.lightledger.history

import com.example.lightledger.data.TransactionType
import org.json.JSONObject

data class HistoryBill(val importId: String, val timestamp: Long, val amountCents: Long,
    val type: TransactionType, val category: String, val note: String?, val sourceText: String?)
data class HistoryImport(val title: String, val expectedCount: Int, val expectedTotalCents: Long, val records: List<HistoryBill>)
data class HistoryImportResult(val imported: Int, val skipped: Int, val addedCents: Long)

/** A separate append-only format: it cannot be mistaken for a replacement backup. */
object HistoryImportCodec {
    fun decode(text: String): HistoryImport {
        val root = JSONObject(text)
        require(root.getString("format") == "lightledger.history.v1") { "请选择轻账本历史账单导入文件" }
        fun integer(obj: JSONObject, key: String): Long = obj.get(key).toString().toLongOrNull() ?: error("$key 必须是整数")
        val array = root.getJSONArray("records")
        require(array.length() in 1..10000) { "导入笔数应为1至10000笔" }
        fun nullable(obj: JSONObject, key: String) = if (!obj.has(key) || obj.isNull(key)) null else obj.getString(key)
        val count = integer(root, "expectedCount")
        require(count in 1..10000) { "导入笔数无效" }
        return HistoryImport(root.getString("title"), count.toInt(), integer(root, "expectedTotalCents"),
            (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                HistoryBill(row.getString("importId"), integer(row, "timestamp"), integer(row, "amountCents"),
                    TransactionType.valueOf(row.getString("type")), row.getString("category"), nullable(row, "note"), nullable(row, "sourceText"))
            }).also(::validate)
    }
    fun validate(data: HistoryImport) {
        require(data.title.isNotBlank() && data.title.length <= 100) { "导入标题无效" }
        require(data.records.size in 1..10000 && data.records.size == data.expectedCount) { "笔数与文件标记不一致" }
        require(data.records.map { it.importId }.toSet().size == data.records.size) { "文件含重复导入标识" }
        require(data.records.all { bill ->
            bill.importId.matches(Regex("[a-zA-Z0-9:_-]{1,120}")) && bill.timestamp in 0L..4133980800000L &&
                bill.amountCents in 1L..1000000000000L && bill.category.isNotBlank() && bill.category.length <= 50 &&
                (bill.note == null || bill.note.length <= 4000) && (bill.sourceText == null || bill.sourceText.length <= 5000)
        }) { "账单日期、金额、分类或备注无效" }
        require(data.records.sumOf { it.amountCents } == data.expectedTotalCents) { "账单合计与文件标记不一致" }
    }
}
