package com.example.lightledger.classification

import com.example.lightledger.data.MerchantRule

object MerchantRuleEngine {
    fun categoryFor(merchant: String?, rules: List<MerchantRule>): Long? {
        val value = merchant?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return rules.firstOrNull { rule ->
            val keyword = rule.keyword.trim().lowercase()
            keyword.isNotEmpty() && if (rule.exactMatch) value == keyword else value.contains(keyword)
        }?.categoryId
    }
}
