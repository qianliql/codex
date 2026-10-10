package com.example.lightledger.backup

import com.example.lightledger.data.*
import org.json.JSONArray
import org.json.JSONObject

/** Backup v2 is self-contained; parsing and validation finish before any database row is replaced. */
object BackupCodec {
    private fun JSONObject.putNullable(key: String, value: String?) { put(key, value ?: JSONObject.NULL) }
    private fun JSONObject.putNullable(key: String, value: Long?) { put(key, value ?: JSONObject.NULL) }

    fun encode(data: LedgerSnapshot): String = JSONObject().apply {
        put("schemaVersion", 2)
        put("categories", JSONArray().apply { data.categories.forEach { c -> put(JSONObject().apply {
            put("id", c.id); put("name", c.name); put("isSystem", c.isSystem); put("priority", c.priority); put("sortOrder", c.sortOrder); put("iconKey", c.iconKey)
        }) } })
        put("transactions", JSONArray().apply { data.transactions.forEach { t -> put(JSONObject().apply {
            put("id", t.id); put("amountCents", t.amountCents); put("type", t.type.name)
            putNullable("merchant", t.merchant); putNullable("categoryId", t.categoryId); put("paymentMethod", t.paymentMethod.name)
            putNullable("platform", t.platform); put("timestamp", t.timestamp); put("source", t.source.name)
            putNullable("note", t.note); put("classificationStatus", t.classificationStatus.name)
            putNullable("refundOfTransactionId", t.refundOfTransactionId); putNullable("notificationKey", t.notificationKey)
            putNullable("ocrText", t.ocrText); put("createdAt", t.createdAt); put("updatedAt", t.updatedAt)
        }) } })
        put("tags", JSONArray().apply { data.tags.forEach { put(JSONObject().apply { put("id", it.id); put("name", it.name) }) } })
        put("transactionTags", JSONArray().apply { data.transactionTags.forEach {
            put(JSONObject().apply { put("transactionId", it.transactionId); put("tagId", it.tagId) })
        } })
        put("merchantRules", JSONArray().apply { data.merchantRules.forEach { rule -> put(JSONObject().apply {
            put("id", rule.id); put("keyword", rule.keyword); put("categoryId", rule.categoryId); put("exactMatch", rule.exactMatch)
        }) } })
        put("settings", JSONArray().apply { data.settings.forEach { put(JSONObject().apply { put("key", it.key); put("value", it.value) }) } })
    }.toString()

    fun decode(json: String): LedgerSnapshot {
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") in 1..2) { "不支持的备份版本" }
        fun <T> rows(key: String, convert: (JSONObject) -> T): List<T> {
            val array = root.getJSONArray(key)
            return (0 until array.length()).map { convert(array.getJSONObject(it)) }
        }
        fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
        fun JSONObject.nullableLong(key: String): Long? = if (isNull(key)) null else getLong(key)
        val snapshot = LedgerSnapshot(
            categories = rows("categories") { Category(it.getLong("id"), it.getString("name"), it.getBoolean("isSystem"), it.optInt("priority", CategoryPriority.defaultFor(it.getString("name"))), it.optInt("sortOrder", CategoryCatalog.primary.indexOf(it.getString("name")).takeIf { n -> n >= 0 } ?: 100), it.optString("iconKey", "auto")) },
            transactions = rows("transactions") { t -> LedgerTransaction(
                id = t.getLong("id"), amountCents = t.getLong("amountCents"), type = TransactionType.valueOf(t.getString("type")),
                merchant = t.nullableString("merchant"), categoryId = t.nullableLong("categoryId"),
                paymentMethod = PaymentMethod.valueOf(t.getString("paymentMethod")), platform = t.nullableString("platform"),
                timestamp = t.getLong("timestamp"), source = TransactionSource.valueOf(t.getString("source")),
                note = t.nullableString("note"), classificationStatus = ClassificationStatus.valueOf(t.getString("classificationStatus")),
                refundOfTransactionId = t.nullableLong("refundOfTransactionId"), notificationKey = t.nullableString("notificationKey"),
                ocrText = t.nullableString("ocrText"), createdAt = t.getLong("createdAt"), updatedAt = t.getLong("updatedAt")) },
            tags = rows("tags") { Tag(it.getLong("id"), it.getString("name")) },
            transactionTags = rows("transactionTags") { TransactionTagCrossRef(it.getLong("transactionId"), it.getLong("tagId")) },
            merchantRules = rows("merchantRules") { MerchantRule(it.getLong("id"), it.getString("keyword"), it.getLong("categoryId"), it.getBoolean("exactMatch")) },
            settings = rows("settings") { AppSetting(it.getString("key"), it.getString("value")) },
        )
        validate(snapshot)
        return CategoryNormalization.withoutHousehold(snapshot)
    }

    private fun validate(data: LedgerSnapshot) {
        fun <T> unique(values: List<T>) = values.size == values.toSet().size
        val categoryIds = data.categories.map { it.id }
        val transactionIds = data.transactions.map { it.id }
        val tagIds = data.tags.map { it.id }
        require(data.categories.all { it.priority in 0..2 }) { "分类优先级无效" }
        require(listOf(0, 1).all { p -> data.categories.count { it.priority == p && it.name != CategoryCatalog.unclassified } <= CategoryPriority.limit(p) }) { "常用分类数量超过限制" }
        require(unique(categoryIds) && unique(transactionIds) && unique(tagIds) &&
            unique(data.merchantRules.map { it.id }) && unique(data.settings.map { it.key })) { "备份含重复 ID" }
        require(categoryIds.all { it > 0 } && transactionIds.all { it > 0 } && tagIds.all { it > 0 }) { "备份 ID 无效" }
        require(data.transactions.all { it.amountCents >= 0 && (it.categoryId == null || it.categoryId in categoryIds) &&
            (it.refundOfTransactionId == null || it.refundOfTransactionId in transactionIds) }) { "账单数据无效" }
        val transactionsById = data.transactions.associateBy { it.id }
        val refundsByExpense = data.transactions.filter { it.refundOfTransactionId != null }.groupBy { it.refundOfTransactionId }
        require(refundsByExpense.all { (expenseId, refunds) ->
            val expense = transactionsById[expenseId]
            expense?.type == TransactionType.EXPENSE && refunds.all { it.type == TransactionType.REFUND } &&
                refunds.sumOf { it.amountCents } <= expense.amountCents
        }) { "退款关联无效" }
        require(data.transactionTags.all { it.transactionId in transactionIds && it.tagId in tagIds } &&
            unique(data.transactionTags.map { it.transactionId to it.tagId })) { "标签关联无效" }
        require(data.merchantRules.all { it.categoryId in categoryIds && it.keyword.isNotBlank() } &&
            unique(data.merchantRules.map { it.keyword })) { "商户规则无效" }
        require(unique(data.categories.map { it.name }) && unique(data.tags.map { it.name }) &&
            unique(data.transactions.mapNotNull { it.notificationKey })) { "备份含重复名称或通知" }
    }
}
