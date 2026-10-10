package com.example.lightledger.classification

import com.example.lightledger.data.MerchantRule
import org.junit.Assert.*
import org.junit.Test

class MerchantRuleEngineTest {
    @Test fun exactRuleWinsBeforeKeywordAndUnknownStaysEmpty() {
        val rules = listOf(MerchantRule(1, "滴滴出行", 10, true), MerchantRule(2, "滴滴", 20, false))
        assertEquals(10L, MerchantRuleEngine.categoryFor("滴滴出行", rules))
        assertEquals(20L, MerchantRuleEngine.categoryFor("滴滴快车", rules))
        assertNull(MerchantRuleEngine.categoryFor("麦当劳", rules))
    }
}
