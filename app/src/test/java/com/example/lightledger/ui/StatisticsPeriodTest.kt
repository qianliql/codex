package com.example.lightledger.ui

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class StatisticsPeriodTest {
    @Test fun decemberMovesToJanuary() {
        val (start, end) = StatisticsPeriod(2026, 12).bounds()
        val a = Calendar.getInstance().apply { timeInMillis = start }
        val b = Calendar.getInstance().apply { timeInMillis = end }
        assertEquals(2026, a.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, a.get(Calendar.MONTH))
        assertEquals(2027, b.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, b.get(Calendar.MONTH))
    }

    @Test fun yearlyRangeIsTwelveMonths() {
        val (start, end) = StatisticsPeriod(2026, null).bounds()
        val a = Calendar.getInstance().apply { timeInMillis = start }
        val b = Calendar.getInstance().apply { timeInMillis = end }
        assertEquals(Calendar.JANUARY, a.get(Calendar.MONTH))
        assertEquals(2027, b.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, b.get(Calendar.MONTH))
    }
}
