package com.example.lightledger.ui

import android.os.Bundle
import com.example.lightledger.data.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class TransactionFilter(
    val search: String = "", val start: Long = 0L, val end: Long = Long.MAX_VALUE,
    val categoryId: Long = -1L, val tagId: Long = 0L, val method: String = "",
    val source: String = "", val type: String = "", val pendingOnly: Boolean = false,
) {
    val activeCount get() = listOf(search.isNotBlank(), start != 0L || end != Long.MAX_VALUE,
        categoryId != -1L, tagId != 0L, method.isNotBlank(), source.isNotBlank(), type.isNotBlank(), pendingOnly).count { it }
    val validDates get() = start >= 0 && end > start
    fun writeTo(bundle: Bundle) = bundle.apply {
        putString("filter_search", search); putLong("filter_start", start); putLong("filter_end", end)
        putLong("filter_category", categoryId); putLong("filter_tag", tagId); putString("filter_method", method)
        putString("filter_source", source); putString("filter_type", type); putBoolean("pending_only", pendingOnly)
    }
    fun description(categories: List<Category>, tags: List<Tag>): String {
        val date = SimpleDateFormat("yyyy.MM.dd", Locale.CHINA)
        return listOfNotNull(
            search.takeIf { it.isNotBlank() }?.let { "关键词：$it" },
            if (start != 0L || end != Long.MAX_VALUE) "${if (start == 0L) "不限开始" else date.format(Date(start))} — ${if (end == Long.MAX_VALUE) "不限结束" else date.format(Date(end - 1))}" else null,
            if (categoryId == 0L) "未分类" else if (categoryId > 0) categories.firstOrNull { it.id == categoryId }?.name ?: "指定分类" else null,
            if (tagId > 0) tags.firstOrNull { it.id == tagId }?.name?.let { "标签：$it" } ?: "指定标签" else null,
            PaymentMethod.entries.firstOrNull { it.name == method }?.let(UiFormat::method),
            TransactionType.entries.firstOrNull { it.name == type }?.let(UiFormat::type),
            sourceLabel(source).takeIf { source.isNotBlank() },
            if (pendingOnly) "待处理" else null,
        ).joinToString(" · ")
    }
    companion object {
        const val EXTRA = "transaction_filters"
        fun readFrom(b: Bundle?) = if (b == null) TransactionFilter() else TransactionFilter(
            b.getString("filter_search").orEmpty(), b.getLong("filter_start", 0), b.getLong("filter_end", Long.MAX_VALUE),
            b.getLong("filter_category", -1), b.getLong("filter_tag", 0), b.getString("filter_method").orEmpty(),
            b.getString("filter_source").orEmpty(), b.getString("filter_type").orEmpty(), b.getBoolean("pending_only"))
        fun sourceLabel(value: String) = when (value) { "NOTIFICATION" -> "支付通知"; "SCREENSHOT" -> "截图导入"; "MANUAL" -> "手动记录"; else -> "不限" }
    }
}
internal enum class SearchDatePreset(val label: String) {
    ALL("不限"), LAST_SEVEN_DAYS("近7天"), THIS_MONTH("本月"), CUSTOM("自选");
    fun range(now: Long = System.currentTimeMillis()): Pair<Long, Long>? {
        val day = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return when (this) {
            ALL -> 0L to Long.MAX_VALUE
            LAST_SEVEN_DAYS -> {
                val end = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
                day.add(Calendar.DAY_OF_MONTH, -6); day.timeInMillis to end
            }
            THIS_MONTH -> {
                day.set(Calendar.DAY_OF_MONTH, 1); val start = day.timeInMillis
                day.add(Calendar.MONTH, 1); start to day.timeInMillis
            }
            CUSTOM -> null
        }
    }
}

